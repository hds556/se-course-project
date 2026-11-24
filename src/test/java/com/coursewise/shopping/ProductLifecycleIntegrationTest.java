package com.coursewise.shopping;

import com.coursewise.shopping.repository.CartItemRepository;
import com.coursewise.shopping.repository.CategoryRepository;
import com.coursewise.shopping.repository.CustomerRepository;
import com.coursewise.shopping.repository.FavoriteRepository;
import com.coursewise.shopping.repository.OrderItemRepository;
import com.coursewise.shopping.repository.OrderRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 挑战需求 B：卖家库存、商品上下架与现有订单的联动。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductLifecycleIntegrationTest {
    private static final Path TEMP;
    static {
        try {
            TEMP = Files.createTempDirectory("product-lifecycle-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEMP.resolve("test.db"));
        registry.add("shopping.upload-dir", () -> TEMP.resolve("uploads").toString());
        registry.add("shopping.legacy-data-file", () -> TEMP.resolve("absent.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired CustomerRepository customers;
    @Autowired CartItemRepository cartItems;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired FavoriteRepository favorites;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataMigrationRunner migration;

    private Cookie seller;
    private long leaf;

    @BeforeAll
    void loginSeller() throws Exception {
        seller = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("seller_session");
        assertNotNull(seller);
    }

    @BeforeEach
    void reset() throws Exception {
        orderItems.deleteAll();
        orders.deleteAll();
        cartItems.deleteAll();
        favorites.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        customers.deleteAll();
        leaf = addCategory("测试二级类别", addCategory("测试一级类别", null));
    }

    @Test
    void zeroStockMovesProductToOfflineHistoryAndPositiveStockRestoresVisibility() throws Exception {
        long cup = publish("蓝杯", 4);
        long pen = publish("钢笔", 2);
        assertTrue(containsId(onSale(), cup));
        assertTrue(containsId(onSale(), pen));
        assertTrue(offline().isEmpty());

        editStock(cup, "{\"stock\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0)).andExpect(jsonPath("$.status").value("depleted"));
        assertUnavailable(cup, "蓝杯");
        assertTrue(containsId(onSale(), pen), "同一目录的其他商品应继续在售");
        assertOffline(cup, "depleted", "sold_out", 0);

        editStock(cup, "{\"stock\":6}").andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(6)).andExpect(jsonPath("$.status").value("active"));
        assertAvailable(cup, "蓝杯", 6);
    }

    @Test
    void manualWithdrawalKeepsStockAndStaysOfflineAfterStockIsChanged() throws Exception {
        long cup = publish("手动下架杯", 4);
        mvc.perform(post(adminProduct(cup) + "/withdraw").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("withdrawn")).andExpect(jsonPath("$.stock").value(4));
        assertUnavailable(cup, "手动下架杯");
        String offlineAt = assertOffline(cup, "withdrawn", "manual", 4).path("offlineAt").asText();

        editStock(cup, "{\"stock\":10}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("withdrawn")).andExpect(jsonPath("$.stock").value(10));
        assertUnavailable(cup, "手动下架杯");
        assertEquals(offlineAt, assertOffline(cup, "withdrawn", "manual", 10).path("offlineAt").asText(),
                "调整库存不能重写手动下架历史时间");
    }

    @ParameterizedTest(name = "关闭订单时按下架原因恢复商品：卖家取消={0}")
    @ValueSource(booleans = {false, true})
    void customerOrSellerCancellationOnlyRelistsSoldOutItems(boolean sellerCancels) throws Exception {
        Cookie buyer = registerBuyer();
        long soldOutProduct = publish("订单售罄本", 2);
        long manualProduct = publish("订单手动下架杯", 5);
        long orderId = placeOrder(buyer, addCart(buyer, soldOutProduct, 2), addCart(buyer, manualProduct, 3));
        JsonNode before = read(mvc.perform(get("/api/orders/{id}", orderId).cookie(buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("placed")));
        assertEquals(2, before.path("items").size());
        assertUnavailable(soldOutProduct, "订单售罄本");
        assertOffline(soldOutProduct, "depleted", "sold_out", 0);
        assertAvailable(manualProduct, "订单手动下架杯", 2);

        mvc.perform(post(adminProduct(manualProduct) + "/withdraw").cookie(seller))
                .andExpect(status().isOk());
        assertUnavailable(manualProduct, "订单手动下架杯");
        String manualAt = assertOffline(manualProduct, "withdrawn", "manual", 2).path("offlineAt").asText();
        JsonNode stillPlaced = read(mvc.perform(get("/api/orders/{id}", orderId).cookie(buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("placed")));
        assertEquals(before.path("items"), stillPlaced.path("items"), "下架不能修改订单明细");

        String cancelPath = sellerCancels ? "/api/admin/orders/{id}/cancel" : "/api/orders/{id}/cancel";
        mvc.perform(post(cancelPath, orderId).cookie(sellerCancels ? seller : buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        assertAvailable(soldOutProduct, "订单售罄本", 2);
        assertUnavailable(manualProduct, "订单手动下架杯");
        assertEquals(manualAt, assertOffline(manualProduct, "withdrawn", "manual", 5).path("offlineAt").asText());
        JsonNode cancelled = read(mvc.perform(get("/api/orders/{id}", orderId).cookie(buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled")));
        assertEquals(before.path("items"), cancelled.path("items"), "取消只改变订单状态，不删除明细");
    }

    @Test
    void sellerZeroingStockLeavesTwoExistingOrdersAvailableToAdvance() throws Exception {
        Cookie buyer = registerBuyer();
        long product = publish("已下单的马克杯", 3);
        long orderId = placeOrder(buyer, addCart(buyer, product, 1));
        long secondOrder = placeOrder(buyer, addCart(buyer, product, 1));
        JsonNode before = read(mvc.perform(get("/api/admin/orders/{id}", orderId).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("placed")));
        editStock(product, "{\"stock\":0}").andExpect(status().isOk());
        assertOffline(product, "depleted", "sold_out", 0);
        assertUnavailable(product, "已下单的马克杯");
        JsonNode after = read(mvc.perform(get("/api/admin/orders/{id}", orderId).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("placed")));
        assertEquals(before.path("items"), after.path("items"), "库存调零不能改变已创建订单的明细");
        mvc.perform(post("/api/admin/orders/{id}/advance", orderId).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(post("/api/admin/orders/{id}/advance", secondOrder).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    void manuallyWithdrawnProductOrderCanCompleteWithoutChangingStockAgain() throws Exception {
        Cookie buyer = registerBuyer();
        long product = publish("下架后交付", 3);
        long orderId = placeOrder(buyer, addCart(buyer, product, 1));
        mvc.perform(post(adminProduct(product) + "/withdraw").cookie(seller))
                .andExpect(status().isOk());
        for (String nextStatus : new String[]{"confirmed", "packed", "shipped", "completed"}) {
            mvc.perform(post("/api/admin/orders/{id}/advance", orderId).cookie(seller))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(nextStatus));
        }
        assertOffline(product, "withdrawn", "manual", 2);
    }

    @Test
    void historySortsByActualOfflineTimeAndNormalizesExistingStates() throws Exception {
        long newer = publish("较晚下架", 4);
        long older = publish("较早下架", 4);
        long previouslyFrozen = publish("旧冻结", 4);
        jdbc.update("UPDATE product SET status='sold', stock=0, result_at=? WHERE id=?",
                "2026-11-10T08:00:00.100Z", newer);
        jdbc.update("UPDATE product SET status='withdrawn', result_at=? WHERE id=?",
                "2026-11-10T08:00:00Z", older);
        jdbc.update("UPDATE product SET status='frozen', trading_intent_id=123 WHERE id=?", previouslyFrozen);
        migration.run(new DefaultApplicationArguments());

        JsonNode history = offline();
        assertEquals(2, history.size());
        assertEquals(newer, history.get(0).path("id").asLong());
        assertEquals("sold_out", history.get(0).path("offlineReason").asText());
        assertEquals(older, history.get(1).path("id").asLong());
        assertEquals("manual", history.get(1).path("offlineReason").asText());
        assertAvailable(previouslyFrozen, "旧冻结", 4);
        migration.run(new DefaultApplicationArguments());
        assertEquals(history, offline(), "重复启动不会重写历史时间或版本");
    }

    @Test
    void largeStockAdjustmentAndCancellationDoNotOverflow() throws Exception {
        Cookie buyer = registerBuyer();
        long product = publish("库存回补边界商品", 2);
        long orderId = placeOrder(buyer, addCart(buyer, product, 1));
        editStock(product, "{\"stock\":2147483648}").andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(2147483648L));

        mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        JsonNode restored = adminDetail(product);
        assertEquals(2147483649L, restored.path("stock").longValue(), "回补数量不能在 int 上限处溢出或丢失");
        assertEquals("active", restored.path("status").asText());
        assertTrue(containsId(onSale(), product));
        assertFalse(containsId(offline(), product));
    }

    @Test
    void stockWithdrawalAndOfflineListRequireSellerSession() throws Exception {
        Cookie buyer = registerBuyer();
        long product = publish("卖家权限商品", 3);
        mvc.perform(get("/api/admin/products/offline")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/products/offline").cookie(buyer)).andExpect(status().isUnauthorized());
        mvc.perform(patch(adminProduct(product) + "/stock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stock\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(patch(adminProduct(product) + "/stock").cookie(buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stock\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(post(adminProduct(product) + "/withdraw")).andExpect(status().isUnauthorized());
        mvc.perform(post(adminProduct(product) + "/withdraw").cookie(buyer)).andExpect(status().isUnauthorized());
        assertAvailable(product, "卖家权限商品", 3);
    }

    @Test
    void invalidStockAndVersionValuesAreRejectedWithoutChangingTheProduct() throws Exception {
        long product = publish("库存类型校验", 3);
        JsonNode before = adminDetail(product);
        for (String body : new String[]{"{}", "{\"stock\":null}", "{\"stock\":-1}", "{\"stock\":1.5}",
                "{\"stock\":9007199254740992}", "{\"stock\":\"7\"}", "{\"stock\":true}", "{\"stock\":[]}",
                "{\"stock\":0,\"version\":1.5}", "{\"stock\":0,\"version\":9223372036854775808}"}) {
            editStock(product, body).andExpect(status().isBadRequest());
        }
        assertEquals(before, adminDetail(product), "拒绝的请求不得改动商品及版本");
        assertAvailable(product, "库存类型校验", 3);
    }

    @Test
    void staleStockAndWithdrawalVersionsDoNotOverrideACommittedChange() throws Exception {
        long product = publish("版本冲突商品", 3);
        long first = adminDetail(product).path("version").asLong();
        editStock(product, "{\"stock\":4,\"version\":" + first + "}").andExpect(status().isOk());
        long current = adminDetail(product).path("version").asLong();
        assertTrue(current > first, "一次成功的库存修改必须使旧版本失效");

        editStock(product, "{\"stock\":0,\"version\":" + first + "}").andExpect(status().isConflict());
        mvc.perform(post(adminProduct(product) + "/withdraw").cookie(seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + first + "}"))
                .andExpect(status().isConflict());
        assertAvailable(product, "版本冲突商品", 4);

        mvc.perform(post(adminProduct(product) + "/withdraw").cookie(seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + current + "}"))
                .andExpect(status().isOk());
        assertOffline(product, "withdrawn", "manual", 4);
    }

    @Test
    void oldIntentEntryPointsAreGoneAndCannotFreezeAProduct() throws Exception {
        Cookie buyer = registerBuyer();
        long product = publish("新订单在售商品", 3);
        mvc.perform(post("/api/customer/intents").cookie(buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product + "}"))
                .andExpect(status().isGone());
        mvc.perform(post("/api/admin/trades/{id}/start", product).cookie(seller))
                .andExpect(status().isGone());
        mvc.perform(post("/api/admin/freeze").cookie(seller)).andExpect(status().isGone());
        assertAvailable(product, "新订单在售商品", 3);
    }

    private String adminProduct(long id) {
        return "/api/admin/products/" + id;
    }

    private ResultActions editStock(long id, String body) throws Exception {
        return mvc.perform(patch(adminProduct(id) + "/stock").cookie(seller)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode adminDetail(long id) throws Exception {
        return read(mvc.perform(get(adminProduct(id)).cookie(seller)).andExpect(status().isOk()));
    }

    private JsonNode onSale() throws Exception {
        return read(mvc.perform(get("/api/admin/products").cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.items").isArray())).path("items");
    }

    private JsonNode offline() throws Exception {
        JsonNode items = read(mvc.perform(get("/api/admin/products/offline").cookie(seller)).andExpect(status().isOk()));
        assertTrue(items.isArray(), "下架历史应返回数组");
        return items;
    }

    private JsonNode assertOffline(long id, String expectedStatus, String reason, int stock) throws Exception {
        JsonNode match = null;
        for (JsonNode item : offline()) {
            if (item.path("id").asLong() == id) {
                assertNull(match, "同一商品不应多次出现在下架历史中");
                match = item;
            }
        }
        assertNotNull(match, "下架历史缺少商品 " + id);
        assertEquals(expectedStatus, match.path("status").asText());
        assertEquals(reason, match.path("offlineReason").asText());
        assertEquals(stock, match.path("stock").asInt());
        assertFalse(match.path("offlineAt").asText("").isBlank(), "下架历史应带下架时间");
        return match;
    }

    private void assertUnavailable(long id, String search) throws Exception {
        assertFalse(containsId(onSale(), id), "卖家当前在售列表不应包含已下架商品");
        assertFalse(containsId(read(mvc.perform(get("/api/products")).andExpect(status().isOk())).path("items"), id));
        mvc.perform(get("/api/products").param("q", search)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/products/{id}", id)).andExpect(status().isNotFound());
    }

    private void assertAvailable(long id, String search, int stock) throws Exception {
        assertTrue(containsId(onSale(), id), "在售商品应出现在卖家当前列表");
        assertFalse(containsId(offline(), id), "在售商品不应继续出现在下架历史中");
        mvc.perform(get("/api/products").param("q", search)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/products/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(stock));
        assertEquals("active", adminDetail(id).path("status").asText());
    }

    private boolean containsId(JsonNode items, long id) {
        for (JsonNode item : items) if (item.path("id").asLong() == id) return true;
        return false;
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private long addCategory(String name, Long parentId) throws Exception {
        var body = mapper.createObjectNode().put("name", name);
        if (parentId == null) body.putNull("parentId"); else body.put("parentId", parentId);
        return read(mvc.perform(post("/api/admin/categories").cookie(seller)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated())).path("id").asLong();
    }

    private long publish(String name, int stock) throws Exception {
        long id = read(mvc.perform(multipart("/api/products").cookie(seller).param("name", name)
                        .param("price", "12.50").param("stock", String.valueOf(stock))
                        .param("categoryId", String.valueOf(leaf)))
                .andExpect(status().isCreated())).path("id").asLong();
        assertTrue(id > 0);
        return id;
    }

    private Cookie registerBuyer() throws Exception {
        mvc.perform(post("/api/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_buyer\",\"password\":\"testpass123\",\"phone\":\"13800000000\",\"defaultPlace\":\"杭州市测试收货地址\"}"))
                .andExpect(status().isCreated());
        Cookie buyer = mvc.perform(post("/api/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_buyer\",\"password\":\"testpass123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("customer_session");
        assertNotNull(buyer);
        return buyer;
    }

    private long addCart(Cookie buyer, long id, int quantity) throws Exception {
        return read(mvc.perform(post("/api/cart").cookie(buyer).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("productId", id).put("quantity", quantity).toString()))
                .andExpect(status().isCreated())).path("id").asLong();
    }

    private long placeOrder(Cookie buyer, long... cartIds) throws Exception {
        var body = mapper.createObjectNode();
        var items = body.putArray("cartItemIds");
        for (long id : cartIds) items.add(id);
        return read(mvc.perform(post("/api/orders").cookie(buyer).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())).path("id").asLong();
    }
}
