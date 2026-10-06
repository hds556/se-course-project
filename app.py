"""Small Flask server for the single-product shopping system.

The JSON schema, API routes, and response shapes are compatible with server.js.
Set SHOP_DATA_FILE and SHOP_UPLOAD_DIR to use isolated storage for tests.
"""

from __future__ import annotations

import copy
import hashlib
import hmac
import io
import json
import os
import re
import secrets
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory
from PIL import Image, UnidentifiedImageError
from werkzeug.exceptions import RequestEntityTooLarge


ROOT = Path(__file__).resolve().parent
COOKIE_NAME = "seller_session"
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PRICE_PATTERN = re.compile(r"^(?:0|[1-9]\d*)(?:\.\d{1,2})?$")
# Prices are JSON numbers consumed by JavaScript; cents must remain exact.
MAX_PRICE_CENTS = (1 << 53) - 1
MAX_PRICE_TEXT_LENGTH = len(str(MAX_PRICE_CENTS // 100)) + 3
MAX_IMAGE_BYTES = 5 * 1024 * 1024


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def hash_password(password: str) -> str:
    # Node's crypto.scryptSync receives the hexadecimal salt as a UTF-8 string.
    salt = secrets.token_hex(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt.encode("utf-8"), n=16384, r=8, p=1, dklen=64)
    return f"scrypt:{salt}:{digest.hex()}"


def verify_password(password: object, encoded: object) -> bool:
    if not isinstance(password, str) or not isinstance(encoded, str):
        return False
    parts = encoded.split(":")
    if len(parts) != 3 or parts[0] != "scrypt":
        return False
    try:
        salt, expected_hex = parts[1], parts[2]
        expected = bytes.fromhex(expected_hex)
        if len(expected) != 64:
            return False
        actual = hashlib.scrypt(password.encode("utf-8"), salt=salt.encode("utf-8"), n=16384, r=8, p=1, dklen=64)
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(actual, expected)


def create_initial_db() -> dict:
    return {
        "seller": {
            "username": "admin",
            "passwordHash": hash_password(os.environ.get("DEFAULT_SELLER_PASSWORD", "admin1234")),
            "passwordChangedAt": None,
        },
        "products": [],
        "intents": [],
        "nextProductId": 1,
        "nextIntentId": 1,
    }


def save_db(data_file: Path, state: dict) -> None:
    """Replace the whole JSON file atomically, after flushing the temporary file."""
    data_file.parent.mkdir(parents=True, exist_ok=True)
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=data_file.parent,
            prefix=f".{data_file.name}.", suffix=".tmp", delete=False,
        ) as output:
            temp_name = output.name
            json.dump(state, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, data_file)
    finally:
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass


def load_db(data_file: Path) -> dict:
    if not data_file.exists():
        state = create_initial_db()
        save_db(data_file, state)
        return state
    with data_file.open("r", encoding="utf-8") as source:
        return json.load(source)


def current_product(state: dict) -> dict | None:
    return next((item for item in state["products"] if item["status"] != "sold"), None)


def is_buyable(product: dict | None) -> bool:
    return product is not None and product["status"] in ("active", "resumed")


def current_queue(state: dict, product_id: int) -> list[dict]:
    return sorted(
        (item for item in state["intents"] if item["productId"] == product_id and item["stage"] == "queued"),
        key=lambda item: (item["submittedAt"], item["id"]),
    )


def queue_position(state: dict, intent: dict) -> int:
    return next((index for index, item in enumerate(current_queue(state, intent["productId"]), 1)
                 if item["id"] == intent["id"]), 0)


def new_passcode(state: dict) -> str:
    existing = {intent["passcode"] for intent in state["intents"]}
    while True:
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
        if code not in existing:
            return code


def valid_person(name: object, phone: object) -> bool:
    return isinstance(name, str) and bool(name.strip()) and isinstance(phone, str) and bool(phone.strip())


def price_cents(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (str, int, float)):
        return None
    try:
        text = str(value).strip()
    except ValueError:
        return None
    if len(text) > MAX_PRICE_TEXT_LENGTH or not PRICE_PATTERN.fullmatch(text):
        return None
    whole, separator, fraction = text.partition(".")
    cents = int(whole) * 100 + (int(fraction.ljust(2, "0")) if separator else 0)
    return cents if 0 < cents <= MAX_PRICE_CENTS else None


def active_intent_by_code(state: dict, passcode: object) -> tuple[dict, dict] | None:
    code = str(passcode or "").strip().upper()
    intent = next((item for item in state["intents"] if item["passcode"] == code), None)
    product = next((item for item in state["products"] if intent and item["id"] == intent["productId"]), None)
    if intent is None or product is None or intent["stage"] == "closed" or product["status"] == "sold":
        return None
    return intent, product


def start_next_trade(state: dict, product: dict) -> dict | None:
    queue = current_queue(state, product["id"])
    if not queue:
        product["status"] = "resumed"
        product["tradingIntentId"] = None
        return None
    next_intent = queue[0]
    product["status"] = "frozen"
    product["frozenAt"] = utc_now()
    product["tradingIntentId"] = next_intent["id"]
    next_intent["stage"] = "trading"
    return next_intent


def transition(state: dict, product: dict, event: str, payload: dict | None = None) -> dict | None:
    """Apply a validated product/intent state transition."""
    payload = payload or {}
    if event == "FREEZE":
        product["status"] = "frozen"
        product["tradingIntentId"] = None
        product["frozenAt"] = utc_now()
        return None
    if event == "UNFREEZE":
        product["status"] = "active"
        return None
    if event == "START_TRADE":
        return start_next_trade(state, product)
    if event == "MARK_SUCCESS":
        buyer = payload["intent"]
        buyer["stage"] = "closed"
        buyer["outcome"] = "success"
        for intent in state["intents"]:
            if intent["productId"] == product["id"] and intent["stage"] != "closed":
                intent["stage"] = "closed"
                intent["outcome"] = "unsold"
        product["status"] = "sold"
        product["result"] = "success"
        product["resultAt"] = utc_now()
        product["tradingIntentId"] = None
        return None
    if event == "MARK_FAIL":
        buyer = payload["intent"]
        disposition = payload["disposition"]
        buyer["stage"] = "closed"
        if disposition == "requeue":
            buyer["outcome"] = "requeued"
            replacement = {
                "id": state["nextIntentId"], "productId": product["id"],
                "name": buyer["name"], "phone": buyer["phone"],
                "passcode": new_passcode(state), "submittedAt": utc_now(),
                "stage": "queued", "outcome": None,
            }
            state["nextIntentId"] += 1
            state["intents"].append(replacement)
            payload["newPasscode"] = replacement["passcode"]
        else:
            buyer["outcome"] = "voided"
        product["resultAt"] = utc_now()
        product["tradingIntentId"] = None
        return start_next_trade(state, product)
    if event == "ADVANCE_CANCELLED":
        product["tradingIntentId"] = None
        return start_next_trade(state, product)
    raise ValueError(f"Unsupported transition event: {event}")


def json_body() -> dict:
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else {}


def api_error(status: int, message: str):
    return jsonify({"error": message}), status


def checked_image(file) -> tuple[bytes, str] | None:
    if file is None or file.mimetype not in ("image/jpeg", "image/png"):
        return None
    content = file.stream.read(MAX_IMAGE_BYTES + 1)
    if not content or len(content) > MAX_IMAGE_BYTES:
        return None
    try:
        with Image.open(io.BytesIO(content)) as image:
            image_format = image.format
            image.verify()
        if image_format not in ("JPEG", "PNG"):
            return None
        with Image.open(io.BytesIO(content)) as image:
            image.load()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        return None
    if (image_format == "JPEG" and file.mimetype != "image/jpeg") or (image_format == "PNG" and file.mimetype != "image/png"):
        return None
    return content, ".jpg" if image_format == "JPEG" else ".png"


def create_app(data_file: str | Path | None = None, upload_dir: str | Path | None = None) -> Flask:
    """Build an app with independent storage and session state."""
    data_path = Path(data_file or os.environ.get("SHOP_DATA_FILE") or ROOT / "data.json").resolve()
    uploads_path = Path(upload_dir or os.environ.get("SHOP_UPLOAD_DIR") or ROOT / "uploads").resolve()
    uploads_path.mkdir(parents=True, exist_ok=True)
    public_path = ROOT / "public"
    app = Flask(__name__, static_folder=None)
    # Multipart framing and text fields are in addition to the 5 MiB image limit.
    app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024
    lock = threading.RLock()
    sessions: set[str] = set()
    db = load_db(data_path)

    def commit(draft: dict) -> None:
        nonlocal db
        save_db(data_path, draft)
        db = draft

    def authenticated() -> bool:
        return request.cookies.get(COOKIE_NAME) in sessions

    @app.before_request
    def guard_admin_api():
        if request.path.startswith("/api/admin/") or request.path == "/api/auth/password" or request.path == "/api/products":
            with lock:
                if not authenticated():
                    return api_error(401, "未登录")
        return None

    @app.errorhandler(RequestEntityTooLarge)
    def upload_too_large(_error):
        return api_error(400, "图片必须为 JPG/PNG 且不超过 5MB")

    @app.get("/")
    def index_page():
        return send_from_directory(public_path, "index.html")

    @app.get("/uploads/<path:filename>")
    def uploaded_file(filename):
        return send_from_directory(uploads_path, filename)

    @app.get("/<path:filename>")
    def public_file(filename):
        return send_from_directory(public_path, filename)

    @app.get("/api/product")
    def get_product():
        with lock:
            product = current_product(db)
            if product is None:
                return jsonify({"product": None})
            return jsonify({"product": {
                "id": product["id"], "name": product["name"], "description": product["description"],
                "image": product["image"], "priceCents": product["priceCents"],
                "status": product["status"], "buyable": is_buyable(product),
            }})

    @app.post("/api/intents")
    def create_intent():
        body = json_body()
        with lock:
            product = current_product(db)
            if not is_buyable(product):
                return api_error(409, "商品当前不接受购买意向")
            if not valid_person(body.get("name"), body.get("phone")):
                return api_error(400, "姓名和联系电话均为必填")
            draft = copy.deepcopy(db)
            record = {
                "id": draft["nextIntentId"], "productId": product["id"],
                "name": body["name"].strip(), "phone": body["phone"].strip(),
                "passcode": new_passcode(draft), "submittedAt": utc_now(),
                "stage": "queued", "outcome": None,
            }
            draft["nextIntentId"] += 1
            draft["intents"].append(record)
            commit(draft)
            return jsonify({"passcode": record["passcode"]}), 201

    @app.post("/api/intents/query")
    def query_intent():
        with lock:
            result = active_intent_by_code(db, json_body().get("passcode"))
            if result is None:
                return api_error(403, "口令码无效或已失效")
            intent, _ = result
            return jsonify({"stage": intent["stage"], "position": queue_position(db, intent) if intent["stage"] == "queued" else None})

    @app.put("/api/intents")
    def update_intent():
        body = json_body()
        with lock:
            result = active_intent_by_code(db, body.get("passcode"))
            if result is None:
                return api_error(403, "口令码无效或已失效")
            if not valid_person(body.get("name"), body.get("phone")):
                return api_error(400, "姓名和联系电话均为必填")
            draft = copy.deepcopy(db)
            intent = next(item for item in draft["intents"] if item["id"] == result[0]["id"])
            intent["name"] = body["name"].strip()
            intent["phone"] = body["phone"].strip()
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/intents/cancel")
    def cancel_intent():
        with lock:
            result = active_intent_by_code(db, json_body().get("passcode"))
            if result is None:
                return api_error(403, "口令码无效或已失效")
            draft = copy.deepcopy(db)
            intent = next(item for item in draft["intents"] if item["id"] == result[0]["id"])
            intent["stage"] = "closed"
            intent["outcome"] = "cancelled"
            # The product stays frozen. The seller explicitly advances this cancelled trade.
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/auth/login")
    def login():
        body = json_body()
        with lock:
            if body.get("username") != db["seller"]["username"] or not verify_password(body.get("password"), db["seller"]["passwordHash"]):
                return api_error(401, "用户名或密码错误")
            token = secrets.token_hex(32)
            sessions.add(token)
            response = jsonify({"ok": True})
            response.set_cookie(COOKIE_NAME, token, httponly=True, samesite="Strict", path="/")
            return response

    @app.post("/api/auth/password")
    def change_password():
        body = json_body()
        with lock:
            if not verify_password(body.get("oldPassword"), db["seller"]["passwordHash"]):
                return api_error(400, "原密码错误")
            new_password = body.get("newPassword")
            if not isinstance(new_password, str) or len(new_password) < 8 or re.search(r"[A-Za-z]", new_password) is None or re.search(r"\d", new_password) is None:
                return api_error(400, "新密码至少 8 位且必须同时包含字母和数字")
            draft = copy.deepcopy(db)
            draft["seller"]["passwordHash"] = hash_password(new_password)
            draft["seller"]["passwordChangedAt"] = utc_now()
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/products")
    def create_product():
        with lock:
            if current_product(db) is not None:
                return api_error(409, "当前已有未售出商品")
            files = request.files.getlist("image")
            if len(files) != 1:
                return api_error(400, "必须上传 JPG 或 PNG 图片")
            validated = checked_image(files[0])
            if validated is None:
                return api_error(400, "图片必须为 JPG/PNG 且不超过 5MB")
            name, description = request.form.get("name"), request.form.get("description")
            cents = price_cents(request.form.get("price"))
            if not isinstance(name, str) or not name.strip() or not isinstance(description, str) or not description.strip() or cents is None:
                return api_error(400, "商品名称、描述和有效价格均为必填")
            content, extension = validated
            filename = f"p-{int(datetime.now(timezone.utc).timestamp() * 1000)}-{secrets.token_hex(6)}{extension}"
            image_path = uploads_path / filename
            draft = copy.deepcopy(db)
            record = {
                "id": draft["nextProductId"], "name": name.strip(), "description": description.strip(),
                "image": f"/uploads/{filename}", "priceCents": cents, "status": "active",
                "tradingIntentId": None, "createdAt": utc_now(), "frozenAt": None,
                "result": None, "resultAt": None,
            }
            draft["nextProductId"] += 1
            draft["products"].append(record)
            try:
                with image_path.open("xb") as output:
                    output.write(content)
                    output.flush()
                    os.fsync(output.fileno())
                commit(draft)
            except Exception:
                image_path.unlink(missing_ok=True)
                raise
            return jsonify({"id": record["id"]}), 201

    @app.get("/api/admin/product")
    def admin_product():
        with lock:
            product = current_product(db)
            if product is None:
                return jsonify({"product": None, "tradingIntent": None})
            trading = next((intent for intent in db["intents"] if intent["id"] == product["tradingIntentId"]), None)
            return jsonify({"product": product, "tradingIntent": trading})

    @app.get("/api/admin/intents")
    def admin_intents():
        with lock:
            product = current_product(db)
            if product is None:
                return jsonify({"intents": []})
            records = sorted(
                (intent for intent in db["intents"] if intent["productId"] == product["id"] and
                 (intent["stage"] != "closed" or
                  (intent["id"] == product["tradingIntentId"] and intent["outcome"] == "cancelled"))),
                key=lambda item: (item["submittedAt"], item["id"]),
            )
            return jsonify({"intents": [
                {**intent, "position": queue_position(db, intent) if intent["stage"] == "queued" else None}
                for intent in records
            ]})

    @app.post("/api/admin/freeze")
    def freeze():
        with lock:
            product = current_product(db)
            if not is_buyable(product):
                return api_error(409, "当前商品不能手动冻结")
            draft = copy.deepcopy(db)
            item = current_product(draft)
            transition(draft, item, "FREEZE")
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/admin/unfreeze")
    def unfreeze():
        with lock:
            product = current_product(db)
            if product is None or product["status"] != "frozen" or product["tradingIntentId"] is not None:
                return api_error(409, "当前商品不能手动解冻")
            draft = copy.deepcopy(db)
            transition(draft, current_product(draft), "UNFREEZE")
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/admin/trade/start")
    def start_trade():
        with lock:
            product = current_product(db)
            if not is_buyable(product):
                return api_error(409, "商品当前不能开始交易")
            if not current_queue(db, product["id"]):
                return api_error(409, "当前没有排队中的购买意向")
            draft = copy.deepcopy(db)
            trading = transition(draft, current_product(draft), "START_TRADE")
            commit(draft)
            return jsonify({"intentId": trading["id"], "name": trading["name"], "phone": trading["phone"]})

    @app.post("/api/admin/trade/success")
    def trade_success():
        with lock:
            product = current_product(db)
            trading = next((intent for intent in db["intents"] if product and intent["id"] == product["tradingIntentId"] and intent["stage"] == "trading"), None)
            if trading is None:
                return api_error(409, "当前没有交易中的意向")
            draft = copy.deepcopy(db)
            item = current_product(draft)
            buyer = next(intent for intent in draft["intents"] if intent["id"] == trading["id"])
            transition(draft, item, "MARK_SUCCESS", {"intent": buyer})
            commit(draft)
            return jsonify({"ok": True})

    @app.post("/api/admin/trade/fail")
    def trade_fail():
        body = json_body()
        with lock:
            product = current_product(db)
            trading = next((intent for intent in db["intents"] if product and intent["id"] == product["tradingIntentId"] and intent["stage"] == "trading"), None)
            if trading is None:
                return api_error(409, "当前没有交易中的意向")
            disposition = body.get("disposition")
            if disposition not in ("void", "requeue"):
                return api_error(400, "无效的失败处置方式")
            draft = copy.deepcopy(db)
            item = current_product(draft)
            buyer = next(intent for intent in draft["intents"] if intent["id"] == trading["id"])
            transition_payload = {"intent": buyer, "disposition": disposition}
            next_intent = transition(draft, item, "MARK_FAIL", transition_payload)
            commit(draft)
            return jsonify({"ok": True, "newPasscode": transition_payload.get("newPasscode"),
                            "nextIntentId": next_intent["id"] if next_intent else None})

    @app.post("/api/admin/trade/advance")
    def advance_cancelled_trade():
        """Let the seller settle a buyer-cancelled trade without changing its outcome."""
        with lock:
            product = current_product(db)
            cancelled = next((intent for intent in db["intents"] if product and intent["id"] == product["tradingIntentId"] and
                              intent["stage"] == "closed" and intent["outcome"] == "cancelled"), None)
            if product is None or product["status"] != "frozen" or cancelled is None:
                return api_error(409, "当前没有待递补的已撤销交易意向")
            draft = copy.deepcopy(db)
            item = current_product(draft)
            next_intent = transition(draft, item, "ADVANCE_CANCELLED")
            commit(draft)
            return jsonify({"ok": True, "nextIntentId": next_intent["id"] if next_intent else None})

    @app.get("/api/admin/history")
    def history():
        with lock:
            return jsonify({"products": [
                {**product, "intents": [intent for intent in db["intents"] if intent["productId"] == product["id"]]}
                for product in db["products"] if product["status"] == "sold"
            ]})

    return app


if __name__ == "__main__":
    app = create_app()
    port = int(os.environ.get("PORT", "3000"))
    print(f"在线购物系统已启动：http://localhost:{port}")
    print(f"卖家后台：http://localhost:{port}/admin.html")
    app.run(host=os.environ.get("HOST", "127.0.0.1"), port=port)
