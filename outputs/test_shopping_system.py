"""rebuild_v3测试脚本。Python 3.12，依赖采用项目requirements.txt。

完整回归：
python test_shopping_system.py --project 项目目录 --report-dir 结果目录 --include-existing
定点复测：在命令中用 --case AT-051 替代 --include-existing。

独立用例54项；追加修复项目内11项后共65项。结果目录须在项目外。
测试数据及图片使用临时目录。退出码：0通过，1失败，2参数错误。
"""
import argparse
import concurrent.futures
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
APP = None
CASES = []


def case(number, area, title, steps, expected):
    def decorate(fn):
        fn.case_id = f"AT-{number:03d}"
        CASES.append(dict(id=fn.case_id, area=area, title=title, steps=steps,
                          expected=expected, method=fn.__name__, priority="P1"))
        return fn
    return decorate


class ShoppingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shopping-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "data.json"
        self.uploads = self.root / "uploads"
        env = patch.dict(os.environ, {"DEFAULT_SELLER_PASSWORD": "admin1234"})
        env.start()
        self.addCleanup(env.stop)
        self.app = APP.create_app(data_file=self.data, upload_dir=self.uploads)
        self.app.testing = True
        self.seller = self.app.test_client()
        self.buyer = self.app.test_client()

    def status(self, response, expected):
        self.assertEqual(response.status_code, expected, response.get_data(as_text=True)[:400])
        return response.get_json()

    def db(self):
        return json.loads(self.data.read_text(encoding="utf-8"))

    def login(self, client=None, password="admin1234"):
        return (client or self.seller).post("/api/auth/login", json={"username": "admin", "password": password})

    def image(self, fmt="PNG"):
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGB", (3, 3), "#778899").save(buf, format=fmt)
        return buf.getvalue()

    def publish(self, price="12.50", name="杯子", description="测试商品", image=None, mime="image/png", filename="cup.png"):
        return self.seller.post("/api/products", data={
            "name": name, "description": description, "price": price,
            "image": (io.BytesIO(self.image() if image is None else image), filename, mime)
        }, content_type="multipart/form-data")

    def ready(self):
        self.status(self.login(), 200)
        return self.status(self.publish(), 201)["id"]

    def intent(self, name="买家", phone="13800000000", client=None):
        return (client or self.buyer).post("/api/intents", json={"name": name, "phone": phone})

    def code(self, name="买家", phone="13800000000"):
        return self.status(self.intent(name, phone), 201)["passcode"]

    def query(self, code):
        return self.buyer.post("/api/intents/query", json={"passcode": code})

    def action(self, action, body=None):
        return self.seller.post("/api/admin/" + action, json=body)

    def product(self):
        return self.status(self.seller.get("/api/admin/product"), 200)

    def restart(self):
        app = APP.create_app(data_file=self.data, upload_dir=self.uploads)
        app.testing = True
        return app

    @case(1, "初始化", "空库初始化", "创建临时应用；查询商品及提交意向", "商品为 null；提交返回409；初始ID均为1")
    def test_001_initial(self):
        self.assertEqual(self.status(self.buyer.get("/api/product"), 200), {"product": None})
        self.status(self.intent(), 409)
        self.assertEqual(self.db()["nextProductId"], 1)
        self.assertEqual(self.db()["nextIntentId"], 1)

    @case(2, "权限", "未登录保护全部管理接口", "匿名调用3个管理GET、6个管理POST、发布和改密", "全部返回401，数据文件不变")
    def test_002_auth_guards(self):
        before = self.data.read_bytes()
        for route in ("product", "intents", "history"):
            with self.subTest(route=route):
                self.status(self.buyer.get("/api/admin/" + route), 401)
        for route in ("freeze", "unfreeze", "trade/start", "trade/success", "trade/fail", "trade/advance"):
            with self.subTest(route=route):
                self.status(self.buyer.post("/api/admin/" + route), 401)
        for route in ("/api/products", "/api/auth/password"):
            self.status(self.buyer.post(route), 401)
        self.assertEqual(self.data.read_bytes(), before)

    @case(3, "登录", "错误账号及畸形登录请求", "提交错误账号、错误密码、null、数组、数字密码、空对象", "均401，错误文案一致")
    def test_003_bad_login(self):
        payloads = [{"username": "unknown", "password": "admin1234"},
                    {"username": "admin", "password": "wrong"}, None, [], {},
                    {"username": "admin", "password": 123}]
        errors = []
        for body in payloads:
            with self.subTest(body=body):
                errors.append(self.status(self.seller.post("/api/auth/login", json=body), 401))
        self.assertTrue(all(e == errors[0] for e in errors))

    @case(4, "登录", "会话属性与客户端隔离", "正确登录；检查Cookie；分别访问卖家和匿名客户端", "HttpOnly、SameSite=Strict、Path=/；卖家200，匿名401")
    def test_004_session(self):
        response = self.login()
        self.status(response, 200)
        cookie = response.headers["Set-Cookie"]
        for flag in ("HttpOnly", "SameSite=Strict", "Path=/"):
            self.assertIn(flag, cookie)
        self.status(self.seller.get("/api/admin/product"), 200)
        self.status(self.buyer.get("/api/admin/product"), 401)

    @case(5, "登录", "伪造会话被拒绝", "匿名客户端设置seller_session为伪造值", "访问后台401")
    def test_005_forged_cookie(self):
        self.buyer.set_cookie("seller_session", "forged")
        self.status(self.buyer.get("/api/admin/history"), 401)

    @case(6, "密码", "错误旧密码不生效", "登录后用错误旧密码改密", "400且数据字节不变")
    def test_006_old_password(self):
        self.login()
        before = self.data.read_bytes()
        self.status(self.seller.post("/api/auth/password", json={"oldPassword": "bad", "newPassword": "newpass123"}), 400)
        self.assertEqual(self.data.read_bytes(), before)

    @case(7, "密码", "新密码边界及类型", "新密码取7位、仅字母、仅数字、空、null、数字", "均400；旧密码仍能登录")
    def test_007_weak_password(self):
        self.login()
        for value in ("abc1234", "abcdefgh", "12345678", "", None, 12345678):
            with self.subTest(password=value):
                self.status(self.seller.post("/api/auth/password", json={"oldPassword": "admin1234", "newPassword": value}), 400)
        self.status(self.login(), 200)

    @case(8, "密码", "8位合格密码及重启持久化", "改为abcd1234；创建新应用；分别使用新旧密码登录", "改密200；新密码200旧密码401；保存哈希和改密时间，无明文")
    def test_008_change_password(self):
        self.login()
        self.status(self.seller.post("/api/auth/password", json={"oldPassword": "admin1234", "newPassword": "abcd1234"}), 200)
        self.assertNotIn("abcd1234", self.data.read_text(encoding="utf-8"))
        self.assertTrue(self.db()["seller"]["passwordChangedAt"])
        c = self.restart().test_client()
        self.status(self.login(c, "admin1234"), 401)
        self.status(self.login(c, "abcd1234"), 200)

    @case(9, "商品", "正常PNG发布及公开字段", "发布12.50元商品；公开查询和下载图片", "201；1250分；active且可购买；仅公开7个商品字段；图片字节一致")
    def test_009_publish_png(self):
        self.ready()
        p = self.status(self.buyer.get("/api/product"), 200)["product"]
        self.assertEqual(set(p), {"id", "name", "description", "image", "priceCents", "status", "buyable"})
        self.assertEqual(p["priceCents"], 1250)
        self.assertEqual(p["status"], "active")
        self.assertTrue(p["buyable"])
        response = self.buyer.get(p["image"])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, self.image())
        response.close()

    @case(10, "图片", "正常JPEG及客户端文件名隔离", "以../outside.jpg为文件名上传JPEG", "201；服务端生成.jpg名称；临时根目录无outside.jpg")
    def test_010_jpeg(self):
        self.login()
        self.status(self.publish(image=self.image("JPEG"), mime="image/jpeg", filename="../outside.jpg"), 201)
        self.assertTrue(self.product()["product"]["image"].endswith(".jpg"))
        self.assertFalse((self.root / "outside.jpg").exists())
        self.assertEqual(len(list(self.uploads.iterdir())), 1)

    @case(11, "商品", "未售出商品唯一性", "已有商品时在active和frozen状态再次发布", "均409；数据和上传文件数量不变")
    def test_011_unique_product(self):
        self.ready()
        for frozen in (False, True):
            if frozen:
                self.status(self.action("freeze"), 200)
            before = self.data.read_bytes()
            self.status(self.publish(), 409)
            self.assertEqual(self.data.read_bytes(), before)
        self.assertEqual(len(list(self.uploads.iterdir())), 1)

    @case(12, "价格", "合法价格等价类", "分别发布0.01、1、1.2、1.23、带空白12.50；每次成交后再发布", "均201；金额分别为1、100、120、123、1250分")
    def test_012_valid_prices(self):
        self.login()
        for price, cents in (("0.01", 1), ("1", 100), ("1.2", 120), ("1.23", 123), (" 12.50 ", 1250)):
            with self.subTest(price=price):
                self.status(self.publish(price=price), 201)
                self.assertEqual(self.product()["product"]["priceCents"], cents)
                self.code()
                self.status(self.action("trade/start"), 200)
                self.status(self.action("trade/success"), 200)

    @case(13, "价格", "非法价格等价类", "提交0、负数、3位小数、指数、NaN、Infinity、前导零、空、字母", "均400；无商品或上传残留；ID不递增")
    def test_013_invalid_prices(self):
        self.login()
        for price in ("0", "0.00", "-1", "1.234", "1e2", "NaN", "Infinity", "01", "", "abc"):
            with self.subTest(price=price):
                self.status(self.publish(price=price), 400)
        self.assertIsNone(self.product()["product"])
        self.assertEqual(list(self.uploads.iterdir()), [])
        self.assertEqual(self.db()["nextProductId"], 1)

    @case(14, "商品", "商品必填及空白处理", "名称或描述为空白；再提交前后带空格的有效字段", "空白400；合法201；字段去除前后空格")
    def test_014_product_fields(self):
        self.login()
        self.status(self.publish(name="   "), 400)
        self.status(self.publish(description="  "), 400)
        self.status(self.publish(name=" 杯子 ", description=" 说明 "), 201)
        self.assertEqual(self.product()["product"]["name"], "杯子")
        self.assertEqual(self.product()["product"]["description"], "说明")

    @case(15, "图片", "缺图及多图", "发布时不附图片或附两张图片", "400且无商品和图片残留")
    def test_015_image_count(self):
        self.login()
        base = {"name": "杯子", "description": "描述", "price": "1"}
        self.status(self.seller.post("/api/products", data=base), 400)
        self.status(self.seller.post("/api/products", data={**base, "image": [
            (io.BytesIO(self.image()), "a.png", "image/png"),
            (io.BytesIO(self.image()), "b.png", "image/png")]}), 400)
        self.assertEqual(list(self.uploads.iterdir()), [])
        self.assertIsNone(self.product()["product"])

    @case(16, "图片", "伪造、空图片及MIME不匹配", "上传GIF类型、文本伪PNG、空PNG、PNG声明JPEG、截断PNG", "均400且无写入")
    def test_016_bad_images(self):
        self.login()
        for data, mime in ((b"fake", "image/gif"), (b"fake", "image/png"), (b"", "image/png"),
                           (self.image(), "image/jpeg"), (self.image()[:24], "image/png")):
            with self.subTest(size=len(data), mime=mime):
                self.status(self.publish(image=data, mime=mime), 400)
        self.assertEqual(list(self.uploads.iterdir()), [])

    @case(17, "图片", "图片上限5MiB", "合法PNG补齐到5242880字节，再测试5242881字节", "超过上限400；恰好上限201并保存完整字节数")
    def test_017_image_limit(self):
        self.login()
        png = self.image()
        boundary = png + b"\x00" * (APP.MAX_IMAGE_BYTES - len(png))
        self.status(self.publish(image=boundary + b"x"), 400)
        self.assertEqual(list(self.uploads.iterdir()), [])
        self.status(self.publish(image=boundary), 201)
        self.assertEqual(next(self.uploads.iterdir()).stat().st_size, APP.MAX_IMAGE_BYTES)

    @case(18, "图片", "请求总大小上限", "上传超过10MiB的请求", "400 JSON错误；无图片残留")
    def test_018_request_limit(self):
        self.login()
        result = self.status(self.publish(image=b"x" * (10 * 1024 * 1024 + 1)), 400)
        self.assertIn("error", result)
        self.assertEqual(list(self.uploads.iterdir()), [])

    @case(19, "意向", "买家无需注册及口令格式", "匿名提交带空白姓名电话的意向", "201；仅返回8位口令；格式正确；姓名电话已trim；排队第1")
    def test_019_anonymous_intent(self):
        self.ready()
        r = self.status(self.intent(" 张三 ", " 13800000000 "), 201)
        self.assertEqual(set(r), {"passcode"})
        self.assertRegex(r["passcode"], r"^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$")
        self.assertEqual(self.status(self.query(r["passcode"]), 200), {"stage": "queued", "position": 1})
        record = self.db()["intents"][0]
        self.assertEqual((record["name"], record["phone"]), ("张三", "13800000000"))

    @case(20, "意向", "姓名电话必填及类型", "分别提交空白、null、数组、数字姓名或电话", "均400且无意向，ID保持1")
    def test_020_person_validation(self):
        self.ready()
        for name, phone in (("", "1"), (" ", "1"), (None, "1"), ([], "1"), (1, "1"),
                            ("甲", ""), ("甲", " "), ("甲", None), ("甲", []), ("甲", 1)):
            with self.subTest(name=name, phone=phone):
                self.status(self.intent(name, phone), 400)
        self.assertEqual(self.db()["intents"], [])
        self.assertEqual(self.db()["nextIntentId"], 1)

    @case(21, "输入", "畸形JSON及非对象请求", "意向提交非法JSON、数组、null、字符串；后台失败处置数组JSON", "400；无数据变化；不返回500")
    def test_021_malformed_json(self):
        self.ready()
        before = self.data.read_bytes()
        for raw in ("{", "[]", "null", '"text"'):
            with self.subTest(raw=raw):
                self.status(self.buyer.post("/api/intents", data=raw, content_type="application/json"), 400)
        self.assertEqual(self.data.read_bytes(), before)
        self.code()
        self.action("trade/start")
        self.status(self.seller.post("/api/admin/trade/fail", json=[]), 400)

    @case(22, "排队", "相同联系方式独立排队", "同一买家提交三次", "口令不同；ID唯一；位置1/2/3")
    def test_022_duplicates(self):
        self.ready()
        codes = [self.code() for _ in range(3)]
        self.assertEqual(len(set(codes)), 3)
        self.assertEqual([self.status(self.query(c), 200)["position"] for c in codes], [1, 2, 3])
        self.assertEqual(len({r["id"] for r in self.db()["intents"]}), 3)

    @case(23, "口令", "查询规范化和信息最小化", "以小写及前后空白查询有效口令", "200；仅stage和position，无姓名、电话或口令")
    def test_023_code_normalization(self):
        self.ready()
        code = self.code()
        self.assertEqual(self.status(self.query(" " + code.lower() + " "), 200), {"stage": "queued", "position": 1})

    @case(24, "口令", "无效口令不能查询修改撤销", "使用空、随机、null、数字、数组口令调用三个接口", "全部403；数据库不变")
    def test_024_invalid_code(self):
        self.ready()
        self.code()
        before = self.data.read_bytes()
        for code in ("", "INVALID0", None, 123, []):
            with self.subTest(code=code):
                self.status(self.query(code), 403)
                self.status(self.buyer.put("/api/intents", json={"passcode": code, "name": "甲", "phone": "1"}), 403)
                self.status(self.buyer.post("/api/intents/cancel", json={"passcode": code}), 403)
        self.assertEqual(self.data.read_bytes(), before)

    @case(25, "意向", "修改不影响排队及他人记录", "两个意向；修改第二人联系方式", "200；第二位置仍2；第一人和提交时间、口令不变")
    def test_025_update(self):
        self.ready()
        self.code("甲", "111")
        c = self.code("乙", "222")
        old = self.db()["intents"]
        self.status(self.buyer.put("/api/intents", json={"passcode": c, "name": " 新乙 ", "phone": " 333 "}), 200)
        new = self.db()["intents"]
        self.assertEqual(new[0], old[0])
        self.assertEqual((new[1]["name"], new[1]["phone"]), ("新乙", "333"))
        for field in ("submittedAt", "passcode", "id"):
            self.assertEqual(new[1][field], old[1][field])
        self.assertEqual(self.status(self.query(c), 200)["position"], 2)

    @case(26, "意向", "修改时必填检查", "以有效口令提交空白姓名或null电话", "400；数据不变")
    def test_026_update_validation(self):
        self.ready()
        c = self.code()
        before = self.data.read_bytes()
        for name, phone in ((" ", "1"), ("甲", None)):
            self.status(self.buyer.put("/api/intents", json={"passcode": c, "name": name, "phone": phone}), 400)
        self.assertEqual(self.data.read_bytes(), before)

    @case(27, "撤销", "排队撤销及重复操作", "两人排队；第一人撤销；再次查询修改撤销", "首次200；第二人成第1；旧口令后续操作403；商品仍active")
    def test_027_cancel_queued(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.status(self.buyer.post("/api/intents/cancel", json={"passcode": a}), 200)
        self.status(self.query(a), 403)
        self.status(self.buyer.put("/api/intents", json={"passcode": a, "name": "甲", "phone": "1"}), 403)
        self.status(self.buyer.post("/api/intents/cancel", json={"passcode": a}), 403)
        self.assertEqual(self.status(self.query(b), 200)["position"], 1)
        self.assertEqual(self.product()["product"]["status"], "active")

    @case(28, "冻结", "手动冻结解冻保留队列", "一人排队；冻结；提交新意向及再次冻结/开始交易；解冻", "冻结200；新提交及非法动作409；原队列保留；解冻恢复active")
    def test_028_freeze(self):
        self.ready()
        c = self.code()
        self.status(self.action("freeze"), 200)
        self.status(self.intent(), 409)
        self.status(self.action("freeze"), 409)
        self.status(self.action("trade/start"), 409)
        self.assertFalse(self.status(self.buyer.get("/api/product"), 200)["product"]["buyable"])
        self.assertEqual(self.status(self.query(c), 200)["position"], 1)
        self.status(self.action("unfreeze"), 200)
        self.assertEqual(self.product()["product"]["status"], "active")
        self.assertEqual(self.status(self.query(c), 200)["position"], 1)

    @case(29, "状态", "无商品时管理动作", "无商品调用冻结解冻、开始、成功、失败、递补", "全部409且数据库不变")
    def test_029_no_product_actions(self):
        self.login()
        before = self.data.read_bytes()
        for action in ("freeze", "unfreeze", "trade/start", "trade/success", "trade/fail", "trade/advance"):
            with self.subTest(action=action):
                self.status(self.action(action), 409)
        self.assertEqual(self.data.read_bytes(), before)

    @case(30, "状态", "无队列或无交易时非法动作", "有商品无意向；开始/成功/失败/递补/解冻", "409，商品状态和数据库不变")
    def test_030_empty_queue(self):
        self.ready()
        before = self.data.read_bytes()
        for action in ("trade/start", "trade/success", "trade/fail", "trade/advance", "unfreeze"):
            self.status(self.action(action), 409)
        self.assertEqual(self.data.read_bytes(), before)

    @case(31, "交易", "按队首开始及锁定状态", "三人排队；开始；重复开始、冻结解冻、新意向", "第一人trading；商品frozen；后两人位置1/2；非法操作409")
    def test_031_start_trade(self):
        self.ready()
        a, b, c = self.code("甲", "111"), self.code("乙"), self.code("丙")
        r = self.status(self.action("trade/start"), 200)
        self.assertEqual((r["name"], r["phone"]), ("甲", "111"))
        self.assertEqual(self.status(self.query(a), 200), {"stage": "trading", "position": None})
        self.assertEqual([self.status(self.query(x), 200)["position"] for x in (b, c)], [1, 2])
        self.assertEqual(self.product()["product"]["status"], "frozen")
        for action in ("trade/start", "freeze", "unfreeze"):
            self.status(self.action(action), 409)
        self.status(self.intent(), 409)

    @case(32, "交易", "交易中可更新联系信息", "开始交易后以口令更新买家姓名电话", "200；trading状态保留；后台显示新信息")
    def test_032_trading_update(self):
        self.ready()
        c = self.code()
        self.action("trade/start")
        self.status(self.buyer.put("/api/intents", json={"passcode": c, "name": "新名", "phone": "999"}), 200)
        self.assertEqual(self.product()["tradingIntent"]["name"], "新名")
        self.assertEqual(self.status(self.query(c), 200)["stage"], "trading")

    @case(33, "成交", "成交结案及完整历史", "两人排队并成交第一人；查询两人口令和历史；再发布", "两口令403；当前商品null；历史success/unsold；可发布下一件且ID递增")
    def test_033_success(self):
        pid = self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.action("trade/start")
        self.status(self.action("trade/success"), 200)
        for c in (a, b):
            self.status(self.query(c), 403)
        self.assertIsNone(self.status(self.buyer.get("/api/product"), 200)["product"])
        h = self.status(self.seller.get("/api/admin/history"), 200)["products"]
        self.assertEqual(len(h), 1)
        self.assertEqual(h[0]["status"], "sold")
        self.assertEqual(h[0]["result"], "success")
        self.assertTrue(h[0]["resultAt"])
        self.assertEqual([i["outcome"] for i in h[0]["intents"]], ["success", "unsold"])
        self.status(self.action("trade/success"), 409)
        self.assertEqual(self.status(self.publish(), 201)["id"], pid + 1)
        self.status(self.query(a), 403)

    @case(34, "失败", "作废自动递补", "两人排队；第一笔交易失败作废", "旧码403；下一人trading；nextIntentId正确；商品frozen")
    def test_034_void_advance(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.action("trade/start")
        r = self.status(self.action("trade/fail", {"disposition": "void"}), 200)
        self.assertIsNone(r["newPasscode"])
        self.assertEqual(r["nextIntentId"], 2)
        self.status(self.query(a), 403)
        self.assertEqual(self.status(self.query(b), 200)["stage"], "trading")
        self.assertEqual(self.db()["intents"][0]["outcome"], "voided")
        self.assertEqual(self.product()["product"]["status"], "frozen")

    @case(35, "失败", "最后一人作废恢复在售", "唯一买家开始交易后作废；新买家提交", "商品resumed且可购买；nextIntentId为null；新提交201")
    def test_035_void_empty(self):
        self.ready()
        self.code()
        self.action("trade/start")
        r = self.status(self.action("trade/fail", {"disposition": "void"}), 200)
        self.assertIsNone(r["nextIntentId"])
        p = self.status(self.buyer.get("/api/product"), 200)["product"]
        self.assertEqual(p["status"], "resumed")
        self.assertTrue(p["buyable"])
        self.status(self.intent(), 201)

    @case(36, "失败", "重新排队到队尾及旧码失效", "三人排队；第一人交易失败重新排队", "第二人trading，第三人第1，重排人第2；新码唯一，旧码403；保留历史")
    def test_036_requeue(self):
        self.ready()
        a, b, c = self.code("甲", "111"), self.code("乙"), self.code("丙")
        self.action("trade/start")
        r = self.status(self.action("trade/fail", {"disposition": "requeue"}), 200)
        d = r["newPasscode"]
        self.assertNotIn(d, (a, b, c))
        self.status(self.query(a), 403)
        self.assertEqual(self.status(self.query(b), 200)["stage"], "trading")
        self.assertEqual(self.status(self.query(c), 200)["position"], 1)
        self.assertEqual(self.status(self.query(d), 200)["position"], 2)
        rows = self.db()["intents"]
        self.assertEqual(rows[0]["outcome"], "requeued")
        self.assertEqual((rows[-1]["name"], rows[-1]["phone"]), ("甲", "111"))

    @case(37, "失败", "唯一买家重排时立即递补自身", "唯一买家失败重新排队", "新码trading；旧码403；商品frozen；返回新记录ID")
    def test_037_requeue_single(self):
        self.ready()
        old = self.code()
        self.action("trade/start")
        r = self.status(self.action("trade/fail", {"disposition": "requeue"}), 200)
        self.status(self.query(old), 403)
        self.assertEqual(self.status(self.query(r["newPasscode"]), 200)["stage"], "trading")
        self.assertEqual(r["nextIntentId"], 2)
        self.assertEqual(self.product()["product"]["status"], "frozen")

    @case(38, "失败", "失败处置非法参数", "交易中传入空、unknown、null、数组、对象处置", "全部400且数据库不变")
    def test_038_disposition(self):
        self.ready()
        self.code()
        self.action("trade/start")
        before = self.data.read_bytes()
        for d in ("", "unknown", None, [], {}):
            with self.subTest(disposition=d):
                self.status(self.action("trade/fail", {"disposition": d}), 400)
        self.assertEqual(self.data.read_bytes(), before)

    @case(39, "撤销", "交易中撤销须卖家确认", "两人排队；交易人撤销；尝试成功失败解冻；卖家递补", "撤销后冻结；下一人仍queued；非法动作409；递补后下一人trading")
    def test_039_cancel_trading(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.action("trade/start")
        self.status(self.buyer.post("/api/intents/cancel", json={"passcode": a}), 200)
        self.status(self.query(a), 403)
        p = self.product()
        self.assertEqual(p["product"]["status"], "frozen")
        self.assertEqual(p["tradingIntent"]["outcome"], "cancelled")
        rows = self.status(self.seller.get("/api/admin/intents"), 200)["intents"]
        self.assertIn(p["tradingIntent"]["id"], [i["id"] for i in rows])
        self.assertEqual(self.status(self.query(b), 200)["stage"], "queued")
        for action in ("trade/success", "trade/fail", "unfreeze", "trade/start"):
            self.status(self.action(action), 409)
        r = self.status(self.action("trade/advance"), 200)
        self.assertEqual(r["nextIntentId"], 2)
        self.assertEqual(self.status(self.query(b), 200)["stage"], "trading")
        self.assertEqual(self.db()["intents"][0]["outcome"], "cancelled")

    @case(40, "撤销", "撤销确认后空队列恢复", "唯一交易人撤销；卖家递补；重复递补", "首次200且nextIntentId为null；商品resumed；重复409；新意向201")
    def test_040_cancel_empty(self):
        self.ready()
        c = self.code()
        self.action("trade/start")
        self.buyer.post("/api/intents/cancel", json={"passcode": c})
        self.assertIsNone(self.status(self.action("trade/advance"), 200)["nextIntentId"])
        self.assertEqual(self.product()["product"]["status"], "resumed")
        self.status(self.action("trade/advance"), 409)
        self.status(self.intent(), 201)

    @case(41, "持久化", "冻结交易及图片重启保留", "商品有两人排队并开始交易；重新创建应用；读取状态图片", "交易人仍trading，第二人第1；图片存在；旧会话401，重新登录200")
    def test_041_restart(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.action("trade/start")
        old_cookie = self.seller.get_cookie(APP.COOKIE_NAME).value
        c = self.restart().test_client()
        c.set_cookie(APP.COOKIE_NAME, old_cookie)
        self.status(c.get("/api/admin/product"), 401)
        self.status(self.login(c), 200)
        self.assertEqual(self.status(c.get("/api/admin/product"), 200)["product"]["status"], "frozen")
        self.assertEqual(self.status(c.post("/api/intents/query", json={"passcode": a}), 200)["stage"], "trading")
        self.assertEqual(self.status(c.post("/api/intents/query", json={"passcode": b}), 200)["position"], 1)
        p = self.status(c.get("/api/product"), 200)["product"]
        response = c.get(p["image"])
        self.assertEqual(response.status_code, 200)
        response.close()

    @case(42, "持久化", "写入故障保持旧数据并清理新图片", "登录；模拟save_db发生磁盘写入失败；发布商品", "异常被测试捕获；磁盘及内存商品不变；无孤立图片；恢复后发布成功")
    def test_042_write_failure(self):
        self.login()
        before = self.data.read_bytes()
        with patch.object(APP, "save_db", side_effect=OSError("simulated disk full")):
            with self.assertRaises(OSError):
                self.publish()
        self.assertEqual(self.data.read_bytes(), before)
        self.assertIsNone(self.product()["product"])
        self.assertEqual(list(self.uploads.iterdir()), [])
        self.status(self.publish(), 201)

    @case(43, "并发", "同一实例20个并发意向", "用20个独立客户端并发提交；检查记录与队列", "全部201；20个唯一ID/口令；队列位置完整1至20；重启保留20条")
    def test_043_concurrent_intents(self):
        self.ready()
        def submit(i):
            with self.app.test_client() as c:
                response = c.post("/api/intents", json={"name": f"买家{i}", "phone": "13800000000"})
                return response.status_code, response.get_json()
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(submit, range(20)))
        self.assertEqual([r[0] for r in results], [201] * 20)
        codes = [r[1]["passcode"] for r in results]
        self.assertEqual(len(set(codes)), 20)
        self.assertEqual(len({i["id"] for i in self.db()["intents"]}), 20)
        positions = [self.status(self.query(c), 200)["position"] for c in codes]
        self.assertEqual(sorted(positions), list(range(1, 21)))
        c = self.restart().test_client()
        self.login(c)
        self.assertEqual(len(self.status(c.get("/api/admin/intents"), 200)["intents"]), 20)

    @case(44, "静态资源", "页面资源可访问及路径越界防护", "访问首页后台JS/CSS；尝试读取app.py、data.json和../文件", "合法资源200；敏感或越界路径404")
    def test_044_static_paths(self):
        for url in ("/", "/admin.html", "/app.js", "/admin.js", "/style.css"):
            with self.subTest(url=url):
                response = self.buyer.get(url)
                self.assertEqual(response.status_code, 200)
                self.assertTrue(response.data)
                response.close()
        for url in ("/app.py", "/data.json", "/../app.py", "/%2e%2e/app.py", "/uploads/../data.json"):
            with self.subTest(url=url):
                self.assertEqual(self.buyer.get(url).status_code, 404)

    @case(45, "历史", "失败撤销记录纳入成交历史并重启保留", "三人排队；甲重排、乙作废、丙撤销确认；甲最终成交；重启查历史", "历史含4条记录，结果依次requeued/voided/cancelled/success")
    def test_045_complete_history(self):
        self.ready()
        self.code("甲")
        self.code("乙")
        c = self.code("丙")
        self.action("trade/start")
        self.status(self.action("trade/fail", {"disposition": "requeue"}), 200)
        self.status(self.action("trade/fail", {"disposition": "void"}), 200)
        self.status(self.buyer.post("/api/intents/cancel", json={"passcode": c}), 200)
        self.status(self.action("trade/advance"), 200)
        self.status(self.action("trade/success"), 200)
        client = self.restart().test_client()
        self.login(client)
        h = self.status(client.get("/api/admin/history"), 200)["products"]
        self.assertEqual(len(h), 1)
        self.assertEqual([i["outcome"] for i in h[0]["intents"]], ["requeued", "voided", "cancelled", "success"])

    @case(46, "并发", "两卖家同时发布只成功一次", "两个已登录客户端同时发布合法图片", "状态码为201/409；数据库1个商品、1个图片、nextProductId为2")
    def test_046_concurrent_publish(self):
        clients = [self.app.test_client() for _ in range(2)]
        for c in clients:
            self.status(self.login(c), 200)
        import threading
        barrier = threading.Barrier(2)
        def publish(c):
            barrier.wait(timeout=10)
            return c.post("/api/products", data={"name": "杯子", "description": "说明", "price": "1",
                "image": (io.BytesIO(self.image()), "cup.png", "image/png")}).status_code
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(publish, clients))
        self.assertEqual(sorted(statuses), [201, 409])
        self.assertEqual(len(self.db()["products"]), 1)
        self.assertEqual(self.db()["nextProductId"], 2)
        self.assertEqual(len(list(self.uploads.iterdir())), 1)

    @case(47, "并发", "同时开始交易只成功一次", "两人排队；两个已登录客户端同时开始交易", "200/409；仅一人trading且为队首；第二人仍queued")
    def test_047_concurrent_start(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        clients = [self.app.test_client() for _ in range(2)]
        for c in clients:
            self.login(c)
        import threading
        barrier = threading.Barrier(2)
        def start(c):
            barrier.wait(timeout=10)
            return c.post("/api/admin/trade/start").status_code
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(start, clients))
        self.assertEqual(sorted(statuses), [200, 409])
        self.assertEqual(sum(i["stage"] == "trading" for i in self.db()["intents"]), 1)
        self.assertEqual(self.status(self.query(a), 200)["stage"], "trading")
        self.assertEqual(self.status(self.query(b), 200)["stage"], "queued")

    @case(48, "持久化", "意向写入失败不消耗ID或破坏队列", "已有意向；模拟save_db失败提交；恢复写入再提交", "异常捕获；数据库和队列不变；恢复后新记录ID=2、位置2")
    def test_048_intent_write_failure(self):
        self.ready()
        a = self.code("甲")
        before = self.data.read_bytes()
        with patch.object(APP, "save_db", side_effect=OSError("simulated disk full")):
            with self.assertRaises(OSError):
                self.intent("乙")
        self.assertEqual(self.data.read_bytes(), before)
        self.assertEqual(self.status(self.query(a), 200)["position"], 1)
        b = self.code("乙")
        self.assertEqual(self.db()["intents"][-1]["id"], 2)
        self.assertEqual(self.status(self.query(b), 200)["position"], 2)

    @case(49, "持久化", "待确认撤销重启后可继续处理", "交易人撤销但未确认；重启登录；查看并递补", "保留closed/cancelled与frozen；递补200且下一人trading")
    def test_049_cancel_restart(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.action("trade/start")
        self.buyer.post("/api/intents/cancel", json={"passcode": a})
        c = self.restart().test_client()
        self.login(c)
        p = self.status(c.get("/api/admin/product"), 200)
        self.assertEqual(p["product"]["status"], "frozen")
        self.assertEqual(p["tradingIntent"]["outcome"], "cancelled")
        self.status(c.post("/api/admin/trade/advance"), 200)
        self.assertEqual(self.status(c.post("/api/intents/query", json={"passcode": b}), 200)["stage"], "trading")

    @case(50, "配置", "自定义初始密码仅用于新库", "设置DEFAULT_SELLER_PASSWORD初始化独立库；改变环境变量后重启", "自定义密码可登录；默认密码不可登录；已有库密码不被覆盖")
    def test_050_initial_password_env(self):
        data, uploads = self.root / "configured.json", self.root / "configured-uploads"
        with patch.dict(os.environ, {"DEFAULT_SELLER_PASSWORD": "custom123"}):
            app = APP.create_app(data_file=data, upload_dir=uploads)
        self.status(self.login(app.test_client(), "custom123"), 200)
        self.status(self.login(app.test_client(), "admin1234"), 401)
        with patch.dict(os.environ, {"DEFAULT_SELLER_PASSWORD": "changed123"}):
            restarted = APP.create_app(data_file=data, upload_dir=uploads)
        self.status(self.login(restarted.test_client(), "custom123"), 200)
        self.status(self.login(restarted.test_client(), "changed123"), 401)

    @case(51, "健壮性", "超长价格应返回可处理错误", "登录；合法PNG配5000位数字价格发布", "按输入健壮性验收原则应400 JSON错误而非500；无数据或图片残留")
    def test_051_extreme_price(self):
        self.login()
        self.app.testing = False
        before = self.data.read_bytes()
        response = self.publish(price="9" * 5000)
        self.evidence = {"price_digits": 5000, "status_code": response.status_code,
                         "content_type": response.content_type,
                         "data_unchanged": self.data.read_bytes() == before,
                         "uploads_empty": list(self.uploads.iterdir()) == []}
        self.assertEqual(self.data.read_bytes(), before)
        self.assertEqual(list(self.uploads.iterdir()), [])
        body = self.status(response, 400)
        self.assertTrue(response.is_json)
        self.assertIn("error", body)

    @case(52, "冻结", "冻结期间已有买家仍可修改撤销", "两人排队；手动冻结；第二人改信息；第一人撤销；解冻", "修改撤销200；第二人位置1；商品仍frozen直到解冻；解冻后可提交")
    def test_052_manage_frozen(self):
        self.ready()
        a, b = self.code("甲"), self.code("乙")
        self.status(self.action("freeze"), 200)
        self.status(self.buyer.put("/api/intents", json={"passcode": b, "name": "乙新", "phone": "555"}), 200)
        self.status(self.buyer.post("/api/intents/cancel", json={"passcode": a}), 200)
        self.assertEqual(self.status(self.query(b), 200)["position"], 1)
        self.assertEqual(self.product()["product"]["status"], "frozen")
        self.status(self.action("unfreeze"), 200)
        self.status(self.intent(), 201)

    @case(53, "缺陷迭代", "金额上限精确保存并可重启读取", "登录；发布90071992547409.91元；查询与读取保存文件；重启再查询", "201；priceCents精确为9007199254740991；重启后值不变")
    def test_053_maximum_price(self):
        self.status(self.login(), 200)
        self.status(self.publish(price="90071992547409.91"), 201)
        expected = 9007199254740991
        self.assertEqual(self.status(self.buyer.get("/api/product"), 200)["product"]["priceCents"], expected)
        self.assertEqual(self.db()["products"][0]["priceCents"], expected)
        restarted = self.restart().test_client()
        self.assertEqual(self.status(restarted.get("/api/product"), 200)["product"]["priceCents"], expected)

    @case(54, "缺陷迭代", "超上限及超长价格拒绝后仍能发布", "登录；提交上限加1分、整数元超上限、18位数字及5000位数字；随后发布0.01元", "非法请求均400 JSON，数据字节和图片目录不变；随后201，商品ID为1、价格为1分")
    def test_054_price_rejection_recovery(self):
        self.status(self.login(), 200)
        before = self.data.read_bytes()
        for price in ("90071992547409.92", "90071992547410", "9" * 18, "9" * 5000):
            with self.subTest(length=len(price)):
                response = self.publish(price=price)
                self.status(response, 400)
                self.assertTrue(response.is_json)
                self.assertIn("error", response.get_json())
                self.assertEqual(self.data.read_bytes(), before)
                self.assertEqual(list(self.uploads.iterdir()), [])
                self.assertIsNone(self.product()["product"])
        self.assertEqual(self.status(self.publish(price="0.01"), 201)["id"], 1)
        self.assertEqual(self.product()["product"]["priceCents"], 1)


class RecordedResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.records = []
        self.failed_ids = set()
        self.details = {}

    def startTest(self, test):
        self.start_time = time.perf_counter()
        super().startTest(test)

    def addFailure(self, test, err):
        self.failed_ids.add(test.id())
        self.details[test.id()] = self._exc_info_to_string(err, test)
        super().addFailure(test, err)

    def addError(self, test, err):
        self.failed_ids.add(test.id())
        self.details[test.id()] = self._exc_info_to_string(err, test)
        super().addError(test, err)

    def addSubTest(self, test, subtest, err):
        if err:
            self.failed_ids.add(test.id())
            self.details[test.id()] = self.details.get(test.id(), "") + self._exc_info_to_string(err, subtest)
        super().addSubTest(test, subtest, err)

    def addSkip(self, test, reason):
        self.details[test.id()] = "SKIP: " + reason
        super().addSkip(test, reason)

    def stopTest(self, test):
        method = getattr(test, test._testMethodName)
        detail = self.details.get(test.id(), "")
        self.records.append(dict(case_id=getattr(method, "case_id", "UPSTREAM"), test=test.id(),
            status="FAIL" if test.id() in self.failed_ids else ("SKIP" if detail.startswith("SKIP:") else "PASS"),
            seconds=round(time.perf_counter() - self.start_time, 4), detail=detail,
            evidence=getattr(test, "evidence", {})))
        super().stopTest(test)


def manifest(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file() and ".git" not in p.relative_to(root).parts}


def main():
    global APP
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--report-dir", required=True, type=Path)
    parser.add_argument("--include-existing", action="store_true", help="Also run original tests/test_app.py read-only")
    parser.add_argument("--case", action="append", help="Run selected case IDs, e.g. --case AT-051")
    args = parser.parse_args()
    project = args.project.resolve()
    reports = args.report_dir.resolve()
    if reports == project or project in reports.parents:
        parser.error("Report directory must be outside the project")
    if not (project / "app.py").is_file():
        parser.error("Project must contain app.py")
    before = manifest(project)
    reports.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("app", project / "app.py")
    APP = importlib.util.module_from_spec(spec)
    sys.modules["app"] = APP
    spec.loader.exec_module(APP)
    if args.case:
        selected = [c for c in CASES if c["id"] in args.case]
        if {c["id"] for c in selected} != set(args.case):
            parser.error("Unknown case ID")
        suite = unittest.TestSuite(ShoppingTests(c["method"]) for c in selected)
    else:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ShoppingTests)
    if args.include_existing:
        upstream = project / "tests" / "test_app.py"
        if not upstream.is_file():
            parser.error("Original tests/test_app.py is missing")
        spec2 = importlib.util.spec_from_file_location("upstream_test_app", upstream)
        module = importlib.util.module_from_spec(spec2)
        spec2.loader.exec_module(module)
        suite.addTests(unittest.defaultTestLoader.loadTestsFromModule(module))
    with (reports / "execution.log").open("w", encoding="utf-8") as log:
        with contextlib.redirect_stderr(log):
            result = unittest.TextTestRunner(stream=log, verbosity=2, resultclass=RecordedResult).run(suite)
    after = manifest(project)
    integrity = before == after
    payload = dict(project=str(project), python=sys.version, integrity_unchanged=integrity,
                   changed_paths=sorted(k for k in before.keys() | after.keys() if before.get(k) != after.get(k)),
                   run=result.testsRun, failures=len(result.failures), errors=len(result.errors),
                   skipped=len(result.skipped), cases=CASES, results=result.records)
    (reports / "results.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    (reports / "project_manifest.json").write_text(json.dumps(dict(before=before, after=after), indent=2), encoding="utf-8")
    print(f"Tests: {result.testsRun}; failures: {len(result.failures)}; errors: {len(result.errors)}; skipped: {len(result.skipped)}")
    print(f"Project file integrity unchanged: {integrity}. Reports: {reports}")
    return 0 if result.wasSuccessful() and integrity else 1


if __name__ == "__main__":
    raise SystemExit(main())
