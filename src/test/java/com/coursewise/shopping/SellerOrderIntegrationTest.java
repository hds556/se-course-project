package com.coursewise.shopping;

import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.repository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 挑战需求B · 开发B（14207 推进订单状态 / 14208 卖家取消订单 /
 * 14209 查看商品订单记录 / 14214 查看客户购买历史）集成测试。
 *
 * <p>只覆盖本人负责的卖家订单处理四条需求，不做全量回归；每条用例对应 SRS 里的一条验收标准。
 * 订单实体与买家端下单链路由开发 A 提供，本测试直接使用，属于依赖加载而非测试范围扩展。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SellerOrderIntegrationTest {

    static final Path TEMP;
    static {
        try { TEMP = Files.createTempDirectory("seller-order-integration-"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEMP.resolve("test.db"));
        registry.add("shopping.upload-dir", () -> TEMP.resolve("uploads").toString());
        registry.add("shopping.legacy-data-file", () -> TEMP.resolve("absent.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired CartItemRepository cartItems;
    @Autowired FavoriteRepository favorites;
    @Autowired CustomerRepository customers;

    Cookie seller;
    Cookie alice;
    Cookie bob;
    long leaf;

    @BeforeAll
    void loginSeller() throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        seller = response.getCookie("seller_session");
        assertNotNull(seller);
    }

    @BeforeEach
    void setUp() throws Exception {
        orderItems.deleteAll();
        orders.deleteAll();
        cartItems.deleteAll();
        favorites.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        customers.deleteAll();

        long parent = category("生活", null);
        leaf = category("文具", parent);
        alice = register("alice");
        bob = register("bob");
    }

    // ==================== 14207 推进订单状态 ====================

    @Test
    void advanceFollowsTheFiveStatusesOneStepAtATime() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        long orderId = submit(alice, product, 1);

        // 客户下单 → 商家确认 → 备货完成 → 开始发货 → 交易完成
        String[][] steps = {
                {"confirmed", "商家确认", "packed", "备货完成"},
                {"packed", "备货完成", "shipped", "开始发货"},
                {"shipped", "开始发货", "completed", "交易完成"},
        };
        for (String[] step : steps) {
            mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(step[0]))
                    .andExpect(jsonPath("$.statusLabel").value(step[1]))
                    .andExpect(jsonPath("$.nextStatus").value(step[2]))
                    .andExpect(jsonPath("$.nextStatusLabel").value(step[3]))
                    .andExpect(jsonPath("$.advanceable").value(true))
                    .andExpect(jsonPath("$.cancellable").value(true));
        }

        // 最后一步：开始发货 → 交易完成，此后不可再推进、不可取消
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.statusLabel").value("交易完成"))
                .andExpect(jsonPath("$.advanceable").value(false))
                .andExpect(jsonPath("$.cancellable").value(false));

        // 落库状态同步为「交易完成」，整单流转（明细不动）
        assertEquals(OrderStatus.completed, orders.findById(orderId).orElseThrow().getStatus());
        mvc.perform(get("/api/admin/orders/" + orderId).cookie(seller))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void advanceRefusesToSkipOrStayInPlace() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        long orderId = submit(alice, product, 1);

        // 跳过：客户下单 不能直接跳到「备货完成」
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller)
                        .contentType("application/json").content("{\"to\":\"packed\"}"))
                .andExpect(status().isConflict());
        // 原地：客户下单 → 客户下单
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller)
                        .contentType("application/json").content("{\"to\":\"placed\"}"))
                .andExpect(status().isConflict());
        // 非法状态码按 400 处理
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller)
                        .contentType("application/json").content("{\"to\":\"teleported\"}"))
                .andExpect(status().isBadRequest());
        // 三次非法请求都不应改动状态
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());

        // 显式指定「下一格」则允许，且只能一格
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller)
                        .contentType("application/json").content("{\"to\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    void advanceCannotGoBackOrUndoCompletion() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        long orderId = submit(alice, product, 1);
        setStatus(orderId, OrderStatus.shipped);

        // 回退：开始发货 不能退回「备货完成」
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller)
                        .contentType("application/json").content("{\"to\":\"packed\"}"))
                .andExpect(status().isConflict());
        assertEquals(OrderStatus.shipped, orders.findById(orderId).orElseThrow().getStatus());

        // 交易完成后不可撤销：既不能推进，也不能取消
        setStatus(orderId, OrderStatus.completed);
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel").cookie(seller))
                .andExpect(status().isConflict());
        assertEquals(OrderStatus.completed, orders.findById(orderId).orElseThrow().getStatus());

        // 已取消同样不可再推进
        setStatus(orderId, OrderStatus.cancelled);
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance").cookie(seller))
                .andExpect(status().isConflict());
    }

    @Test
    void advanceRequiresSellerLogin() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        long orderId = submit(alice, product, 1);
        mvc.perform(post("/api/admin/orders/" + orderId + "/advance")).andExpect(status().isUnauthorized());
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ==================== 14208 卖家取消订单 ====================

    @Test
    void sellerCancelRestoresStockAndKeepsOrderCancelled() throws Exception {
        long pen = publish("钢笔", "10.00", 5);
        long note = publish("本子", "20.00", 3);
        long orderId = submit(alice, List.of(Map.entry(pen, 2), Map.entry(note, 1)));

        assertEquals(3, products.findById(pen).orElseThrow().getStock());
        assertEquals(2, products.findById(note).orElseThrow().getStock());

        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.statusLabel").value("已取消"))
                .andExpect(jsonPath("$.advanceable").value(false))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.restoredQuantity").value(3))
                .andExpect(jsonPath("$.restored.length()").value(2));

        // 库存按明细数量逐项回补
        assertEquals(5, products.findById(pen).orElseThrow().getStock());
        assertEquals(3, products.findById(note).orElseThrow().getStock());
        assertEquals(OrderStatus.cancelled, orders.findById(orderId).orElseThrow().getStatus());
        // 订单与明细都不容物理删除，取消只是改状态
        assertEquals(2, orderItems.findByOrderIdOrderByIdAsc(orderId).size());
    }

    @Test
    void sellerCanCancelEvenAfterShippingButNotAfterCompletion() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 2);
        setStatus(orderId, OrderStatus.shipped);

        // 「开始发货」仍可取消（客户侧此时已不可取消，这是卖家侧的差异点）
        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        assertEquals(5, products.findById(product).orElseThrow().getStock(), "已发货取消同样回补库存");

        // 已取消不能重复取消
        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel").cookie(seller))
                .andExpect(status().isConflict());
        assertEquals(5, products.findById(product).orElseThrow().getStock(), "重复取消不得二次回补库存");
    }

    @Test
    void sellerCancelReListsDepletedProductThroughExistingStockInterface() throws Exception {
        long product = publish("只剩两件", "10.00", 2);
        long orderId = submit(alice, product, 2);
        assertEquals(0, products.findById(product).orElseThrow().getStock());
        // 模拟开发 D 的「库存归零自动下架」
        setProductStatus(product, ProductStatus.depleted);

        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored[0].status").value("active"))
                .andExpect(jsonPath("$.restored[0].statusLabel").value("在售"));

        ProductEntity restored = products.findById(product).orElseThrow();
        assertEquals(2, restored.getStock(), "库存回补走既有商品更新接口");
        assertEquals(ProductStatus.active, restored.getStatus(), "售罄下架的商品自动恢复在售");
    }

    @Test
    void sellerCancelRequiresLoginAndKnownOrder() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        mvc.perform(post("/api/admin/orders/" + orderId + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/orders/999999/cancel").cookie(seller)).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/orders/999999/advance").cookie(seller)).andExpect(status().isNotFound());
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ==================== 14209 查看商品订单记录 ====================

    @Test
    void productOrderRecordsListEveryOrderInvolvingThatProduct() throws Exception {
        long pen = publish("钢笔", "10.00", 20);
        long note = publish("本子", "20.00", 20);
        // alice：一笔只含钢笔，一笔钢笔 + 本子；bob：一笔只含本子
        long onlyPen = submit(alice, pen, 1);
        long mixed = submit(alice, List.of(Map.entry(pen, 2), Map.entry(note, 1)));
        submit(bob, note, 3);

        var body = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("productId", String.valueOf(pen))).andExpect(status().isOk()).andReturn());

        assertEquals(2, body.path("total").asInt(), "只返回涉及该商品的订单");
        assertEquals(2, body.path("items").size());
        // 按商品维度的顶部汇总：订单笔数 / 累计件数 / 当前库存与状态
        assertEquals(2, body.path("product").path("orderCount").asInt());
        assertEquals(3, body.path("product").path("totalQuantity").asInt());
        assertEquals("钢笔", body.path("product").path("name").asText());
        assertEquals(20 - 3, body.path("product").path("stock").asInt());
        assertEquals("active", body.path("product").path("status").asText());

        // 下单时间倒序：后提交的混合单在前，且命中的商品明细被标记
        assertEquals(mixed, body.path("items").get(0).path("id").asLong());
        assertEquals(onlyPen, body.path("items").get(1).path("id").asLong());
        assertTrue(body.path("items").get(0).path("items").get(0).path("highlight").asBoolean());
    }

    @Test
    void productOrderRecordsIncludeEveryStatusAndCanBePaged() throws Exception {
        long pen = publish("钢笔", "10.00", 30);
        long first = submit(alice, pen, 1);
        long second = submit(alice, pen, 1);
        long third = submit(bob, pen, 1);
        setStatus(first, OrderStatus.completed);
        setStatus(second, OrderStatus.cancelled);

        // 不按状态过滤：进行中 / 交易完成 / 已取消都要出现在商品订单记录里
        var all = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("productId", String.valueOf(pen)).param("size", "50")).andReturn());
        assertEquals(3, all.path("total").asInt());
        Set<String> statuses = new HashSet<>();
        all.path("items").forEach(node -> statuses.add(node.path("status").asText()));
        assertEquals(Set.of("placed", "completed", "cancelled"), statuses);

        // 分页
        var page1 = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("productId", String.valueOf(pen)).param("page", "1").param("size", "2")).andReturn());
        var page2 = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("productId", String.valueOf(pen)).param("page", "2").param("size", "2")).andReturn());
        assertEquals(2, page1.path("items").size());
        assertEquals(1, page2.path("items").size());
        assertEquals(2, page1.path("totalPages").asInt());
        assertEquals(third, all.path("items").get(0).path("id").asLong());
    }

    @Test
    void productOrderRecordsRequireLoginAndValidateInput() throws Exception {
        long pen = publish("钢笔", "10.00", 5);
        submit(alice, pen, 1);
        mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("productId", "999999")).andExpect(status().isNotFound());
        // 两个维度互斥，同时传视为误用
        mvc.perform(get("/api/admin/orders").cookie(seller)
                        .param("productId", String.valueOf(pen)).param("customerId", "1"))
                .andExpect(status().isBadRequest());
    }

    // ==================== 14214 查看客户购买历史 ====================

    @Test
    void customerPurchaseHistoryListsAllOrdersOfOneCustomer() throws Exception {
        long pen = publish("钢笔", "10.00", 20);
        long aliceFirst = submit(alice, pen, 1);
        long aliceSecond = submit(alice, pen, 2);
        submit(bob, pen, 3);
        setStatus(aliceFirst, OrderStatus.completed);

        var body = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("customerId", String.valueOf(customerId("alice")))).andExpect(status().isOk()).andReturn());

        assertEquals(2, body.path("total").asInt(), "只看该客户自己的订单");
        assertEquals("alice", body.path("customer").path("username").asText());
        // 下单时间倒序
        assertEquals(aliceSecond, body.path("items").get(0).path("id").asLong());
        assertEquals(aliceFirst, body.path("items").get(1).path("id").asLong());
        assertEquals("交易完成", body.path("items").get(1).path("statusLabel").asText());
        assertEquals("alice", body.path("items").get(0).path("customerName").asText());
    }

    @Test
    void customerPurchaseHistorySupportsAdvanceAndCancel() throws Exception {
        long pen = publish("钢笔", "10.00", 20);
        long toAdvance = submit(alice, pen, 1);
        long toCancel = submit(alice, pen, 2);
        var aliceId = String.valueOf(customerId("alice"));

        // 从「客户购买历史」入口直接推进 / 取消
        mvc.perform(post("/api/admin/orders/" + toAdvance + "/advance").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(post("/api/admin/orders/" + toCancel + "/cancel").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        var body = read(mvc.perform(get("/api/admin/orders").cookie(seller)
                .param("customerId", aliceId)).andReturn());
        assertEquals("商家确认", body.path("items").get(1).path("statusLabel").asText());
        assertEquals("已取消", body.path("items").get(0).path("statusLabel").asText());
        // 钢笔初始 20 件：推进的那笔已扣 1 件不再回补，取消的那笔回补 2 件 → 20 - 1 - 2 + 2 = 19
        assertEquals(19, products.findById(pen).orElseThrow().getStock(), "取消的那笔已回补 2 件");
    }

    @Test
    void customerPurchaseHistoryRequiresLoginAndKnownCustomer() throws Exception {
        mvc.perform(get("/api/admin/orders").param("customerId", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/orders").cookie(seller).param("customerId", "999999"))
                .andExpect(status().isNotFound());
    }

    // ==================== 辅助 ====================

    private JsonNode read(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Cookie register(String username) throws Exception {
        mvc.perform(post("/api/customer/register").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password1\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isCreated());
        var response = mvc.perform(post("/api/customer/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("customer_session");
        assertNotNull(cookie);
        return cookie;
    }

    private long customerId(String username) {
        return customers.findByUsername(username).orElseThrow().getId();
    }

    private long category(String name, Long parentId) throws Exception {
        var body = mapper.createObjectNode().put("name", name);
        if (parentId == null) body.putNull("parentId"); else body.put("parentId", parentId);
        return read(mvc.perform(post("/api/admin/categories").cookie(seller)
                        .contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long publish(String name, String price, int stock) throws Exception {
        return read(mvc.perform(multipart("/api/products").cookie(seller)
                        .param("name", name).param("price", price).param("stock", String.valueOf(stock))
                        .param("categoryId", String.valueOf(leaf)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long addCart(Cookie customer, long productId, int quantity) throws Exception {
        return read(mvc.perform(post("/api/cart").cookie(customer).contentType("application/json")
                        .content("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long submit(Cookie customer, long productId, int quantity) throws Exception {
        return submit(customer, List.of(Map.entry(productId, quantity)));
    }

    /** 一次提交多件商品，只生成 1 笔订单（买家端能力，本测试只作为下单手段）。 */
    private long submit(Cookie customer, List<Map.Entry<Long, Integer>> items) throws Exception {
        List<Long> cartIds = new ArrayList<>();
        for (Map.Entry<Long, Integer> item : items) cartIds.add(addCart(customer, item.getKey(), item.getValue()));
        return read(mvc.perform(post("/api/orders").cookie(customer).contentType("application/json")
                        .content("{\"cartItemIds\":" + cartIds + "}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private void setStatus(long orderId, OrderStatus status) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.saveAndFlush(order);
    }

    private void setProductStatus(long productId, ProductStatus status) {
        ProductEntity product = products.findById(productId).orElseThrow();
        product.setStatus(status);
        products.saveAndFlush(product);
    }
}
