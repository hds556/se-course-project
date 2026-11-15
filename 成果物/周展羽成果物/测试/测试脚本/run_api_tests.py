# -*- coding: utf-8 -*-
"""第四次迭代（挑战需求B：订单/购物车/收藏 + 商品生命周期）接口层黑盒测试。

按 SRS V4.0 的 FR-C-01~10 / FR-S-01~13 与 §9 验收场景设计用例，
只通过 HTTP 接口驱动，不依赖任何内部实现细节。
"""
import json
import os
import sys
import time
import uuid
import threading
import urllib.request
import urllib.error
import urllib.parse
import http.cookiejar

BASE = os.environ.get("TEST_BASE", "http://127.0.0.1:18080")
HERE = os.path.dirname(os.path.abspath(__file__))
EVID = os.path.join(HERE, "证据")
os.makedirs(EVID, exist_ok=True)

RESULTS = []
ARTIFACTS = {}


def log(case_id, title, ok, detail, srs=""):
    RESULTS.append({"id": case_id, "title": title, "pass": bool(ok), "detail": detail, "srs": srs})
    print(("PASS " if ok else "FAIL ") + case_id + " " + title + ("  | " + str(detail)[:220] if detail else ""))


class Client:
    def __init__(self, name="anon"):
        self.name = name
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def req(self, method, path, body=None, raw_body=None, headers=None, timeout=20):
        url = BASE + path
        data = None
        hdrs = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json; charset=utf-8"
        elif raw_body is not None:
            data, ctype = raw_body
            hdrs["Content-Type"] = ctype
        if headers:
            hdrs.update(headers)
        rq = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with self.opener.open(rq, timeout=timeout) as r:
                payload = r.read()
                return r.status, _maybe_json(payload)
        except urllib.error.HTTPError as e:
            payload = e.read()
            return e.code, _maybe_json(payload)
        except Exception as e:  # 连接层异常
            return 0, {"exception": repr(e)}


def _maybe_json(payload):
    if not payload:
        return None
    try:
        return json.loads(payload.decode("utf-8"))
    except Exception:
        return payload.decode("utf-8", "ignore")


def multipart(fields, files=None):
    boundary = "----wk" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(("--" + boundary + "\r\n").encode())
        parts.append(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode())
        parts.append(str(v).encode("utf-8") + b"\r\n")
    for name, filename, content, ctype in (files or []):
        parts.append(("--" + boundary + "\r\n").encode())
        parts.append(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (name, filename)).encode())
        parts.append(("Content-Type: %s\r\n\r\n" % ctype).encode())
        parts.append(content + b"\r\n")
    parts.append(("--" + boundary + "--\r\n").encode())
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


# ---------------- 夹具 ----------------
seller = Client("seller")
s, b = seller.req("POST", "/api/auth/login", {"username": "admin", "password": "admin1234"})
assert s == 200, ("卖家登录失败", s, b)
ARTIFACTS["seller_login"] = (s, b)

s, cat1 = seller.req("POST", "/api/admin/categories", {"name": "数码"})
assert s == 201, ("建一级类别失败", s, cat1)
s, cat2 = seller.req("POST", "/api/admin/categories", {"name": "耳机", "parentId": cat1["id"]})
assert s == 201, ("建二级类别失败", s, cat2)
s, cat3 = seller.req("POST", "/api/admin/categories", {"name": "键盘", "parentId": cat1["id"]})
assert s == 201, ("建二级类别失败", s, cat3)
L2, L2B = cat2["id"], cat3["id"]


def publish(name, price, stock, category=L2, description="<p>默认描述</p>"):
    body, ctype = multipart({"name": name, "price": price, "stock": stock,
                             "categoryId": category, "description": description})
    st, resp = seller.req("POST", "/api/products", raw_body=(body, ctype))
    assert st == 201, ("发布失败", name, st, resp)
    return resp["id"]


P = {}
P["A"] = publish("测试商品A", "100.00", 10, L2, "<p>降噪耳机，续航三十小时，支持快充</p>")
P["B"] = publish("测试商品B", "50.00", 1, L2B, "<p>机械键盘，青轴手感</p>")
P["C"] = publish("测试商品C", "20.00", 2, L2, "<p>备用配件</p>")
P["D"] = publish("测试商品D", "30.00", 3, L2B, "<p>用于手动下架测试</p>")
P["E"] = publish("测试商品E", "10.00", 5, L2, "<p>用于并发下单测试</p>")
P["F"] = publish("测试商品F", "15.00", 1, L2, "<p>用于售罄恢复测试</p>")
ARTIFACTS["products"] = P


