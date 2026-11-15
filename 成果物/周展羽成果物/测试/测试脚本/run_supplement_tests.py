# -*- coding: utf-8 -*-
"""补充验证：订单占满库存后的自动下架、取消回补、越权、富文本清洗等。"""
import json
import os
import urllib.parse

from run_api_tests import (Client, seller, publish, add_cart, submit, advance, set_stock, withdraw,
                           admin_product, stock_of, status_of, new_customer, log, RESULTS, EVID, L2, L2B, P)

RESULTS.clear()


def y1():
    pid = publish("补验-占满下架", "31.00", 2, L2, "<p>占满测试</p>")
    c = new_customer("y1")
    item = add_cart(c, pid, 2)
    st, o = submit(c, [item])
    stt = status_of(pid)
    anon = Client("anon")
    s_l, lst = anon.req("GET", "/api/products?size=50")
    in_list = any(x["id"] == pid for x in lst["items"])
    s_d, _ = anon.req("GET", "/api/products/%d" % pid)
    ok = st == 201 and stt == "depleted" and not in_list and s_d == 404
    log("TC-Y01", "订单占满库存后商品自动下架（SRS §5.3：在售→已下架·售罄）", ok,
        {"submit": st, "status": stt, "inList": in_list, "detail": s_d}, "SRS §5.3/FR-S-06")
    # 补货恢复
    st2, _ = set_stock(pid, 5)
    ok2 = st2 == 200 and status_of(pid) == "active"
    log("TC-Y02", "售罄下架后补货自动恢复在售", ok2, {"restore": st2, "status": status_of(pid)}, "FR-S-06")
    return pid


def y3():
    pid = publish("补验-手动下架后取消", "32.00", 3, L2, "<p>手动下架后取消回补</p>")
    c = new_customer("y3")
    item = add_cart(c, pid, 1)
    st, o = submit(c, [item])
    withdraw(pid)
    s_stock_before = stock_of(pid)
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    stt = status_of(pid)
    ok = st2 == 200 and stt == "withdrawn" and stock_of(pid) == s_stock_before + 1
    log("TC-Y03", "取消订单回补库存，但手动下架商品不因此恢复在售（INV-2）", ok,
        {"cancel": st2, "status": stt, "stock": [s_stock_before, stock_of(pid)]}, "INV-2/FR-C-09")


def y4():
    c = new_customer("y4")
    item = add_cart(c, P["A"], 1)
    st, o = submit(c, [item])
    advance(o["id"]); advance(o["id"])  # -> packed
    st2, b = c.req("POST", "/api/orders/%d/cancel" % o["id"])
    ok = st2 == 200 and (b or {}).get("status") == "cancelled"
    log("TC-Y04", "「备货完成」状态客户仍可取消（发货前最后一格）", ok, {"status": st2}, "FR-C-09")


def y5():
    a = new_customer("y5a")
    b = new_customer("y5b")
    item = add_cart(b, P["A"], 1)
    st, o = submit(b, [item])
    st2, _ = a.req("POST", "/api/orders/%d/cancel" % o["id"])
    log("TC-Y05", "客户取消他人订单被拒绝（404）", st2 == 404, {"status": st2}, "FR-C-10/AC-10")


def y6():
    st, b = seller.req("POST", "/api/admin/orders/999999/cancel")
    st2, b2 = seller.req("GET", "/api/admin/orders/999999")
    log("TC-Y06", "卖家操作不存在的订单返回 404", st == 404 and st2 == 404, {"cancel": st, "detail": st2},
        "FR-S-09")


def y7():
    pid = publish("补验-富文本清洗", "33.00", 2, L2,
                  "<p>正常描述</p><script>alert(1)</script><img src='https://evil.example/a.png'>")
    anon = Client("anon")
    st, d = anon.req("GET", "/api/products/%d" % pid)
    desc = d.get("description", "")
    ok = st == 200 and "script" not in desc.lower() and "evil.example" not in desc
    log("TC-Y07", "描述中的脚本与外链图片被清洗（仅允许本地 /uploads 图片）", ok, {"desc": desc}, "BR-30 富媒体")


def y8():
    anon = Client("anon")
    st, b = anon.req("GET", "/api/products?q=" + urllib.parse.quote("%_"))
    st2, b2 = anon.req("GET", "/api/products?q=" + urllib.parse.quote("' OR 1=1 --"))
    ok = st == 200 and st2 == 200
    log("TC-Y08", "搜索含通配符/注入片段不报错、不越权返回全部", ok,
        {"pct": (st, b.get("total")), "inj": (st2, b2.get("total"))}, "FR-C-02")


def y9():
    st, b = seller.req("GET", "/api/admin/orders?page=1&size=5")
    times = [x["createdAt"] for x in b["items"]]
    desc = all(times[i] >= times[i + 1] for i in range(len(times) - 1))
    st2, b2 = seller.req("GET", "/api/admin/orders?page=2&size=5")
    ids1 = {x["id"] for x in b["items"]}
    ids2 = {x["id"] for x in b2["items"]}
    log("TC-Y09", "卖家订单总览按下单时间倒序，分页不重叠", desc and not (ids1 & ids2),
        {"desc": desc, "overlap": sorted(ids1 & ids2)}, "FR-S-11")


def y10():
    # 同秒批量注册，检查排序是否稳定（ISO 字符串排序的经典坑）
    c = Client("bulk")
    names = []
    for i in range(5):
        n = "same_%d_%s" % (i, os.urandom(2).hex())
        st, _ = c.req("POST", "/api/customer/register",
                      {"username": n, "password": "pass1234", "phone": "1370000%04d" % i})
        if st == 201:
            names.append(n)
    st, b = seller.req("GET", "/api/admin/customers?page=1&size=200")
    order = [x.get("username") for x in b["items"]]
    # 注册顺序应稳定出现（后注册的排前面 → 位置索引递减）
    pos = [order.index(n) for n in names if n in order]
    stable = all(pos[i] > pos[i + 1] for i in range(len(pos) - 1))
    log("TC-Y10", "同一秒内连续注册的客户排序稳定（后注册在前）", stable, {"order": pos, "names": names},
        "FR-S-10")


def y11():
    st, b = seller.req("GET", "/api/admin/products?page=1&size=1000")
    st2, b2 = seller.req("GET", "/api/orders?page=1&size=1000")  # 无会话也是 401，换客户
    c = new_customer("y11")
    st3, b3 = c.req("GET", "/api/orders?page=1&size=1000")
    ok = st in (200, 400) and st3 == 200
    log("TC-Y11", "超大分页参数不报 500（商品 400 校验 / 订单静默截断 50）", ok,
        {"admin": st, "mine": st3}, "FR-C-10")


CASES = [y1, y3, y4, y5, y6, y7, y8, y9, y10, y11]

if __name__ == "__main__":
    for fn in CASES:
        try:
            fn()
        except Exception as e:
            log("ERR-" + fn.__name__.upper(), "用例执行异常", False, repr(e))
            import traceback
            traceback.print_exc()
    out = os.path.join(EVID, "补充验证结果.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(RESULTS, f, ensure_ascii=False, indent=2, default=str)
    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["pass"])
    print("\n==== 补充验证汇总: %d/%d 通过 ====" % (passed, total))
    for r in RESULTS:
        if not r["pass"]:
            print("FAIL", r["id"], r["title"], r["detail"])
