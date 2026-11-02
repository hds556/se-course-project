package com.coursewise.shopping.review3;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP 边界回归。仅时间排序用例通过 JDBC 设置独立测试夹具的合法时间值。 */
public class BoundaryRegressionTest extends Review3Base {
    private static final String HUGE_PAGE = "?page=2147483647&size=50";

    @Test @DisplayName("R3-X001 | 客户历史极大正页码返回空页且不发生服务器错误")
    void x001() throws Exception {
        Client buyer = customer("page_buyer");
        expect(buyer.get("/api/customer/orders"), 200);
        JsonNode page = expect(buyer.get("/api/customer/orders" + HUGE_PAGE), 200).json;
        assertEquals(0, page.path("items").size());
        assertEquals(2147483647, page.path("page").asInt());
    }

    @Test @DisplayName("R3-X002 | 卖家客户列表极大正页码返回空页且不发生服务器错误")
    void x002() throws Exception {
        customer("page_directory");
        assertEquals(1, expect(seller.get("/api/admin/customers"), 200).json.path("total").asInt());
        JsonNode page = expect(seller.get("/api/admin/customers" + HUGE_PAGE), 200).json;
        assertEquals(0, page.path("items").size());
        assertEquals(2147483647, page.path("page").asInt());
    }

    @Test @DisplayName("R3-X003 | 卖家查询客户历史极大正页码返回空页")
    void x003() throws Exception {
        Client buyer = customer("page_seller_history");
        long customerId = id(expect(buyer.get("/api/customer/me"), 200).json);
        String path = "/api/admin/customers/" + customerId + "/orders";
        expect(seller.get(path), 200);
        JsonNode page = expect(seller.get(path + HUGE_PAGE), 200).json;
        assertEquals(0, page.path("items").size());
        assertEquals(2147483647, page.path("page").asInt());
    }

    @Test @DisplayName("R3-X004 | 小数商品ID不得截断后创建购买意向")
    void x004() throws Exception {
        Client buyer = customer("fraction_id");
        long productId = id(publish("小数ID保护", 2));
        Response response = buyer.post("/api/customer/intents",
                obj("productId", new BigDecimal(productId + ".9")));
        assertBadIdAndNoIntent(response, productId);
    }