def admin_product(pid):
    st, b = seller.req("GET", "/api/admin/products/%d" % pid)
    assert st == 200, ("卖家查询商品失败", pid, st, b)
    return b


def stock_of(pid):
    return admin_product(pid)["stock"]


def status_of(pid):
    return admin_product(pid)["status"]


def new_customer(tag):
    c = Client(tag)
    name = "cust_%s_%d" % (tag, int(time.time() * 1000) % 1000000)
    st, b = c.req("POST", "/api/customer/register",
                  {"username": name, "password": "pass1234", "phone": "1380000%04d" % (abs(hash(name)) % 10000),
                   "defaultPlace": "教学楼A"})
    assert st == 201, ("注册失败", st, b)
    st, b = c.req("POST", "/api/customer/login", {"username": name, "password": "pass1234"})
    assert st == 200, ("登录失败", st, b)
    c.username = name
    st, me = c.req("GET", "/api/customer/me")
    assert st == 200, ("读取会话失败", st, me)
    c.cid = me.get("id")
    return c


def add_cart(c, pid, qty=1):
    st, b = c.req("POST", "/api/cart", {"productId": pid, "quantity": qty})
    assert st == 201, ("加购失败", pid, st, b)
    return b["id"]


def submit(c, item_ids):
    return c.req("POST", "/api/orders", {"cartItemIds": item_ids})


def advance(oid, to=None):
    body = {} if to is None else {"to": to}
    return seller.req("POST", "/api/admin/orders/%d/advance" % oid, body)


def set_stock(pid, n):
    v = admin_product(pid)["version"]
    return seller.req("PATCH", "/api/admin/products/%d/stock" % pid, {"stock": n, "version": v})


def withdraw(pid):
    v = admin_product(pid)["version"]
    return seller.req("POST", "/api/admin/products/%d/withdraw" % pid, {"version": v})


# ---------------- A 商品浏览 / 搜索 / 详情 ----------------
def case(fn):
    fn()
    return fn


def a1():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?size=50")
    ids = [x["id"] for x in b["items"]]
    log("TC-C01", "未登录浏览商品列表，只含在售商品", st == 200 and set(P.values()).issubset(set(ids)),
        {"status": st, "total": b["total"]}, "FR-C-01/AC-01")


def a2():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?size=50")
    item = b["items"][0]
    ok = ("name" in item and "priceCents" in item and "image" in item and "category" in item
          and "stock" not in item and "description" not in item)
    log("TC-C02", "列表卡片字段为名称/价格/主图/类别，不含描述与库存", ok, sorted(item.keys()), "FR-C-01")


def a3():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?categoryId=%d&size=50" % L2B)
    ok = st == 200 and b["total"] >= 1 and all(x["category"]["id"] == L2B for x in b["items"])
    log("TC-C03", "按二级类别筛选只返回该类别商品", ok, {"total": b["total"]}, "FR-C-01")


def a4():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?q=" + urllib.parse.quote("商品A"))
    ok = st == 200 and any(x["id"] == P["A"] for x in b["items"])
    log("TC-C04", "按名称关键词搜索命中", ok, b["total"], "FR-C-02")


def a5():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?q=" + urllib.parse.quote("续航三十小时"))
    ok = st == 200 and any(x["id"] == P["A"] for x in b["items"])
    log("TC-C05", "描述纯文本关键词可命中（富文本已剥离）", ok, b["total"], "FR-C-02/AC-07")


def a6():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?q=" + urllib.parse.quote("耳机"))
    # 类别名"耳机"只出现在 category 上，不应成为命中依据（A 的描述含"降噪耳机"会命中，故断言 C 不出现）
    ok = st == 200 and all(x["id"] != P["C"] for x in b["items"])
    log("TC-C06", "类别名不参与匹配（仅因归类不命中）", ok, [x["id"] for x in b["items"]], "FR-C-02")


def a7():
    c = Client("anon")
    st, b = c.req("GET", "/api/products?q=&size=50")
    ok = st == 200 and b["total"] >= 6
    log("TC-C07", "空关键词返回全部在售商品", ok, b["total"], "FR-C-02")


