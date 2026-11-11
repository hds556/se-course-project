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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 挑战需求B · 开发A（14201 收藏 / 14202 购物车 / 14203 提交订单 / 14204 客户取消 / 14205 我的订单）集成测试。
 *
 * <p>只覆盖本人负责的买家端闭环，不做全量回归；每条用例对应 SRS 中的一条验收标准。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderIntegrationTest {

    static final Path TEMP;
    static {
        try { TEMP = Files.createTempDirectory("order-integration-"); }
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

    // ==================== 14201 收藏商品 ====================

    @Test
    void notLoggedInCannotFavorite() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/favorites/" + product)).andExpect(status().isUnauthorized());
    }

    @Test
    void favoriteIsIdempotentAndRemovable() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/favorites/" + product).cookie(alice)).andExpect(status().isCreated());
        }
        // 重复收藏不产生第二条记录
        assertEquals(1, favorites.count());
        mvc.perform(get("/api/favorites").cookie(alice))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].available").value(true));

        mvc.perform(delete("/api/favorites/" + product).cookie(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/favorites").cookie(alice)).andExpect(jsonPath("$.items.length()").value(0));
        // 取消不存在的收藏同样幂等成功
        mvc.perform(delete("/api/favorites/" + product).cookie(alice)).andExpect(status().isOk());
    }

    @Test
    void offlineProductStaysInFavoritesAndIsMarked() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        mvc.perform(post("/api/favorites/" + product).cookie(alice)).andExpect(status().isCreated());
        offline(product);

        mvc.perform(get("/api/favorites").cookie(alice))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[0].statusLabel").value("已下架"));
    }

    // ==================== 14202 购物车管理 ====================

    @Test
    void notLoggedInCannotUseCart() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart").contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void addMergeUpdateAndRemoveKeepOneRow() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long first = read(mvc.perform(post("/api/cart").cookie(alice).contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":2}")).andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
        long second = read(mvc.perform(post("/api/cart").cookie(alice).contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":1}")).andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
        assertEquals(first, second, "同一商品在购物车中只有一行");
        mvc.perform(get("/api/cart").cookie(alice))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));

        mvc.perform(patch("/api/cart/" + first).cookie(alice).contentType("application/json")
                .content("{\"quantity\":4}")).andExpect(status().isOk());
        mvc.perform(get("/api/cart").cookie(alice)).andExpect(jsonPath("$.items[0].quantity").value(4));

        // 数量下限为 1
        mvc.perform(patch("/api/cart/" + first).cookie(alice).contentType("application/json")
                .content("{\"quantity\":0}")).andExpect(status().isBadRequest());

        mvc.perform(delete("/api/cart/" + first).cookie(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/cart").cookie(alice)).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void addingToCartDoesNotTouchStock() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        mvc.perform(post("/api/cart").cookie(alice).contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":3}")).andExpect(status().isCreated());
        assertEquals(5, products.findById(product).orElseThrow().getStock());
    }

    @Test
    void offlineProductStaysInCartButCannotBeAddedOrOrdered() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long itemId = read(mvc.perform(post("/api/cart").cookie(alice).contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":1}")).andReturn()).path("id").asLong();
        offline(product);

        mvc.perform(get("/api/cart").cookie(alice))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].available").value(false));
        // 已下架商品不能再加购
        mvc.perform(post("/api/cart").cookie(alice).contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":1}")).andExpect(status().isConflict());
        // 下架后也不能下单
        mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + itemId + "]}")).andExpect(status().isConflict());
    }

    // ==================== 14203 提交订单 ====================

    @Test
    void submitTwoProductsGeneratesOneOrderWithTwoItemsAndDeductsStock() throws Exception {
        long a = publish("钢笔", "10.00", 5);
        long b = publish("本子", "20.50", 3);
        List<Long> ids = List.of(addCart(alice, a, 2), addCart(alice, b, 1));

        var order = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":" + ids + "}")).andExpect(status().isCreated()).andReturn());
        assertEquals(1, orders.count(), "多件商品只生成 1 笔订单");
        assertEquals(2, order.path("items").size());
        assertEquals("placed", order.path("status").asText());
        // 总金额 = Σ(单价 × 数量) = 10*2 + 20.5*1
        assertEquals(4050, order.path("totalCents").asLong());
        assertEquals(3, products.findById(a).orElseThrow().getStock());
        assertEquals(2, products.findById(b).orElseThrow().getStock());
        assertEquals(0, cartItems.count(), "下单成功后选中项删除");
    }

    @Test
    void stockShortageRejectsOnlyThatItemAndLeavesOthersOrderable() throws Exception {
        long enough = publish("钢笔", "10.00", 5);
        long shortage = publish("本子", "20.00", 1);
        long enoughItem = addCart(alice, enough, 2);
        long shortageItem = addCart(alice, shortage, 3);

        var body = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + enoughItem + "," + shortageItem + "]}")).andReturn());
        assertEquals(1, orders.count());
        assertEquals(1, body.path("items").size());
        assertTrue(body.path("rejectedItems").has(String.valueOf(shortage)), "缺货商品应逐项提示");

        assertEquals(3, products.findById(enough).orElseThrow().getStock());
        assertEquals(1, products.findById(shortage).orElseThrow().getStock(), "缺货商品库存不变");
        // 只删除成功下单的购物车项，缺货项仍保留
        assertTrue(cartItems.findById(shortageItem).isPresent());
        assertTrue(cartItems.findById(enoughItem).isEmpty());
    }

    @Test
    void allItemsUnavailableGeneratesNoOrderAndReturnsPerItemErrors() throws Exception {
        long product = publish("钢笔", "10.00", 1);
        long item = addCart(alice, product, 5);
        var response = mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        var body = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}")).andReturn());
        assertTrue(body.path("itemErrors").has(String.valueOf(product)),
                "全部不可下单时应逐商品提示，实际响应: " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, orders.count());
        assertEquals(1, products.findById(product).orElseThrow().getStock());
    }

    @Test
    void onlySelectedCartItemsAreOrderedAndRemoved() throws Exception {
        long a = publish("钢笔", "10.00", 5);
        long b = publish("本子", "20.00", 5);
        long picked = addCart(alice, a, 1);
        addCart(alice, b, 1);

        mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + picked + "]}")).andExpect(status().isCreated());
        assertEquals(1, cartItems.count(), "未勾选的购物车项保留");
        assertTrue(cartItems.findById(picked).isEmpty());
    }

    @Test
    void orderKeepsSnapshotAfterPriceChange() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long item = addCart(alice, product, 2);
        var order = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}")).andReturn());
        long orderId = order.path("id").asLong();

        ProductEntity entity = products.findById(product).orElseThrow();
        entity.setPriceCents(12000);
        products.saveAndFlush(entity);

        mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(1000))
                .andExpect(jsonPath("$.totalCents").value(2000));
    }

    @Test
    void concurrentSubmitNeverOversells() throws Exception {
        long product = publish("限量笔", "10.00", 5);
        // 8 位客户各买 1 件，库存只有 5 → 恰好 5 单成功，库存不为负
        Cookie[] buyers = new Cookie[8];
        long[] itemIds = new long[8];
        for (int i = 0; i < 8; i++) {
            buyers[i] = register("buyer" + i);
            itemIds[i] = addCart(buyers[i], product, 1);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/orders").cookie(buyers[index]).contentType("application/json")
                        .content("{\"cartItemIds\":[" + itemIds[index] + "]}")).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<Integer> future : results) if (future.get() == 201) success++;
        pool.shutdown();

        assertEquals(5, success, "库存 5 件只能成功 5 单");
        assertEquals(0, products.findById(product).orElseThrow().getStock());
        assertTrue(products.findById(product).orElseThrow().getStock() >= 0, "库存不得为负");
    }

    @Test
    void emptySelectionIsRejected() throws Exception {
        mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[]}")).andExpect(status().isBadRequest());
        assertEquals(0, orders.count());
    }

    // ==================== 14204 客户取消订单 ====================

    @Test
    void cancelRestoresStockAndRelistsDepletedProduct() throws Exception {
        long product = publish("只剩一件", "10.00", 1);
        long item = addCart(alice, product, 1);
        long orderId = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}")).andReturn()).path("id").asLong();
        // 下单占满库存 → 商品因售罄下架
        assertEquals(0, products.findById(product).orElseThrow().getStock());

        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        ProductEntity restored = products.findById(product).orElseThrow();
        assertEquals(1, restored.getStock(), "库存按数量回补");
        assertEquals(ProductStatus.active, restored.getStatus(), "售罄下架的商品自动恢复在售");
        mvc.perform(get("/api/orders").cookie(alice))
                .andExpect(jsonPath("$.items[0].status").value("cancelled"));
    }

    @Test
    void cannotCancelAfterShipping() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long item = addCart(alice, product, 1);
        long orderId = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}")).andReturn()).path("id").asLong();

        for (OrderStatus status : List.of(OrderStatus.confirmed, OrderStatus.packed)) {
            setStatus(orderId, status);
        }
        // 「备货完成」仍可取消
        mvc.perform(get("/api/orders").cookie(alice)).andExpect(jsonPath("$.items[0].cancellable").value(true));

        setStatus(orderId, OrderStatus.shipped);
        mvc.perform(get("/api/orders").cookie(alice)).andExpect(jsonPath("$.items[0].cancellable").value(false));
        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(alice)).andExpect(status().isConflict());
        assertEquals(ProductStatus.active, products.findById(product).orElseThrow().getStatus());
        assertEquals(4, products.findById(product).orElseThrow().getStock());
    }

    @Test
    void cannotCancelOthersOrAlreadyCancelledOrder() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long item = addCart(alice, product, 1);
        long orderId = read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json")
                .content("{\"cartItemIds\":[" + item + "]}")).andReturn()).path("id").asLong();

        // 越权：bob 不能取消 alice 的订单，也不能查到
        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + orderId).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").cookie(bob)).andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(alice)).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(alice)).andExpect(status().isConflict());
    }

    // ==================== 14205 查看我的订单 ====================

    @Test
    void myOrdersShowAllStatusesNewestFirstWithItems() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        long first = submit(alice, product, 1);
        long second = submit(alice, product, 2);
        setStatus(first, OrderStatus.completed);
        setStatus(second, OrderStatus.cancelled);

        mvc.perform(get("/api/orders").cookie(alice))
                .andExpect(jsonPath("$.total").value(2))
                // 按下单时间倒序：第二笔在前
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[0].statusLabel").value("已取消"))
                .andExpect(jsonPath("$.items[1].statusLabel").value("交易完成"))
                .andExpect(jsonPath("$.items[0].items[0].productName").value("钢笔"))
                .andExpect(jsonPath("$.items[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].orderNo").exists());
    }

    @Test
    void myOrdersArePagedAndIsolatedPerCustomer() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        for (int i = 0; i < 12; i++) submit(alice, product, 1);
        submit(bob, product, 1);

        mvc.perform(get("/api/orders").cookie(alice).param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/orders").cookie(alice).param("page", "2").param("size", "10"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/orders").cookie(bob)).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void myOrdersRequireLogin() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    // ==================== 辅助 ====================

    private record Rendered(MvcResult result) {}

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
        long item = addCart(customer, productId, quantity);
        return read(mvc.perform(post("/api/orders").cookie(customer).contentType("application/json")
                        .content("{\"cartItemIds\":[" + item + "]}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    /** 把商品置为「已售罄下架」，模拟卖家调库存/订单占满后的状态。 */
    private void offline(long productId) {
        ProductEntity entity = products.findById(productId).orElseThrow();
        entity.setStatus(ProductStatus.depleted);
        products.saveAndFlush(entity);
    }

    private void setStatus(long orderId, OrderStatus status) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.saveAndFlush(order);
    }
}