    @Test @DisplayName("R3-X005 | 超long数字商品ID不得溢出为已存在商品ID")
    void x005() throws Exception {
        Client buyer = customer("overflow_number_id");
        long productId = id(publish("超long数字ID保护", 2));
        // longValue() 会把 2^64 + 合法ID 截断回合法ID；输入本身并非该商品的ID。
        BigInteger invalid = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(productId));
        Response response = buyer.post("/api/customer/intents", obj("productId", invalid));
        assertBadIdAndNoIntent(response, productId);
    }

    @Test @DisplayName("R3-X006 | 超long文本商品ID应返回参数错误而非服务器错误")
    void x006() throws Exception {
        Client buyer = customer("overflow_text_id");
        long productId = id(publish("超long文本ID保护", 2));
        String invalid = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(productId)).toString();
        Response response = buyer.post("/api/customer/intents", obj("productId", invalid));
        assertBadIdAndNoIntent(response, productId);
    }

    @Test @DisplayName("R3-X007 | 小数补货库存应拒绝且不得截断写入")
    void x007() throws Exception {
        long productId = depletedProduct();
        Response response = seller.post("/api/admin/products/" + productId + "/restock",
                obj("stock", new BigDecimal("1.5")));
        assertBadStockAndUnchanged(response, productId);
    }

    @Test @DisplayName("R3-X008 | 超Integer补货库存应返回400且库存不变")
    void x008() throws Exception {
        long productId = depletedProduct();
        Response response = seller.post("/api/admin/products/" + productId + "/restock",
                obj("stock", 2147483648L));
        assertBadStockAndUnchanged(response, productId);
    }

    @Test @DisplayName("R3-X009 | 客户目录按合法ISO时间的实际先后倒序")
    void x009() throws Exception {
        Client older = customer("iso_older");
        Client newer = customer("iso_newer");
        long olderId = id(expect(older.get("/api/customer/me"), 200).json);
        long newerId = id(expect(newer.get("/api/customer/me"), 200).json);
        jdbc.update("UPDATE customer SET registered_at=? WHERE id=?", "2026-10-30T08:00:00Z", olderId);
        jdbc.update("UPDATE customer SET registered_at=? WHERE id=?", "2026-10-30T08:00:00.100Z", newerId);
        JsonNode items = expect(seller.get("/api/admin/customers"), 200).json.path("items");
        assertEquals(2, items.size());
        assertEquals(newerId, id(items.get(0)), "实际较晚注册的客户应排第一");
        assertEquals(olderId, id(items.get(1)));
    }

    @Test @DisplayName("R3-X010 | 客户历史按合法ISO下单时间实际先后倒序")
    void x010() throws Exception {
        HistoryFixture fixture = historyFixture("iso_customer_history");
        JsonNode items = expect(fixture.buyer.get("/api/customer/orders"), 200).json.path("items");
        assertHistoryOrder(items, fixture);
    }

    @Test @DisplayName("R3-X011 | 卖家客户购买历史按实际下单时间倒序")
    void x011() throws Exception {
        HistoryFixture fixture = historyFixture("iso_seller_history");
        JsonNode items = expect(seller.get("/api/admin/customers/" + fixture.customerId + "/orders"), 200).json.path("items");
        assertHistoryOrder(items, fixture);
    }

    @Test @DisplayName("R3-X012 | 非整数文本商品ID应拒绝且不新增意向")
    void x012() throws Exception {
        Client buyer = customer("fraction_text_id");
        long productId = id(publish("非整数文本ID保护", 2));
        Response response = buyer.post("/api/customer/intents", obj("productId", productId + ".9"));
        assertBadIdAndNoIntent(response, productId);
    }

    private void assertBadIdAndNoIntent(Response response, long productId) {
        assertAll(
                () -> assertEquals(400, response.status, response.text),
                () -> assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM intent", Integer.class)),
                () -> assertEquals(2, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId)));
    }

    private long depletedProduct() throws Exception {
        JsonNode product = publish("补货边界商品", 2);
        long productId = id(product);
        JsonNode updated = expect(seller.patch("/api/admin/products/" + productId,
                obj("stock", 0, "categoryId", leafId, "version", product.path("version").asLong())), 200).json;
        assertEquals("depleted", updated.path("status").asText());
        assertEquals(0, updated.path("stock").asInt());
        return productId;
    }

    private void assertBadStockAndUnchanged(Response response, long productId) throws Exception {
        JsonNode product = expect(seller.get("/api/admin/products/" + productId), 200).json;
        assertAll(
                () -> assertEquals(400, response.status, response.text),
                () -> assertEquals(0, product.path("stock").asInt()),
                () -> assertEquals("depleted", product.path("status").asText()));
    }

    private class HistoryFixture {
        Client buyer;
        long customerId, olderId, newerId;
    }

    private HistoryFixture historyFixture(String username) throws Exception {
        HistoryFixture f = new HistoryFixture();
        f.buyer = customer(username);
        f.customerId = id(expect(f.buyer.get("/api/customer/me"), 200).json);
        long productId = id(publish("历史时间格式商品", 3));
        f.olderId = id(submit(f.buyer, productId));
        start(productId);
        fail(productId, "void");
        f.newerId = id(submit(f.buyer, productId));
        start(productId);
        fail(productId, "void");
        jdbc.update("UPDATE intent SET submitted_at=? WHERE id=?", "2026-10-30T08:00:00Z", f.olderId);
        jdbc.update("UPDATE intent SET submitted_at=? WHERE id=?", "2026-10-30T08:00:00.100Z", f.newerId);
        return f;
    }

    private void assertHistoryOrder(JsonNode items, HistoryFixture fixture) {
        assertEquals(2, items.size());
        assertEquals(fixture.newerId, id(items.get(0)), "100毫秒后的记录应在整秒记录之前");
        assertEquals(fixture.olderId, id(items.get(1)));
    }
}