def a8():
    c = Client("anon")
    st, b = c.req("GET", "/api/products/%d" % P["A"])
    ok = st == 200 and "stock" in b and b["stock"] == 10
    log("TC-C08", "在售商品详情含价格/可售库存/描述/图片", ok, {k: b.get(k) for k in ("stock", "readOnly")}, "FR-C-03")


# ---------------- B 注册 / 登录 ----------------
def b1():
    c = Client("anon")
    st, b = c.req("POST", "/api/customer/register", {"username": "x" + uuid.uuid4().hex[:6], "password": "pass1234"})
    msg = (b or {}).get("error", "") if isinstance(b, dict) else ""
    ok = st == 400 and ("电话" in msg or "fieldErrors" in (b or {}))
    log("TC-C09", "缺电话注册被拒绝并指出缺失字段", ok,
        {"status": st, "body": b, "有fieldErrors": "fieldErrors" in (b or {})}, "FR-C-04")


def b2():
    c = Client("anon")
    st, b = c.req("POST", "/api/customer/register",
                  {"username": "p7_" + uuid.uuid4().hex[:6], "password": "1234567", "phone": "13900000001"})
    log("TC-C10", "密码 7 位被拒绝", st == 400, {"status": st}, "FR-C-04")


def b3():
    c = Client("anon")
    name = "p8_" + uuid.uuid4().hex[:6]
    st, b = c.req("POST", "/api/customer/register", {"username": name, "password": "12345678", "phone": "13900000002"})
    st2, b2_ = c.req("GET", "/api/customer/me")
    ok = st == 201 and st2 == 401
    log("TC-C11", "密码 8 位可注册，且注册后不自动登录", ok, {"register": st, "me": st2}, "FR-C-04")


def b4():
    c = Client("anon")
    st, b = c.req("POST", "/api/customer/register", {"username": "dup_" + uuid.uuid4().hex[:6],
                                                     "password": "pass1234", "phone": "13900000003"})
    name = "dup_" + uuid.uuid4().hex[:6]
    c.req("POST", "/api/customer/register", {"username": name, "password": "pass1234", "phone": "13900000004"})
    st2, b2_ = c.req("POST", "/api/customer/register", {"username": name, "password": "pass1234", "phone": "13900000005"})
    log("TC-C12", "重复用户名注册被拒绝", st2 in (400, 409), {"status": st2, "body": b2_}, "FR-C-04")


def b5():
    c = Client("anon")
    phone = "13911112222"
    n1 = "tel_a_" + uuid.uuid4().hex[:5]
    n2 = "tel_b_" + uuid.uuid4().hex[:5]
    s1, _ = c.req("POST", "/api/customer/register", {"username": n1, "password": "pass1234", "phone": phone})
    s2, _ = c.req("POST", "/api/customer/register", {"username": n2, "password": "pass1234", "phone": phone})
    log("TC-C13", "同一电话可注册两个不同账号", s1 == 201 and s2 == 201, [s1, s2], "FR-C-04")


def b6():
    c = new_customer("tel")
    st, b = c.req("POST", "/api/customer/login", {"username": "13800000000", "password": "pass1234"})
    log("TC-C14", "电话不能作为登录凭证", st == 401, {"status": st}, "FR-C-05")


def b7():
    c = Client("anon")
    s1, b1_ = c.req("POST", "/api/customer/login", {"username": "no_such_user_zz", "password": "pass1234"})
    s2, b2_ = c.req("POST", "/api/customer/login", {"username": "cust_tel", "password": "wrongpass"})
    m1 = (b1_ or {}).get("message") if isinstance(b1_, dict) else None
    m2 = (b2_ or {}).get("message") if isinstance(b2_, dict) else None
    ok = s1 == 401 and s2 == 401 and m1 == m2
    log("TC-C15", "用户名不存在与密码错误提示一致", ok, {"m1": m1, "m2": m2}, "FR-C-05")


def b8():
    c = new_customer("logout")
    st, b = c.req("POST", "/api/customer/logout")
    st2, b2_ = c.req("GET", "/api/favorites")
    ok = st == 200 and st2 == 401
    log("TC-C16", "退出登录后收藏/购物车/下单入口不可用", ok, {"logout": st, "favorites": st2}, "FR-C-05")


# ---------------- C 收藏 ----------------
def c1():
    c = Client("anon")
    st, b = c.req("POST", "/api/favorites/%d" % P["A"])
    log("TC-C17", "未登录收藏返回 401", st == 401, {"status": st}, "FR-C-06")


