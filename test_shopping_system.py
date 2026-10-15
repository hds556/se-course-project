"""java_rebuild_v4 黑盒回归脚本（仅使用 Python 标准库）。

运行完整回归：
  python test_shopping_system.py --project "Java项目目录" --report-dir "项目外的结果目录"
单项复测：
  python test_shopping_system.py --project "Java项目目录" --report-dir "结果目录" --case AT-008

脚本将源码复制到系统临时目录，再用项目自带 Maven Wrapper 打包并启动。
数据库、上传文件、构建目录、日志均在项目目录外；不会改动被测项目。
需要 JDK 17、Python 3.10+，首次构建需要联网下载 Maven 及依赖。
"""
from __future__ import annotations

import argparse
import binascii
import concurrent.futures
from http.cookiejar import CookieJar
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zlib

CASES: dict[str, str] = {}
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_PRICE_CENTS = 9007199254740991


def test_case(case_id: str, title: str):
    def decorate(function):
        function.case_id = case_id
        function.case_title = title
        CASES[case_id] = function.__name__
        return function
    return decorate


def png_bytes() -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", binascii.crc32(kind + payload) & 0xffffffff))
    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    pixel = zlib.compress(b"\x00\x33\x66\x99\xff")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", pixel) + chunk(b"IEND", b""))


class Response:
    def __init__(self, status: int, headers, data: bytes):
        self.status = status
        self.headers = headers
        self.data = data

    def json(self):
        return json.loads(self.data.decode("utf-8"))


class Client:
    """Small urllib client that can retain a seller session cookie."""
    def __init__(self, base_url: str, keep_cookies: bool = False):
        self.base_url = base_url
        handlers = [urllib.request.HTTPCookieProcessor(CookieJar())] if keep_cookies else []
        self.opener = urllib.request.build_opener(*handlers)

    def request(self, method: str, path: str, *, body: bytes | None = None,
                content_type: str | None = None, json_body=None, timeout: int = 20) -> Response:
        headers = {}
        if json_body is not None or content_type == "application/json":
            body = json.dumps(json_body, ensure_ascii=False).encode("utf-8") if json_body is not None else body
            headers["Content-Type"] = "application/json"
        if content_type and content_type != "application/json":
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                return Response(response.status, response.headers, response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, error.headers, error.read())


def multipart(fields: dict[str, str], image: bytes | None = None,
              filename: str = "test.png", mime: str = "image/png") -> tuple[bytes, str]:
    boundary = "----codex-test-8fb289c4"
    pieces = []
    for name, value in fields.items():
        pieces.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n").encode())
    if image is not None:
        pieces.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n").encode())
        pieces.append(image + b"\r\n")
    pieces.append(f"--{boundary}--\r\n".encode())
    return b"".join(pieces), f"multipart/form-data; boundary={boundary}"


class JavaShoppingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.project = Path(CLI.project).resolve()
        cls.report_dir = Path(CLI.report_dir).resolve()
        java_home = os.environ.get("JAVA_HOME")
        java_name = "java.exe" if os.name == "nt" else "java"
        java_candidate = Path(java_home) / "bin" / java_name if java_home else None
        cls.java = str(java_candidate) if java_candidate and java_candidate.is_file() else shutil.which("java")
        if not cls.java:
            raise RuntimeError("找不到Java运行环境，请安装并配置JDK 17")
        version = subprocess.run([cls.java, "-version"], capture_output=True, text=True)
        version_text = version.stdout + version.stderr
        match = re.search(r'version "(?:1\.)?(\d+)', version_text)
        if not match or int(match.group(1)) < 17:
            raise RuntimeError("Java应用需要JDK 17或更新版本：" + version_text.strip())
        cls.work_root = Path(tempfile.mkdtemp(prefix="java-shopping-test-"))
        cls.source_copy = cls.work_root / "project"
        shutil.copytree(cls.project, cls.source_copy,
                        ignore=shutil.ignore_patterns(".git", "target", "data", "uploads", "__pycache__"))
        wrapper = cls.source_copy / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        if not wrapper.exists():
            raise RuntimeError("被测目录缺少 Maven Wrapper（mvnw.cmd/mvnw）")
        if os.name != "nt":
            wrapper.chmod(wrapper.stat().st_mode | 0o111)
        command = [str(wrapper), "-B", "-q", "-DskipTests", "package"]
        command = subprocess.list2cmdline(command) if os.name == "nt" else command
        build_log = cls.report_dir / "maven-build.log"
        cls.report_dir.mkdir(parents=True, exist_ok=True)
        with build_log.open("wb") as log:
            result = subprocess.run(command, cwd=cls.source_copy, shell=(os.name == "nt"),
                                   stdout=log, stderr=subprocess.STDOUT, timeout=900)
        if result.returncode != 0:
            tail = build_log.read_text(encoding="utf-8", errors="replace")[-6000:]
            raise RuntimeError(f"Java 项目构建失败，详见 {build_log}\n{tail}")
        cls.jar = cls.source_copy / "target" / "shopping-system-1.0.0.jar"
        if not cls.jar.exists():
            raise RuntimeError("Maven 构建成功，但没有找到 target/shopping-system-1.0.0.jar")
        cls.results: list[dict] = []
        cls._log_guard = threading.Lock()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.work_root, ignore_errors=True)

    def setUp(self):
        self.case_id = getattr(getattr(self, self._testMethodName), "case_id", self._testMethodName)
        self.sandbox = Path(tempfile.mkdtemp(prefix=f"{self.case_id}-", dir=self.work_root))
        self.db_file = self.sandbox / "data" / "shop.db"
        self.upload_dir = self.sandbox / "uploads"
        self.legacy_file = self.sandbox / "missing-legacy-data.json"
        self.port = self._free_port()
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.seller = Client(self.base_url, keep_cookies=True)
        self.buyer = Client(self.base_url)
        self.app_process = None
        self.log_handle = None
        self.addCleanup(self._cleanup)
        legacy = None
        if self.case_id == "AT-019":
            legacy = self.sandbox / "legacy.json"
            legacy.write_text(json.dumps({"products": [{"id": 41, "name": "迁移商品",
                "description": "legacy", "image": "/uploads/legacy.png", "priceCents": 1234,
                "status": "active", "tradingIntentId": None, "createdAt": "2026-10-01T00:00:00Z",
                "frozenAt": None, "result": None, "resultAt": None}], "intents": [{
                "id": 73, "productId": 41, "name": "历史买家", "phone": "13800000000",
                "passcode": "LEGACY42", "submittedAt": "2026-10-01T00:01:00Z",
                "stage": "queued", "outcome": None}]}, ensure_ascii=False),
                encoding="utf-8")
        self._start_app(legacy)

    @staticmethod
    def _free_port() -> int:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    def _start_app(self, legacy_file: Path | None = None):
        self.db_file.parent.mkdir(parents=True, exist_ok=True)
        self.upload_dir.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env.update({
            "SHOP_HOST": "127.0.0.1", "SHOP_PORT": str(self.port),
            "SHOP_DB_FILE": str(self.db_file), "SHOP_UPLOAD_DIR": str(self.upload_dir),
            "SHOP_DATA_FILE": str(legacy_file or self.legacy_file),
            "DEFAULT_SELLER_PASSWORD": "admin1234",
        })
        app_log = self.report_dir / f"application-{self.case_id}.log"
        self.log_handle = app_log.open("ab")
        command = ["java", "-jar", str(self.jar),
                   "--server.address=127.0.0.1", f"--server.port={self.port}",
                   f"--spring.datasource.url=jdbc:sqlite:{self.db_file}",
                   f"--shopping.upload-dir={self.upload_dir}",
                   f"--shopping.legacy-data-file={legacy_file or self.legacy_file}"]
        command[0] = self.java
        self.app_process = subprocess.Popen(command, cwd=self.source_copy,
                                            env=env, stdout=self.log_handle,
                                            stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 45
        last_error = None
        bootstrap_client = Client(self.base_url, keep_cookies=True)
        while time.monotonic() < deadline:
            if self.app_process.poll() is not None:
                break
            try:
                response = self.buyer.request("GET", "/api/product", timeout=2)
                if response.status == 200:
                    login = self._login(bootstrap_client)
                    if login.status == 200:
                        return
                    last_error = f"default seller initialization pending (HTTP {login.status})"
                else:
                    last_error = f"readiness returned HTTP {response.status}"
            except Exception as exc:  # startup polling only
                last_error = str(exc)
            time.sleep(0.25)
        if self.app_process.poll() is not None:
            raise RuntimeError(f"Java 服务启动后退出（{last_error}），查看 {app_log}")
        raise RuntimeError(f"Java 服务在45秒内未就绪（{last_error}），查看 {app_log}")

    def _stop_app(self):
        if self.app_process and self.app_process.poll() is None:
            self.app_process.terminate()
            try:
                self.app_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.app_process.kill()
                self.app_process.wait(timeout=5)
        self.app_process = None
        if self.log_handle:
            self.log_handle.close()
            self.log_handle = None

    def _cleanup(self):
        self._stop_app()
        shutil.rmtree(self.sandbox, ignore_errors=True)

    def _expect(self, response: Response, status: int, message: str = "") -> Response:
        self.assertEqual(response.status, status,
                         message or response.data.decode("utf-8", errors="replace")[:500])
        return response

    def _login(self, client: Client | None = None, password: str = "admin1234") -> Response:
        return (client or self.seller).request("POST", "/api/auth/login",
                    json_body={"username": "admin", "password": password})

    def _publish(self, price: str = "12.50", *, image: bytes | None = None,
                 name: str = "杯子", description: str = "测试商品",
                 filename: str = "test.png", mime: str = "image/png",
                 client: Client | None = None) -> Response:
        body, content_type = multipart({"name": name, "description": description, "price": price},
                                       png_bytes() if image is None else image, filename, mime)
        return (client or self.seller).request("POST", "/api/products", body=body,
                                                content_type=content_type, timeout=60)

    def _create_product(self) -> dict:
        self._expect(self._login(), 200)
        self._expect(self._publish(), 201)
        return self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"]

    def _intent(self, name="买家", phone="13800000000", client: Client | None = None) -> Response:
        return (client or self.buyer).request("POST", "/api/intents",
                    json_body={"name": name, "phone": phone})

    def _admin(self, path: str, body=None, *, method="POST") -> Response:
        payload = None if method == "GET" else (body if body is not None else {})
        return self.seller.request(method, "/api/admin/" + path, json_body=payload)

    def _codes(self, n: int = 2) -> list[str]:
        values = []
        for i in range(n):
            response = self._expect(self._intent(f"买家{i+1}", f"13800000{i:03d}"), 201)
            values.append(response.json()["passcode"])
        return values

    @test_case("AT-001", "空库初始化和静态页面")
    def test_001_initial_and_static(self):
        product = self._expect(self.buyer.request("GET", "/api/product"), 200).json()
        self.assertIsNone(product["product"])
        self._expect(self._intent(), 409)
        for path, mime in (("/", "text/html"), ("/admin.html", "text/html"),
                           ("/style.css", "text/css"), ("/app.js", "javascript"),
                           ("/admin.js", "javascript")):
            response = self._expect(self.buyer.request("GET", path), 200)
            self.assertIn(mime, response.headers.get("Content-Type", ""))

    @test_case("AT-002", "未登录时管理接口拒绝访问")
    def test_002_auth_guards(self):
        for path in ("/api/admin/product", "/api/admin/intents", "/api/admin/history"):
            self._expect(self.buyer.request("GET", path), 401)
        for path in ("freeze", "unfreeze", "trade/start", "trade/success", "trade/fail", "trade/advance"):
            self._expect(self.buyer.request("POST", "/api/admin/" + path, json_body={}), 401)
        body, mime = multipart({"name": "x", "description": "y", "price": "1"}, png_bytes())
        self._expect(self.buyer.request("POST", "/api/products", body=body, content_type=mime), 401)

    @test_case("AT-003", "卖家登录、错误密码和会话隔离")
    def test_003_login_session(self):
        self._expect(self._login(password="bad-password"), 401)
        response = self._expect(self._login(), 200)
        cookie = response.headers.get("Set-Cookie", "")
        for flag in ("HttpOnly", "SameSite=Strict", "Path=/"):
            self.assertIn(flag, cookie)
        self._expect(self.seller.request("GET", "/api/admin/product"), 200)
        self._expect(self.buyer.request("GET", "/api/admin/product"), 401)
        self.buyer.opener.addheaders = [("Cookie", "seller_session=forged")]
        self._expect(self.buyer.request("GET", "/api/admin/product"), 401)

    @test_case("AT-004", "修改密码的错误输入与重启后生效")
    def test_004_password(self):
        self._expect(self._login(), 200)
        self._expect(self.seller.request("POST", "/api/auth/password",
                    json_body={"oldPassword": "wrong", "newPassword": "abcd1234"}), 400)
        for value in ("abc1234", "abcdefgh", "12345678", "", None):
            self._expect(self.seller.request("POST", "/api/auth/password",
                        json_body={"oldPassword": "admin1234", "newPassword": value}), 400)
        self._expect(self.seller.request("POST", "/api/auth/password",
                    json_body={"oldPassword": "admin1234", "newPassword": "abcd1234"}), 200)
        self._expect(self._login(Client(self.base_url, keep_cookies=True), "admin1234"), 401)
        self._expect(self._login(Client(self.base_url, keep_cookies=True), "abcd1234"), 200)

    @test_case("AT-005", "发布商品并校验公开字段和图片")
    def test_005_publish_and_read(self):
        self._expect(self._login(), 200)
        created = self._expect(self._publish(), 201).json()
        product = self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"]
        self.assertEqual(product["id"], created["id"])
        self.assertEqual(product["priceCents"], 1250)
        self.assertEqual(product["status"], "active")
        self.assertTrue(product["buyable"])
        self.assertEqual(set(product), {"id", "name", "description", "image", "priceCents", "status", "buyable"})
        image = self._expect(self.buyer.request("GET", product["image"]), 200)
        self.assertEqual(image.data, png_bytes())

    @test_case("AT-006", "图片格式校验和文件名隔离")
    def test_006_image_validation(self):
        self._expect(self._login(), 200)
        for data, filename, mime in ((b"not image", "fake.png", "image/png"),
                                     (png_bytes(), "fake.gif", "image/gif"),
                                     (b"", "empty.png", "image/png")):
            response = self._publish(image=data, filename=filename, mime=mime)
            self.assertGreaterEqual(response.status, 400)
        self.assertEqual(list(self.upload_dir.iterdir()), [])
        self._expect(self._publish(filename="../outside.png"), 201)
        self.assertFalse((self.sandbox / "outside.png").exists())

    @test_case("AT-007", "价格非法输入拒绝")
    def test_007_invalid_prices(self):
        self._expect(self._login(), 200)
        for price in ("0", "0.00", "-1", "1.234", "1e2", "NaN", "Infinity", "01", "", "abc"):
            response = self._publish(price=price)
            self.assertEqual(response.status, 400, f"price={price!r}: {response.data[:300]!r}")
        self.assertIsNone(self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"])
        self.assertEqual(list(self.upload_dir.iterdir()), [])

    @test_case("AT-008", "DEF-001：5000位价格返回400 JSON且无副作用")
    def test_008_extreme_price(self):
        self._expect(self._login(), 200)
        before = hashlib.sha256(self.db_file.read_bytes()).hexdigest()
        response = self._publish(price="9" * 5000)
        self.assertEqual(response.status, 400, response.data[:500])
        self.assertIn("application/json", response.headers.get("Content-Type", ""))
        self.assertEqual(hashlib.sha256(self.db_file.read_bytes()).hexdigest(), before)
        self.assertEqual(list(self.upload_dir.iterdir()), [])
        self.assertIsNone(self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"])

    @test_case("AT-009", "最高金额及超范围价格")
    def test_009_price_maximum(self):
        self._expect(self._login(), 200)
        response = self._publish(price="90071992547409.92")
        self.assertEqual(response.status, 400)
        self.assertEqual(list(self.upload_dir.iterdir()), [])
        self._expect(self._publish(price="90071992547409.91"), 201)
        product = self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"]
        self.assertEqual(product["priceCents"], MAX_PRICE_CENTS)

    @test_case("AT-010", "5MiB图片边界值")
    def test_010_image_limit(self):
        self._expect(self._login(), 200)
        image = png_bytes()
        oversized = image + b"\x00" * (MAX_IMAGE_BYTES + 1 - len(image))
        response = self._publish(image=oversized)
        self.assertGreaterEqual(response.status, 400)
        self.assertEqual(list(self.upload_dir.iterdir()), [])
        boundary = image + b"\x00" * (MAX_IMAGE_BYTES - len(image))
        self._expect(self._publish(image=boundary), 201)
        saved = next(self.upload_dir.iterdir())
        self.assertEqual(saved.stat().st_size, MAX_IMAGE_BYTES)

    @test_case("AT-011", "买家提交、姓名电话必填及口令格式")
    def test_011_intent_validation(self):
        self._create_product()
        for body in ({"name": "  ", "phone": "13800000000"},
                     {"name": "买家", "phone": " "}, {"name": "买家"}):
            response = self.buyer.request("POST", "/api/intents", json_body=body)
            self.assertEqual(response.status, 400, response.data[:300])
        response = self._expect(self._intent(" 买家 ", " 13800000000 "), 201)
        code = response.json()["passcode"]
        self.assertEqual(len(code), 8)
        self.assertTrue(code.isupper() and code.isalnum())
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": code.lower()}), 200).json()["position"], 1)

    @test_case("AT-012", "重複联系方式保持独立排队")
    def test_012_queue_duplicates(self):
        self._create_product()
        codes = [self._expect(self._intent("同一买家", "13800000000"), 201).json()["passcode"] for _ in range(3)]
        self.assertEqual(len(set(codes)), 3)
        positions = [self._expect(self.buyer.request("POST", "/api/intents/query",
                     json_body={"passcode": code}), 200).json()["position"] for code in codes]
        self.assertEqual(positions, [1, 2, 3])

    @test_case("AT-013", "意向查询、修改及撤销")
    def test_013_intent_lifecycle(self):
        self._create_product()
        first, second = self._codes(2)
        self._expect(self.buyer.request("PUT", "/api/intents",
                    json_body={"passcode": second, "name": "新姓名", "phone": "13900000000"}), 200)
        intents = self._expect(self._admin("intents", method="GET"), 200).json()["intents"]
        second_row = next(row for row in intents if row["passcode"] == second)
        self.assertEqual((second_row["name"], second_row["phone"], second_row["position"]),
                         ("新姓名", "13900000000", 2))
        self._expect(self.buyer.request("POST", "/api/intents/cancel", json_body={"passcode": first}), 200)
        self._expect(self.buyer.request("POST", "/api/intents/query", json_body={"passcode": first}), 403)
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": second}), 200).json()["position"], 1)

    @test_case("AT-014", "商品冻结和解冻保留意向队列")
    def test_014_freeze(self):
        self._create_product()
        code = self._expect(self._intent(), 201).json()["passcode"]
        self._expect(self._admin("freeze"), 200)
        self._expect(self._intent("第二人", "13900000000"), 409)
        self._expect(self._admin("freeze"), 409)
        self._expect(self._admin("unfreeze"), 200)
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": code}), 200).json()["position"], 1)
        self._expect(self._intent("第二人", "13900000000"), 201)

    @test_case("AT-015", "交易成交后口令失效并写入历史")
    def test_015_trade_success(self):
        self._create_product()
        codes = self._codes(2)
        self._expect(self._admin("trade/start"), 200)
        self._expect(self._admin("trade/success"), 200)
        self.assertIsNone(self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"])
        for code in codes:
            self._expect(self.buyer.request("POST", "/api/intents/query", json_body={"passcode": code}), 403)
        history = self._expect(self._admin("history", method="GET"), 200).json()["products"]
        self.assertEqual(len(history), 1)
        self.assertEqual(history[0]["result"], "success")
        self.assertEqual([row["outcome"] for row in history[0]["intents"]], ["success", "unsold"])

    @test_case("AT-016", "交易失败后重新排队并递补")
    def test_016_trade_requeue(self):
        self._create_product()
        first, second = self._codes(2)
        self._expect(self._admin("trade/start"), 200)
        result = self._expect(self._admin("trade/fail", {"disposition": "requeue"}), 200).json()
        new_code = result["newPasscode"]
        self.assertNotEqual(first, new_code)
        self._expect(self.buyer.request("POST", "/api/intents/query", json_body={"passcode": first}), 403)
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": second}), 200).json()["stage"], "trading")
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": new_code}), 200).json()["position"], 1)

    @test_case("AT-017", "交易中买家撤销后由卖家确认递补")
    def test_017_cancel_trade_advance(self):
        self._create_product()
        first, second = self._codes(2)
        self._expect(self._admin("trade/start"), 200)
        self._expect(self.buyer.request("POST", "/api/intents/cancel", json_body={"passcode": first}), 200)
        self._expect(self._admin("trade/success"), 409)
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": second}), 200).json()["stage"], "queued")
        self._expect(self._admin("trade/advance"), 200)
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": second}), 200).json()["stage"], "trading")

    @test_case("AT-018", "SQLite数据和图片在服务重启后保留")
    def test_018_restart(self):
        product = self._create_product()
        code = self._expect(self._intent(), 201).json()["passcode"]
        image_path = product["image"]
        self._stop_app()
        self._start_app()
        self._expect(self.buyer.request("GET", "/api/product"), 200)
        persisted = self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"]
        self.assertEqual(persisted["id"], product["id"])
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": code}), 200).json()["position"], 1)
        self._expect(self.seller.request("GET", "/api/admin/product"), 401)
        self._expect(self._login(), 200)
        self._expect(self.buyer.request("GET", image_path), 200)

    @test_case("AT-019", "旧data.json首次启动迁移")
    def test_019_migration(self):
        product = self._expect(self.buyer.request("GET", "/api/product"), 200).json()["product"]
        self.assertEqual((product["id"], product["name"], product["priceCents"]), (41, "迁移商品", 1234))
        self._expect(self._login(), 200)
        intents = self._expect(self._admin("intents", method="GET"), 200).json()["intents"]
        self.assertEqual([(item["id"], item["passcode"], item["position"]) for item in intents],
                         [(73, "LEGACY42", 1)])
        self.assertEqual(self._expect(self.buyer.request("POST", "/api/intents/query",
                         json_body={"passcode": "LEGACY42"}), 200).json()["stage"], "queued")
        self.assertTrue((self.sandbox / "legacy.json").exists(), "迁移不应自行删除旧文件")

    @test_case("AT-020", "20个并发买家提交独立意向")
    def test_020_concurrent_intents(self):
        self._create_product()
        def submit(i):
            return Client(self.base_url).request("POST", "/api/intents",
                       json_body={"name": f"买家{i}", "phone": f"138{i:08d}"}, timeout=30)
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            responses = list(pool.map(submit, range(20)))
        self.assertEqual([response.status for response in responses], [201] * 20)
        codes = [response.json()["passcode"] for response in responses]
        self.assertEqual(len(set(codes)), 20)
        rows = self._expect(self._admin("intents", method="GET"), 200).json()["intents"]
        positions = sorted(row["position"] for row in rows)
        self.assertEqual(positions, list(range(1, 21)))
        self.assertEqual(len({row["id"] for row in rows}), 20)

    @test_case("AT-021", "两个卖家同时发布时只创建一件商品")
    def test_021_concurrent_publish(self):
        self._expect(self._login(), 200)
        second_seller = Client(self.base_url, keep_cookies=True)
        self._expect(self._login(second_seller), 200)
        def publish(client):
            return self._publish(client=client)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(publish, (self.seller, second_seller)))
        self.assertEqual(sorted(response.status for response in responses), [201, 409])
        self.assertEqual(len(list(self.upload_dir.iterdir())), 1)

    @test_case("AT-022", "并发开始交易只允许一个请求成功")
    def test_022_concurrent_start(self):
        self._create_product()
        self._codes(2)
        second_seller = Client(self.base_url, keep_cookies=True)
        self._expect(self._login(second_seller), 200)
        def start(client):
            return client.request("POST", "/api/admin/trade/start", json_body={})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(start, (self.seller, second_seller)))
        self.assertEqual(sorted(response.status for response in responses), [200, 409])
        rows = self._expect(self._admin("intents", method="GET"), 200).json()["intents"]
        self.assertEqual(sum(row["stage"] == "trading" for row in rows), 1)

    @test_case("AT-023", "畸形及null JSON输入返回客户端错误")
    def test_023_malformed_json(self):
        headers = {"Content-Type": "application/json"}
        for body in (b"{", b"[]", b"null", b"\"text\""):
            request = urllib.request.Request(self.base_url + "/api/intents", data=body,
                                             headers=headers, method="POST")
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    status, content_type, data = response.status, response.headers.get("Content-Type", ""), response.read()
            except urllib.error.HTTPError as error:
                status, content_type, data = error.code, error.headers.get("Content-Type", ""), error.read()
            self.assertEqual(status, 400, f"body={body!r}: HTTP {status}, {data[:400]!r}")

    @test_case("AT-024", "敏感静态路径和上传目录越界访问被拒绝")
    def test_024_path_traversal(self):
        for path in ("/app.py", "/data.json", "/src/main/java/com/coursewise/shopping/ShoppingService.java",
                     "/uploads/%2e%2e%2fdata%2fshop.db"):
            response = self.buyer.request("GET", path)
            self.assertGreaterEqual(response.status, 400, f"{path} unexpectedly returned HTTP {response.status}")


class RecordingResult(unittest.TextTestResult):
    def startTest(self, test):
        self._case_started = time.monotonic()
        super().startTest(test)

    def _record(self, test, status: str, detail: str = ""):
        method = getattr(test, test._testMethodName)
        self.testsRun
        JavaShoppingTests.results.append({
            "id": getattr(method, "case_id", test._testMethodName),
            "title": getattr(method, "case_title", test._testMethodName),
            "status": status,
            "duration_seconds": round(time.monotonic() - self._case_started, 3),
            "detail": detail[-3000:],
        })

    def addSuccess(self, test):
        super().addSuccess(test)
        self._record(test, "passed")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._record(test, "failed", self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self._record(test, "error", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._record(test, "skipped", reason)


class RecordingRunner(unittest.TextTestRunner):
    resultclass = RecordingResult


def source_manifest(project: Path) -> dict[str, str]:
    tracked = subprocess.run(["git", "-C", str(project), "ls-files", "-z"],
                             capture_output=True, check=False)
    paths = [Path(p.decode("utf-8", errors="replace")) for p in tracked.stdout.split(b"\0") if p]
    if not paths:
        paths = [p.relative_to(project) for p in project.rglob("*")
                 if p.is_file() and not any(part in {".git", "target", "data", "uploads"} for part in p.parts)]
    result = {}
    for rel in paths:
        file = project / rel
        if file.is_file():
            result[rel.as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="java_rebuild_v4接口黑盒自动化回归")
    parser.add_argument("--project", required=True, help="java_rebuild_v4项目目录")
    parser.add_argument("--report-dir", required=True, help="项目外的测试结果目录")
    parser.add_argument("--case", help="只执行一个用例，例如AT-008")
    args = parser.parse_args()
    global CLI
    CLI = args
    project = Path(args.project).resolve()
    report_dir = Path(args.report_dir).resolve()
    if not (project / "pom.xml").is_file() or not (project / "src/main/java").is_dir():
        parser.error("--project应指向包含pom.xml和src/main/java的Java项目目录")
    if project == report_dir or project in report_dir.parents:
        parser.error("--report-dir必须放在项目目录外")
    if args.case and args.case not in CASES:
        parser.error("未知用例编号；可用编号：" + ", ".join(CASES))
    report_dir.mkdir(parents=True, exist_ok=True)
    before = source_manifest(project)
    JavaShoppingTests.results = []
    started = time.monotonic()
    if args.case:
        suite = unittest.defaultTestLoader.loadTestsFromName(CASES[args.case], JavaShoppingTests)
    else:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(JavaShoppingTests)
    runner = RecordingRunner(verbosity=2, stream=sys.stdout)
    result = runner.run(suite)
    after = source_manifest(project)
    changed = sorted(path for path in set(before) | set(after) if before.get(path) != after.get(path))
    summary = {
        "project": "se-course-project",
        "branch": "java_rebuild_v4",
        "commit": "491328c5495138439cde67a4a0f8ca20b806dd3d",
        "test_count": result.testsRun,
        "passed": len(result.successes) if hasattr(result, "successes") else result.testsRun - len(result.failures) - len(result.errors) - len(result.skipped),
        "failed": len(result.failures), "errors": len(result.errors), "skipped": len(result.skipped),
        "duration_seconds": round(time.monotonic() - started, 3),
        "changed_source_paths": changed,
        "results": JavaShoppingTests.results,
    }
    (report_dir / "results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (report_dir / "project_manifest.json").write_text(json.dumps({
        "before": before, "after": after, "changed_paths": changed,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    (report_dir / "execution.log").write_text(
        f"Ran {result.testsRun} tests in {summary['duration_seconds']:.3f}s\n"
        f"Passed: {summary['passed']}; failed: {summary['failed']}; errors: {summary['errors']}; skipped: {summary['skipped']}\n"
        f"Changed source paths: {len(changed)}\n", encoding="utf-8")
    return 0 if result.wasSuccessful() and not changed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"测试脚本执行失败：{exc}", file=sys.stderr)
        sys.exit(2)
