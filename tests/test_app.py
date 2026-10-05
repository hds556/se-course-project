"""End-to-end API scenarios against an isolated Flask data store."""

import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from app import create_app


def png_bytes():
    output = io.BytesIO()
    Image.new("RGB", (2, 2), "#c77454").save(output, format="PNG")
    return output.getvalue()


class ShoppingSystemTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data_file = self.root / "data.json"
        self.upload_dir = self.root / "uploads"
        password_patch = patch.dict(os.environ, {"DEFAULT_SELLER_PASSWORD": "admin1234"})
        password_patch.start()
        self.addCleanup(password_patch.stop)
        self.app = create_app(data_file=self.data_file, upload_dir=self.upload_dir)
        self.app.testing = True
        self.client = self.app.test_client()

    def login(self, password="admin1234", username="admin"):
        return self.client.post(
            "/api/auth/login", json={"username": username, "password": password}
        )

    def publish(self, *, image=None, filename="item.png", content_type="image/png", price="12.50"):
        if image is None:
            image = png_bytes()
        return self.client.post(
            "/api/products",
            data={
                "name": "手作杯子",
                "description": "一只温暖的杯子",
                "price": price,
                "image": (io.BytesIO(image), filename, content_type),
            },
            content_type="multipart/form-data",
        )

    def intent(self, name="买家", phone="13800000000"):
        return self.client.post("/api/intents", json={"name": name, "phone": phone})

    def admin_product(self):
        response = self.client.get("/api/admin/product")
        self.assertEqual(response.status_code, 200)
        return response.get_json()

    def admin_intents(self):
        response = self.client.get("/api/admin/intents")
        self.assertEqual(response.status_code, 200)
        return response.get_json()["intents"]

    def query(self, passcode):
        return self.client.post("/api/intents/query", json={"passcode": passcode})

    def test_auth_session_and_password_are_protected(self):
        for route in ("/api/admin/product", "/api/admin/intents", "/api/admin/history"):
            with self.subTest(route=route):
                self.assertEqual(self.client.get(route).status_code, 401)
        self.assertEqual(self.client.post("/api/products").status_code, 401)

        unknown_user = self.login(username="missing")
        wrong_password = self.login(password="wrong")
        self.assertEqual(unknown_user.status_code, 401)
        self.assertEqual(wrong_password.status_code, 401)
        self.assertEqual(unknown_user.get_json(), wrong_password.get_json())

        logged_in = self.login()
        self.assertEqual(logged_in.status_code, 200)
        self.assertIn("HttpOnly", logged_in.headers.get("Set-Cookie", ""))
        self.assertEqual(self.admin_product()["product"], None)

        weak = self.client.post(
            "/api/auth/password", json={"oldPassword": "admin1234", "newPassword": "12345678"}
        )
        self.assertEqual(weak.status_code, 400)
        changed = self.client.post(
            "/api/auth/password", json={"oldPassword": "admin1234", "newPassword": "newpass123"}
        )
        self.assertEqual(changed.status_code, 200)
        db = json.loads(self.data_file.read_text(encoding="utf-8"))
        self.assertNotIn("newpass123", self.data_file.read_text(encoding="utf-8"))
        self.assertTrue(db["seller"]["passwordChangedAt"])
        fresh_client = self.app.test_client()
        self.assertEqual(
            fresh_client.post(
                "/api/auth/login", json={"username": "admin", "password": "admin1234"}
            ).status_code,
            401,
        )
        self.assertEqual(
            fresh_client.post(
                "/api/auth/login", json={"username": "admin", "password": "newpass123"}
            ).status_code,
            200,
        )

    def test_duplicate_intents_are_independent_and_cancel_updates_positions(self):
        self.assertEqual(self.login().status_code, 200)
        self.assertEqual(self.publish().status_code, 201)
        first = self.intent(name="小王").get_json()["passcode"]
        second = self.intent(name="小王").get_json()["passcode"]
        self.assertNotEqual(first, second)
        self.assertEqual(len(first), 8)
        first_query = self.query(first).get_json()
        self.assertEqual(first_query["position"], 1)
        self.assertEqual(set(first_query), {"position", "stage"})
        self.assertEqual(self.query(second).get_json()["position"], 2)
        self.assertEqual(len(self.admin_intents()), 2)

        changed = self.client.put(
            "/api/intents", json={"passcode": second, "name": "新名字", "phone": "13900000000"}
        )
        self.assertEqual(changed.status_code, 200)
        self.assertEqual(self.query(second).get_json()["position"], 2)
        self.assertEqual(self.admin_intents()[1]["name"], "新名字")

        self.assertEqual(
            self.client.post("/api/intents/cancel", json={"passcode": first}).status_code,
            200,
        )
        self.assertEqual(self.query(second).get_json()["position"], 1)
        self.assertEqual(self.query(first).status_code, 403)
        self.assertEqual(self.query(first).get_json(), self.query("INVALID0").get_json())
        self.assertEqual(
            self.client.put(
                "/api/intents", json={"passcode": first, "name": "测试", "phone": "1"}
            ).status_code,
            403,
        )
        self.assertEqual(
            self.client.post("/api/intents/cancel", json={"passcode": first}).status_code,
            403,
        )
        self.assertEqual(self.admin_product()["product"]["status"], "active")

    def test_empty_queue_start_does_not_change_state_and_freeze_preserves_queue(self):
        self.login()
        self.publish()
        empty_start = self.client.post("/api/admin/trade/start")
        self.assertEqual(empty_start.status_code, 409)
        product = self.admin_product()["product"]
        self.assertEqual(product["status"], "active")
        self.assertIsNone(product["tradingIntentId"])

        code = self.intent().get_json()["passcode"]
        self.assertEqual(self.client.post("/api/admin/freeze").status_code, 200)
        self.assertEqual(self.admin_product()["product"]["status"], "frozen")
        self.assertEqual(self.intent().status_code, 409)
        self.assertEqual(self.query(code).get_json()["position"], 1)
        self.assertEqual(self.client.post("/api/admin/unfreeze").status_code, 200)
        self.assertEqual(self.admin_product()["product"]["status"], "active")
        self.assertEqual(self.query(code).get_json()["position"], 1)

    def test_success_closes_all_codes_and_preserves_complete_history(self):
        self.login()
        self.publish()
        first = self.intent("甲", "111").get_json()["passcode"]
        second = self.intent("乙", "222").get_json()["passcode"]
        start = self.client.post("/api/admin/trade/start")
        self.assertEqual(start.status_code, 200)
        self.assertEqual(start.get_json()["name"], "甲")
        self.assertEqual(self.query(first).get_json()["stage"], "trading")
        self.assertEqual(self.admin_product()["product"]["status"], "frozen")
        self.assertEqual(self.intent().status_code, 409)

        self.assertEqual(self.client.post("/api/admin/trade/success").status_code, 200)
        self.assertIsNone(self.client.get("/api/product").get_json()["product"])
        self.assertEqual(self.query(first).status_code, 403)
        self.assertEqual(self.query(second).status_code, 403)
        history = self.client.get("/api/admin/history").get_json()["products"]
        self.assertEqual(len(history), 1)
        self.assertEqual(history[0]["result"], "success")
        self.assertTrue(history[0]["resultAt"])
        self.assertEqual(
            {item["name"]: item["outcome"] for item in history[0]["intents"]},
            {"甲": "success", "乙": "unsold"},
        )
        self.assertEqual(self.client.post("/api/admin/trade/success").status_code, 409)
        self.assertEqual(self.publish().status_code, 201)

    def test_failed_trade_requeues_to_tail_then_voids_and_restores_sale(self):
        self.login()
        self.publish()
        first = self.intent("甲", "111").get_json()["passcode"]
        second = self.intent("乙", "222").get_json()["passcode"]
        self.client.post("/api/admin/trade/start")
        failed = self.client.post("/api/admin/trade/fail", json={"disposition": "requeue"})
        self.assertEqual(failed.status_code, 200)
        replacement = failed.get_json()["newPasscode"]
        self.assertTrue(replacement)
        self.assertNotIn(replacement, (first, second))
        self.assertEqual(self.query(first).status_code, 403)
        self.assertEqual(self.query(second).get_json()["stage"], "trading")
        self.assertEqual(self.query(replacement).get_json()["position"], 1)
        self.assertEqual(self.admin_product()["product"]["status"], "frozen")

        self.assertEqual(
            self.client.post("/api/admin/trade/fail", json={"disposition": "void"}).status_code,
            200,
        )
        self.assertEqual(self.query(second).status_code, 403)
        self.assertEqual(self.query(replacement).get_json()["stage"], "trading")
        self.assertEqual(
            self.client.post("/api/admin/trade/fail", json={"disposition": "void"}).status_code,
            200,
        )
        self.assertEqual(self.query(replacement).status_code, 403)
        self.assertEqual(self.admin_product()["product"]["status"], "resumed")
        self.assertEqual(self.intent("丙", "333").status_code, 201)

        db = json.loads(self.data_file.read_text(encoding="utf-8"))
        self.assertEqual(
            [item["outcome"] for item in db["intents"][:3]],
            ["requeued", "voided", "voided"],
        )

    def test_trading_buyer_cancel_requires_seller_advance(self):
        self.login()
        self.publish()
        first = self.intent("甲", "111").get_json()["passcode"]
        second = self.intent("乙", "222").get_json()["passcode"]
        self.client.post("/api/admin/trade/start")
        self.assertEqual(
            self.client.post("/api/intents/cancel", json={"passcode": first}).status_code,
            200,
        )
        self.assertEqual(self.query(first).status_code, 403)
        self.assertEqual(self.admin_product()["product"]["status"], "frozen")
        self.assertEqual(self.query(second).get_json()["stage"], "queued")
        marked = self.admin_product()["tradingIntent"]
        self.assertEqual(marked["outcome"], "cancelled")
        self.assertIn(marked["id"], {item["id"] for item in self.admin_intents()})
        self.assertEqual(self.client.post("/api/admin/trade/advance").status_code, 200)
        self.assertEqual(self.query(second).get_json()["stage"], "trading")
        self.assertEqual(self.admin_product()["product"]["status"], "frozen")

        self.client.post("/api/intents/cancel", json={"passcode": second})
        self.assertEqual(self.client.post("/api/admin/trade/advance").status_code, 200)
        self.assertEqual(self.admin_product()["product"]["status"], "resumed")
        self.assertEqual(self.client.post("/api/admin/trade/advance").status_code, 409)

    def test_upload_validation_and_product_uniqueness(self):
        self.login()
        base = {"name": "杯子", "description": "手作", "price": "12.50"}
        missing_image = self.client.post("/api/products", data=base)
        self.assertEqual(missing_image.status_code, 400)
        invalid_type = self.publish(image=b"not an image", filename="bad.gif", content_type="image/gif")
        self.assertEqual(invalid_type.status_code, 400)
        forged_png = self.publish(image=b"not an image", filename="bad.png", content_type="image/png")
        self.assertEqual(forged_png.status_code, 400)
        too_large = self.publish(image=b"x" * (5 * 1024 * 1024 + 1))
        self.assertEqual(too_large.status_code, 400)
        zero_price = self.publish(price="0")
        self.assertEqual(zero_price.status_code, 400)
        self.assertIsNone(self.client.get("/api/product").get_json()["product"])
        self.assertEqual(self.publish().status_code, 201)
        self.assertEqual(self.publish().status_code, 409)
        self.assertEqual(len(list(self.upload_dir.glob("*"))), 1)

    def test_data_survives_new_app_instance(self):
        self.login()
        self.publish()
        code = self.intent("留存买家", "13900000000").get_json()["passcode"]
        self.assertTrue(self.data_file.exists())
        restarted = create_app(data_file=self.data_file, upload_dir=self.upload_dir)
        restarted.testing = True
        client = restarted.test_client()
        self.assertEqual(client.get("/api/product").get_json()["product"]["name"], "手作杯子")
        self.assertEqual(
            client.post("/api/intents/query", json={"passcode": code}).get_json()["position"],
            1,
        )
        self.assertEqual(
            client.post(
                "/api/auth/login", json={"username": "admin", "password": "admin1234"}
            ).status_code,
            200,
        )
        self.assertEqual(len(client.get("/api/admin/intents").get_json()["intents"]), 1)


if __name__ == "__main__":
    unittest.main()