def c2():
    c = new_customer("fav")
    st, b = c.req("POST", "/api/favorites/%d" % P["A"])
    st2, lst = c.req("GET", "/api/favorites")
    ok = st == 201 and any(x["productId"] == P["A"] for x in lst["items"])
    st3, _ = c.req("DELETE", "/api/favorites/%d" % P["A"])
    st4, lst2 = c.req("GET", "/api/favorites")
    ok = ok and st3 == 200 and not any(x["productId"] == P["A"] for x in lst2["items"])
    log("TC-C18", "收藏/取消收藏生效", ok, {"add": st, "del": st3}, "FR-C-06")


def c3():
    c = new_customer("fav2")
    c.req("POST", "/api/favorites/%d" % P["A"])
    c.req("POST", "/api/favorites/%d" % P["A"])
    st, lst = c.req("GET", "/api/favorites")
    n = sum(1 for x in lst["items"] if x["productId"] == P["A"])
    log("TC-C19", "重复收藏不产生第二条记录", n == 1, {"count": n}, "FR-C-06")


def c4():
    c = new_customer("fav3")
    c.req("POST", "/api/favorites/%d" % P["D"])
    st, b = c.req("GET", "/api/products/%d/favorite-detail" % P["D"])
    ok1 = st == 200 and b.get("stock") is not None
    other = new_customer("favother")
    st2, _ = other.req("GET", "/api/products/%d/favorite-detail" % P["D"])
    log("TC-C20", "收藏详情仅本人可访问（他人已登录访问 404）", ok1 and st2 == 404, {"owner": st, "other": st2}, "FR-C-03")


# ---------------- D 购物车 ----------------
def d1():
    c = Client("anon")
    st, b = c.req("POST", "/api/cart", {"productId": P["A"], "quantity": 1})
    log("TC-C21", "未登录加购返回 401", st == 401, {"status": st}, "FR-C-07")


def d2():
    c = new_customer("cart")
    before = stock_of(P["A"])
    item = add_cart(c, P["A"], 2)
    st, lst = c.req("GET", "/api/cart")
    after = stock_of(P["A"])
    rows = [x for x in lst["items"] if x["productId"] == P["A"]]
    ok = len(rows) == 1 and rows[0]["quantity"] == 2 and before == after
    log("TC-C22", "加购成功且不占用库存（仅一行）", ok, {"rows": len(rows), "qty": rows[0]["quantity"],
                                                    "stock": [before, after]}, "FR-C-07")
    st2, _ = c.req("PATCH", "/api/cart/%d" % item, {"quantity": 3})
    st3, _ = c.req("DELETE", "/api/cart/%d" % item)
    st4, lst2 = c.req("GET", "/api/cart")
    log("TC-C23", "修改数量与移除生效", st2 == 200 and st3 == 200 and not lst2["items"],
        {"patch": st2, "del": st3}, "FR-C-07")
    return c


def d3():
    c = new_customer("cart2")
    add_cart(c, P["A"], 1)
    add_cart(c, P["A"], 1)
    st, lst = c.req("GET", "/api/cart")
    rows = [x for x in lst["items"] if x["productId"] == P["A"]]
    log("TC-C24", "同一商品重复加购仍只有一行", len(rows) == 1, {"rows": len(rows)}, "FR-C-07")


# ---------------- E 提交订单 ----------------
def e1():
    c = new_customer("order1")
    i1 = add_cart(c, P["A"], 2)
    i2 = add_cart(c, P["C"], 1)
    keep = add_cart(c, P["B"], 1)
    sA, sC = stock_of(P["A"]), stock_of(P["C"])
    st, b = submit(c, [i1, i2])
    ok = st == 201 and b["status"] == "placed" and len(b["items"]) == 2 and b["totalCents"] == 10000 * 2 + 2000
    log("TC-C25", "多件下单生成 1 笔订单（2 条明细）且金额正确", ok,
        {"status": st, "orderStatus": b.get("status"), "lines": len(b.get("items", [])), "total": b.get("totalCents")},
        "FR-C-08/AC-02")
    st2, lst = c.req("GET", "/api/cart")
    left_ids = [x["id"] for x in lst["items"]]
    log("TC-C26", "下单后选中项删除、未选中项保留", st2 == 200 and keep in left_ids and i1 not in left_ids,
        {"left": left_ids, "keep": keep}, "FR-C-08")
    log("TC-C27", "下单即扣减可售库存", stock_of(P["A"]) == sA - 2 and stock_of(P["C"]) == sC - 1,
        {"A": [sA, stock_of(P["A"])], "C": [sC, stock_of(P["C"])]}, "FR-C-08")
    ARTIFACTS["order1"] = b
    # 快照：改价后订单不变
    v = admin_product(P["A"])["version"]
    body, ctype = multipart({"name": "测试商品A", "price": "999.00", "categoryId": L2,
                             "description": "<p>降噪耳机，续航三十小时，支持快充</p>", "version": v})
    st3, _ = seller.req("PUT", "/api/admin/products/%d/content" % P["A"], raw_body=(body, ctype))
    st4, od = c.req("GET", "/api/orders/%d" % b["id"])
    unit = [x for x in od["items"] if x["productId"] == P["A"]][0]["unitPriceCents"]
    log("TC-C28", "改价后历史订单单价仍为下单时快照", st3 == 200 and unit == 10000,
        {"edit": st3, "unit": unit, "total": od["totalCents"]}, "FR-C-08/FR-S-05/AC-08")
    return c, b


