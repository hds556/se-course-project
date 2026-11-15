# -*- coding: utf-8 -*-
"""对抗性边界测试：非法输入、越权、边界值、排序分页、并发重复提交。

目的不是再证一遍主流程可用，而是找主流程测试覆盖不到的判定漏洞。
"""
import json
import os
import time
import uuid
import threading
import urllib.parse

import run_api_tests as R
from run_api_tests import Client, multipart, seller, publish, add_cart, submit, advance, set_stock, withdraw, \
    admin_product, stock_of, status_of, new_customer, log, RESULTS, EVID, L2, L2B, P

# 独立结果集
RESULTS.clear()

PNG = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
       b"\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82")
GIF = b"GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff!\xf9\x04\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;"


def publish_raw(fields, files=None):
    body, ctype = multipart(fields, files)
    return seller.req("POST", "/api/products", raw_body=(body, ctype))


def x1():
    c = new_customer("q0")
    st, b = c.req("POST", "/api/cart", {"productId": P["A"], "quantity": 0})
    log("TC-X01", "加购数量为 0 被拒绝（400）", st == 400, {"status": st, "body": b}, "FR-C-07")


def x2():
    c = new_customer("qneg")
    st, b = c.req("POST", "/api/cart", {"productId": P["A"], "quantity": -3})
    log("TC-X02", "加购数量为负数被拒绝（400）", st == 400, {"status": st}, "FR-C-07")


def x3():
    c = new_customer("noprod")
    st, b = c.req("POST", "/api/cart", {"productId": 987654, "quantity": 1})
    log("TC-X03", "加购不存在商品返回 404", st == 404, {"status": st, "body": b}, "FR-C-07")


def x4():
    owner = new_customer("x4owner")
    other = new_customer("x4other")
    item = add_cart(owner, P["A"], 1)
    st, b = submit(other, [item])
    st2, lst = owner.req("GET", "/api/cart")
    ok = st in (400, 403, 404) and any(x["id"] == item for x in lst["items"])
    log("TC-X04", "勾选他人购物车项不能下单，且原条目不受影响", ok, {"status": st, "body": b}, "FR-C-08/AC-10")


def x5():
    c = new_customer("x5")
    item = add_cart(c, P["A"], 1)
    s1, o1 = submit(c, [item])
    s2, o2 = submit(c, [item])
    st, lst = c.req("GET", "/api/orders")
    ok = s1 == 201 and s2 in (400, 404, 409) and lst["total"] == 1
    log("TC-X05", "同一购物车项重复提交不会生成第二笔订单", ok,
        {"first": s1, "second": s2, "orders": lst["total"]}, "FR-C-08/BR-25")


def x6():
    pid = publish("对抗-下架在购物车", "21.00", 2, L2, "<p>x</p>")
    c = new_customer("x6")
    item = add_cart(c, pid, 1)
    withdraw(pid)
    st, lst = c.req("GET", "/api/cart")
    row = [x for x in lst["items"] if x["productId"] == pid]
    ok1 = len(row) == 1 and row[0]["available"] is False
    st2, o = submit(c, [item])
    st3, lst2 = c.req("GET", "/api/cart")
    still = any(x["id"] == item for x in lst2["items"])
    ok = ok1 and st2 == 409 and still
    log("TC-X06", "购物车中已下架商品保留并标注不可下单，提交被拒且条目不清空", ok,
        {"row": row, "submit": st2, "kept": still}, "FR-C-07/BR-49")


def x7():
    pid = publish("对抗-下架在收藏", "22.00", 2, L2, "<p>x</p>")
    c = new_customer("x7")
    c.req("POST", "/api/favorites/%d" % pid)
    withdraw(pid)
    st, lst = c.req("GET", "/api/favorites")
    row = [x for x in lst["items"] if x["productId"] == pid]
    ok = st == 200 and len(row) == 1 and row[0]["status"] == "offline" and row[0]["statusLabel"] == "已下架"
    log("TC-X07", "商品下架后收藏条目保留并标注「已下架」", ok, row, "FR-C-06/AC-15")


def x8():
    base = {"name": "非法库存0", "price": "10.00", "stock": 0, "categoryId": L2, "description": "<p>x</p>"}
    st, b = publish_raw(base)
    log("TC-X08", "发布时库存为 0 被拒绝", st == 400, {"status": st, "body": b}, "FR-S-04")


def x9():
    for tag, price in [("0", "0"), ("neg", "-5.00"), ("3dp", "10.001"), ("over", "10000000.00")]:
        st, b = publish_raw({"name": "非法价格_" + tag, "price": price, "stock": 1,
                             "categoryId": L2, "description": "<p>x</p>"})
        log("TC-X09" + tag, "非法价格（%s）被拒绝" % price, st == 400, {"status": st, "body": b}, "FR-S-04")