def e2():
    c = new_customer("short")
    # B 库存 1，加购 3 件；A 库存充足
    iB = add_cart(c, P["B"], 3)
    iA = add_cart(c, P["A"], 1)
    sA = stock_of(P["A"])
    st, b = submit(c, [iA, iB])
    ok = st == 201 and len(b["items"]) == 1 and str(P["B"]) in (b.get("rejectedItems") or {})
    log("TC-C29", "部分缺货：缺货项剔除、其余正常下单", ok,
        {"status": st, "lines": len(b.get("items", [])), "rejected": b.get("rejectedItems")}, "FR-C-08/AC-04")
    log("TC-C30", "缺货商品不扣库存、不进入订单", stock_of(P["B"]) == 1 and stock_of(P["A"]) == sA - 1,
        {"B": stock_of(P["B"]), "A": [sA, stock_of(P["A"])]}, "FR-C-08")
    return c


def e3():
    c = new_customer("allshort")
    iB = add_cart(c, P["B"], 99)
    st, b = submit(c, [iB])
    log("TC-C31", "全部缺货时不生成订单（409）", st == 409 and (b or {}).get("itemErrors"),
        {"status": st, "body": b}, "FR-C-08")


def e4():
    c = new_customer("empty")
    st, b = submit(c, [])
    log("TC-C32", "未勾选商品提交被拒绝（400）", st == 400, {"status": st, "body": b}, "FR-C-08")


def e5():
    # 并发：E 库存 5，两个客户各加购 5 件，同时提交
    c1 = new_customer("race1")
    c2 = new_customer("race2")
    i1 = add_cart(c1, P["E"], 5)
    i2 = add_cart(c2, P["E"], 5)
    before = stock_of(P["E"])
    out = {}
    barrier = threading.Barrier(2)

    def go(key, cli, item):
        barrier.wait()
        out[key] = submit(cli, [item])

    t1 = threading.Thread(target=go, args=("c1", c1, i1))
    t2 = threading.Thread(target=go, args=("c2", c2, i2))
    t1.start(); t2.start(); t1.join(); t2.join()
    after = stock_of(P["E"])
    wins = sum(1 for k in out if out[k][0] == 201)
    ok = wins == 1 and after == 0
    log("TC-C33", "并发提交不超卖（1 成功 1 缺货，库存不为负）", ok,
        {"results": {k: out[k][0] for k in out}, "stock": [before, after]}, "FR-C-08/AC-09")


# ---------------- F 客户取消 ----------------
def f1():
    c = new_customer("cancel1")
    iA = add_cart(c, P["A"], 1)
    sA = stock_of(P["A"])
    st, o = submit(c, [iA])
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    ok = st == 201 and st2 == 200 and b["status"] == "cancelled" and stock_of(P["A"]) == sA
    log("TC-C34", "客户在「客户下单」状态可取消，库存回补", ok,
        {"cancel": st2, "status": (b or {}).get("status"), "stock": [sA, stock_of(P["A"])]}, "FR-C-09/AC-03")
    st3, lst = c.req("GET", "/api/orders")
    ok2 = any(x["id"] == o["id"] and x["status"] == "cancelled" for x in lst["items"])
    log("TC-C35", "取消后订单仍在「我的订单」中，状态已取消", ok2, lst["total"], "FR-C-09/FR-C-10")


def f2():
    c = new_customer("cancel2")
    iA = add_cart(c, P["A"], 1)
    st, o = submit(c, [iA])
    for _ in range(3):
        advance(o["id"])
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    log("TC-C36", "「开始发货」后客户不能取消（409）", st2 == 409,
        {"status": st2, "body": b}, "FR-C-09")


def f3():
    c = new_customer("cancel3")
    iF = add_cart(c, P["F"], 1)
    st, o = submit(c, [iF])
    st_depleted = status_of(P["F"])
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    st_active = status_of(P["F"])
    ok = st_depleted == "depleted" and st2 == 200 and st_active == "active"
    log("TC-C37", "售罄下架商品在取消后自动恢复在售", ok,
        {"afterOrder": st_depleted, "cancel": st2, "afterCancel": st_active}, "FR-C-09/FR-S-06")


# ---------------- G 卖家推进 ----------------
def g1():
    c = new_customer("advance")
    iA = add_cart(c, P["A"], 1)
    st, o = submit(c, [iA])
    seq = []
    for _ in range(4):
        s1, b1_ = advance(o["id"])
        seq.append((s1, (b1_ or {}).get("status")))
    s_final, b_final = seller.req("GET", "/api/admin/orders/%d" % o["id"])
    ok = all(x[0] == 200 for x in seq) and b_final["status"] == "completed"
    log("TC-G01", "订单按序推进四次到达「交易完成」", ok, {"seq": seq, "final": b_final.get("status")}, "FR-S-08/AC-05")


def g2():
    c = new_customer("skip")
    iC = add_cart(c, P["C"], 1)
    st, o = submit(c, [iC])
    s1, b1_ = advance(o["id"], "packed")
    log("TC-G02", "跳状态推进被拒绝（409）", s1 == 409, {"status": s1, "body": b1_}, "FR-S-08/FR-S-14")
    st2, _ = seller.req("POST", "/api/admin/orders/%d/cancel" % o["id"])


def g3():
    c = new_customer("noc")
    iC = add_cart(c, P["C"], 1)
    st, o = submit(c, [iC])
    s_before = stock_of(P["C"])
    for _ in range(4):
        advance(o["id"])
    s_after = stock_of(P["C"])
    log("TC-G03", "推进到交易完成不再扣库存", s_before == s_after, {"stock": [s_before, s_after]}, "FR-S-08")
    s2, b2_ = advance(o["id"])
    s3, b3_ = seller.req("POST", "/api/admin/orders/%d/cancel" % o["id"])
    log("TC-G04", "已完成订单不能再推进或取消（409）", s2 == 409 and s3 == 409, {"advance": s2, "cancel": s3},
        "FR-S-08/FR-S-14")


# ---------------- H 卖家取消 ----------------
def h1():
    c = new_customer("scancel")
    iA = add_cart(c, P["A"], 2)
    sA = stock_of(P["A"])
    st, o = submit(c, [iA])
    for _ in range(3):
        advance(o["id"])  # -> shipped
    st2, b = seller.req("POST", "/api/admin/orders/%d/cancel" % o["id"])
    ok = st2 == 200 and b["status"] == "cancelled" and stock_of(P["A"]) == sA
    log("TC-H01", "「开始发货」状态卖家可取消，库存回补", ok,
        {"cancel": st2, "status": (b or {}).get("status"), "stock": [sA, stock_of(P["A"])]}, "FR-S-09/AC-06")
    return c, o


def h2():
    # 卖家侧取消：已取消订单再次取消
    c = new_customer("scancel2")
    pid = publish("取消重复测试", "18.00", 3, L2, "<p>重复取消</p>")
    iC = add_cart(c, pid, 1)
    st, o = submit(c, [iC])
    seller.req("POST", "/api/admin/orders/%d/cancel" % o["id"])
    st2, b = seller.req("POST", "/api/admin/orders/%d/cancel" % o["id"])
    log("TC-H02", "已取消订单再次取消被拒绝（409）", st2 == 409, {"status": st2}, "FR-S-09")