def x10():
    st, b = publish_raw({"price": "10.00", "stock": 1, "categoryId": L2})
    log("TC-X10", "缺商品名称不能发布", st == 400, {"status": st, "body": b}, "FR-S-04")


def x11():
    files = [("images", "p%d.png" % i, PNG, "image/png") for i in range(10)]
    st, b = publish_raw({"name": "十张图", "price": "10.00", "stock": 1, "categoryId": L2}, files)
    log("TC-X11", "上传 10 张图片被拒绝", st == 400, {"status": st, "body": b}, "FR-S-04")


def x12():
    st, b = publish_raw({"name": "GIF图", "price": "10.00", "stock": 1, "categoryId": L2},
                        [("images", "a.gif", GIF, "image/gif")])
    log("TC-X12", "上传非 JPG/PNG 被拒绝", st == 400, {"status": st, "body": str(b)[:160]}, "FR-S-04")


def x13():
    big = b"\x89PNG\r\n\x1a\n" + b"\x00" * (6 * 1024 * 1024)
    st, b = publish_raw({"name": "大图", "price": "10.00", "stock": 1, "categoryId": L2},
                        [("images", "big.png", big, "image/png")])
    log("TC-X13", "单张超过 5MB 被拒绝", st in (400, 413, 500), {"status": st, "body": str(b)[:160]}, "FR-S-04")


def x14():
    pid = publish("对抗-库存边界", "23.00", 3, L2, "<p>x</p>")
    s1, b1 = set_stock(pid, -1)
    s2, b2 = set_stock(pid, 9007199254740992)
    ok = s1 == 400 and s2 == 400
    log("TC-X14", "库存负数与超浏览器精确上限均被拒绝", ok, {"neg": s1, "huge": s2}, "FR-S-06/FR-S-14")


def x15():
    pid = publish("对抗-类别删除", "24.00", 1, L2B, "<p>x</p>")
    st, b = seller.req("DELETE", "/api/admin/categories/%d" % L2B)
    st2, b2 = seller.req("POST", "/api/admin/categories", {"name": "临时空类别", "parentId": 1})
    st3, b3 = seller.req("DELETE", "/api/admin/categories/%d" % b2["id"])
    ok = st in (400, 409) and st3 == 200
    log("TC-X15", "挂着商品的类别不能删、空类别可删", ok, {"busy": st, "empty": st3}, "FR-S-03")


def x16():
    st, b = seller.req("GET", "/api/admin/customers?page=1&size=100")
    dates = [x.get("registeredAt") for x in b["items"]]
    desc = all(dates[i] >= dates[i + 1] for i in range(len(dates) - 1))
    st2, b2 = seller.req("GET", "/api/admin/customers?page=2&size=5")
    st3, b3 = seller.req("GET", "/api/admin/customers?page=1&size=5")
    ids1 = {x["id"] for x in b3["items"]}
    ids2 = {x["id"] for x in b2["items"]}
    log("TC-X16", "客户列表按注册时间倒序且分页不重叠", desc and not (ids1 & ids2),
        {"desc": desc, "size1": len(ids1), "size2": len(ids2)}, "FR-S-10")


def x17():
    c = new_customer("x17")
    for q in (2, 3):
        i = add_cart(c, P["A"], 1)
        submit(c, [i])
    st, b = c.req("GET", "/api/orders?page=1&size=10")
    times = [x["createdAt"] for x in b["items"]]
    desc = all(times[i] >= times[i + 1] for i in range(len(times) - 1))
    nos = [x["orderNo"] for x in b["items"]]
    log("TC-X17", "我的订单按下单时间倒序，订单号非空", desc and all(nos), {"times": times, "nos": nos},
        "FR-C-10")


def x18():
    c = new_customer("x18")
    item = add_cart(c, P["A"], 1)
    st, o = submit(c, [item])
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    st3, b3 = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    log("TC-X18", "已取消订单再次取消被拒绝（409）", st2 == 200 and st3 == 409, {"first": st2, "again": st3},
        "FR-C-09")


def x19():
    st, b = Client("anon").req("GET", "/api/intents")
    st2, b2 = Client("anon").req("GET", "/api/admin/trades")
    log("TC-X19", "旧意向/交易接口已停用（410）", st == 410 and st2 == 410, {"intents": st, "trades": st2},
        "附录C K-1")