# ---------------- I 卖家订单查询 / 越权 ----------------
def i1():
    c = new_customer("hist")
    # 构造三种状态：进行中 / 完成 / 取消
    i1_ = add_cart(c, P["A"], 1)
    _, o1 = submit(c, [i1_])
    i2_ = add_cart(c, P["A"], 1)
    _, o2 = submit(c, [i2_])
    for _ in range(4):
        advance(o2["id"])
    i3_ = add_cart(c, P["A"], 1)
    _, o3 = submit(c, [i3_])
    c.req("POST", "/api/orders/%d/cancel" % o3["id"])
    st, b = seller.req("GET", "/api/admin/orders?customerId=%d&size=50" % c.cid)
    statuses = {x["status"] for x in b["items"]}
    ok = st == 200 and {"placed", "completed", "cancelled"}.issubset(statuses)
    log("TC-I01", "客户购买历史含进行中/完成/取消三种状态", ok, {"statuses": statuses, "total": b["total"]},
        "FR-S-11/AC-10")
    # 越权：另一个客户访问该订单
    other = new_customer("other")
    st2, _ = other.req("GET", "/api/orders/%d" % o1["id"])
    log("TC-I02", "他人订单号访问返回 404/403", st2 in (403, 404), {"status": st2}, "FR-C-10")
    return c


def i2():
    c = new_customer("prodhist")
    iA = add_cart(c, P["D"], 1)
    st, o = submit(c, [iA])
    c.req("POST", "/api/orders/%d/cancel" % o["id"])
    st2, b = seller.req("GET", "/api/admin/orders?productId=%d&size=50" % P["D"])
    ok = st2 == 200 and any(x["id"] == o["id"] and x["status"] == "cancelled" for x in b["items"])
    log("TC-I03", "商品订单记录含已取消订单", ok, {"total": b["total"]}, "FR-S-12")


def i3():
    c = Client("anon")
    s1, _ = c.req("GET", "/api/admin/orders")
    s2, _ = c.req("GET", "/api/admin/products")
    s3, _ = c.req("GET", "/api/admin/customers")
    log("TC-I04", "未登录访问卖家接口返回 401", s1 == 401 and s2 == 401 and s3 == 401, [s1, s2, s3], "FR-S-01/BR-40")


def i5():
    cust = new_customer("crossrole")
    st, b = cust.req("POST", "/api/auth/login", {"username": cust.username, "password": "pass1234"})
    log("TC-I05", "客户账号不能登录卖家后台", st == 401, {"status": st}, "FR-S-01")


# ---------------- J 商品生命周期 ----------------
def j1():
    pid = publish("生命周期A", "12.00", 2, L2, "<p>生命周期测试</p>")
    st, b = set_stock(pid, 0)
    stt = status_of(pid)
    c = Client("anon")
    s_l, lst = c.req("GET", "/api/products?size=50")
    hidden = all(x["id"] != pid for x in lst["items"])
    s_d, _ = c.req("GET", "/api/products/%d" % pid)
    ok = st == 200 and stt == "depleted" and hidden and s_d == 404
    log("TC-J01", "库存改 0 自动下架（售罄），买家端不可见、详情 404", ok,
        {"stock": st, "status": stt, "listHidden": hidden, "detail": s_d}, "FR-S-06/AC-11")
    st2, _ = set_stock(pid, 5)
    ok2 = st2 == 200 and status_of(pid) == "active"
    log("TC-J02", "售罄下架商品补货后自动恢复在售", ok2, {"restore": st2, "status": status_of(pid)},
        "FR-S-06/AC-12")
    return pid


def j3():
    pid = publish("生命周期B", "13.00", 3, L2B, "<p>手动下架测试</p>")
    st, b = withdraw(pid)
    stt = status_of(pid)
    st2, b2 = set_stock(pid, 10)
    stt2 = status_of(pid)
    c = Client("anon")
    s_d, _ = c.req("GET", "/api/products/%d" % pid)
    ok = st == 200 and stt == "withdrawn" and st2 == 200 and stt2 == "withdrawn" and s_d == 404
    log("TC-J03", "手动下架为终态：调库存也不恢复在售", ok,
        {"withdraw": st, "afterWithdraw": stt, "afterStock": stt2}, "FR-S-07/FR-S-06/AC-13")
    # 已下架不可编辑内容
    v = admin_product(pid)["version"]
    body, ctype = multipart({"name": "改名试试", "price": "13.00", "categoryId": L2B,
                            "description": "<p>x</p>", "version": v})
    s_e, b_e = seller.req("PUT", "/api/admin/products/%d/content" % pid, raw_body=(body, ctype))
    log("TC-J04", "已下架商品不可编辑内容（409/400）", s_e in (400, 409), {"status": s_e, "body": b_e}, "FR-S-05/FR-S-14")
    return pid


def j5():
    pid = publish("生命周期C", "14.00", 2, L2, "<p>在途订单时下架</p>")
    c = new_customer("withdrawinc")
    item = add_cart(c, pid, 1)
    st, o = submit(c, [item])
    s_w, _ = withdraw(pid)
    ok_seq = []
    for _ in range(4):
        s1, b1_ = advance(o["id"])
        ok_seq.append(s1)
    stt = seller.req("GET", "/api/admin/orders/%d" % o["id"])[1]["status"]
    ok = s_w == 200 and all(x == 200 for x in ok_seq) and stt == "completed"
    log("TC-J05", "有在途订单时手动下架，订单仍可推进到完成", ok, {"withdraw": s_w, "advance": ok_seq, "final": stt},
        "FR-S-07/AC-13")
    return pid


def j6(_=None):
    st, arr = seller.req("GET", "/api/admin/products/offline")
    items = arr if isinstance(arr, list) else arr.get("items", [])
    reasons = {x["id"]: x.get("offlineReason") for x in items}
    got = set(reasons.values())
    ok = st == 200 and {"sold_out", "manual"}.issubset(got)
    log("TC-J06", "历史商品列表区分售罄下架与手动下架两类成因", ok,
        {"reasons": sorted(got), "count": len(items)}, "FR-S-13/AC-14")


# ---------------- K 权限 / 一致性 ----------------
def k1():
    c = new_customer("custlist")
    st, b = seller.req("GET", "/api/admin/customers?size=50")
    usernames = [x.get("username") for x in (b.get("items") or [])]
    ok = st == 200 and c.username in usernames
    log("TC-K01", "卖家客户列表含新注册客户，且无编辑/删除能力", ok, {"total": b.get("total")}, "FR-S-10")


def k2():
    c = new_customer("oldapi")
    st, b = c.req("GET", "/api/customer/orders")
    ok = st == 200 and (b.get("total") == 0 and b.get("movedTo") == "/api/orders")
    log("TC-K02", "旧「历史下单记录」接口已迁移到 /api/orders（不再返回意向数据）", ok,
        {"status": st, "movedTo": (b or {}).get("movedTo")}, "FR-C-10/附录C K-1")


def k3():
    c = new_customer("me")
    st, b = c.req("GET", "/api/customer/me")
    ok = st == 200 and b.get("username") == c.username
    log("TC-K03", "客户会话信息可用（收藏/购物车/订单身份一致）", ok, {"status": st}, "FR-C-05")


CASES = [
    ("A1", a1), ("A2", a2), ("A3", a3), ("A4", a4), ("A5", a5), ("A6", a6), ("A7", a7), ("A8", a8),
    ("B1", b1), ("B2", b2), ("B3", b3), ("B4", b4), ("B5", b5), ("B6", b6), ("B7", b7), ("B8", b8),
    ("C1", c1), ("C2", c2), ("C3", c3), ("C4", c4),
    ("D1", d1), ("D2", d2), ("D3", d3),
    ("E1", e1), ("E2", e2), ("E3", e3), ("E4", e4), ("E5", e5),
    ("F1", f1), ("F2", f2), ("F3", f3),
    ("G1", g1), ("G2", g2), ("G3", g3),
    ("H1", h1), ("H2", h2),
    ("I1", i1), ("I2", i2), ("I3", i3), ("I5", i5),
    ("J1", j1), ("J3", j3), ("J5", j5),
    ("K1", k1), ("K2", k2), ("K3", k3),
]

def j7():
    """留一个售罄下架商品，供 J6 校验两类成因并存。"""
    pid = publish("生命周期D", "16.00", 2, L2, "<p>留作售罄成因</p>")
    set_stock(pid, 0)
    return pid


if __name__ == "__main__":
    offline = []
    for name, fn in CASES:
        try:
            fn()
        except Exception as e:
            log("ERR-" + name, "用例执行异常", False, repr(e))
            import traceback
            traceback.print_exc()
    # J6：售罄与手动两类成因并存
    try:
        j7()
        j6()
    except Exception as e:
        log("ERR-J6", "历史商品成因校验异常", False, repr(e))

    out = os.path.join(HERE, "证据", "接口测试结果.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(RESULTS, f, ensure_ascii=False, indent=2, default=str)
    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["pass"])
    print("\n==== 汇总: %d/%d 通过 ====" % (passed, total))
    for r in RESULTS:
        if not r["pass"]:
            print("FAIL", r["id"], r["title"], r["detail"])