def x20():
    # 并发：同一购物车项被同时提交两次
    c = new_customer("x20")
    item = add_cart(c, P["A"], 1)
    out = {}
    barrier = threading.Barrier(2)

    def go(k):
        barrier.wait()
        out[k] = submit(c, [item])

    ts = [threading.Thread(target=go, args=(k,)) for k in ("a", "b")]
    [t.start() for t in ts]
    [t.join() for t in ts]
    st, lst = c.req("GET", "/api/orders")
    ok = lst["total"] == 1 and sum(1 for k in out if out[k][0] == 201) == 1
    log("TC-X20", "同一购物车项并发重复提交只生成 1 笔订单", ok,
        {"results": {k: out[k][0] for k in out}, "orders": lst["total"]}, "FR-C-08/BR-03")


def x21():
    # 改密：错误原密码 / 7 位新密码 / 成功后旧密码失效（最后恢复）
    s1, b1 = seller.req("POST", "/api/auth/password", {"oldPassword": "wrongold", "newPassword": "newpass123"})
    s2, b2 = seller.req("POST", "/api/auth/password", {"oldPassword": "admin1234", "newPassword": "1234567"})
    s3, b3 = seller.req("POST", "/api/auth/password", {"oldPassword": "admin1234", "newPassword": "newpass123"})
    tmp = Client("tmp")
    s4, b4 = tmp.req("POST", "/api/auth/login", {"username": "admin", "password": "admin1234"})
    s5, b5 = tmp.req("POST", "/api/auth/login", {"username": "admin", "password": "newpass123"})
    ok = s1 == 400 or s1 == 401
    ok = ok and s2 == 400 and s3 == 200 and s4 == 401 and s5 == 200
    log("TC-X21", "改密：验原密码、新密码≥8 位、旧密码立即失效", ok,
        {"wrongOld": s1, "short": s2, "ok": s3, "oldLogin": s4, "newLogin": s5}, "FR-S-02")
    # 恢复原密码，避免影响后续用例
    s6, _ = tmp.req("POST", "/api/auth/password", {"oldPassword": "newpass123", "newPassword": "admin1234"})
    print("恢复卖家密码:", s6)


def x22():
    # 加购数量远超库存：允许加入（不占库存），提交时按缺货剔除
    c = new_customer("x22")
    item = add_cart(c, P["A"], 999)
    st, o = submit(c, [item])
    log("TC-X22", "加购可超库存（不占用），提交时按缺货拒绝", st == 409,
        {"submit": st}, "FR-C-07/FR-C-08")


def x23():
    c = new_customer("x23")
    st, b = c.req("GET", "/api/orders?page=0&size=10")
    log("TC-X23", "订单分页 page=0 不报 500（返回首页）", st == 200, {"status": st}, "FR-C-10")


def x24():
    # 收藏已下架商品（直接对 withdrawn 商品收藏）
    pid = publish("对抗-收藏下架品", "25.00", 1, L2, "<p>x</p>")
    withdraw(pid)
    c = new_customer("x24")
    st, b = c.req("POST", "/api/favorites/%d" % pid)
    log("TC-X24", "对已下架商品发起收藏（行为记录）", True, {"status": st, "body": b}, "FR-C-06")


def x25():
    # 编辑在售商品：改类别与名称，买家端立即生效
    pid = publish("对抗-编辑生效", "26.00", 2, L2, "<p>旧描述</p>")
    v = admin_product(pid)["version"]
    body, ctype = multipart({"name": "改名后商品", "price": "27.00", "categoryId": L2B,
                            "description": "<p>新描述关键词甲乙丙</p>", "version": v})
    st, b = seller.req("PUT", "/api/admin/products/%d/content" % pid, raw_body=(body, ctype))
    c = Client("anon")
    s2, d = c.req("GET", "/api/products/%d" % pid)
    s3, s = c.req("GET", "/api/products?q=" + urllib.parse.quote("甲乙丙"))
    ok = st == 200 and d["name"] == "改名后商品" and d["priceCents"] == 2700 and d["category"]["id"] == L2B \
        and any(x["id"] == pid for x in s["items"])
    log("TC-X25", "在售商品改名/改价/改类别立即生效，且新描述可被搜索", ok,
        {"edit": st, "name": d.get("name"), "price": d.get("priceCents")}, "FR-S-05")


CASES = [x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17, x18, x19, x20, x21, x22, x23,
         x24, x25]

if __name__ == "__main__":
    for fn in CASES:
        try:
            fn()
        except Exception as e:
            log("ERR-" + fn.__name__.upper(), "用例执行异常", False, repr(e))
            import traceback
            traceback.print_exc()
    out = os.path.join(EVID, "对抗测试结果.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(RESULTS, f, ensure_ascii=False, indent=2, default=str)
    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["pass"])
    print("\n==== 对抗测试汇总: %d/%d 通过 ====" % (passed, total))
    for r in RESULTS:
        if not r["pass"]:
            print("FAIL", r["id"], r["title"], r["detail"])
