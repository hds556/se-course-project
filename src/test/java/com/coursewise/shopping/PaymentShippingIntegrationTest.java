package com.coursewise.shopping;

import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.order.PaymentTimeoutJob;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 挑战需求C · 开发A（14257 支付订单 / 14258 确认收货 / 14259 查询物流）集成测试。
 *
 * <p>每条用例对应 SRS FR-C-09 / FR-C-11 / FR-C-12 的一条验收标准，不做全量回归。
 * 下单、卖家推进等前置动作直接调用既有接口或改动数据库状态，属于场景搭建而非测试范围。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentShippingIntegrationTest {

    static final Path TEMP;
    static {
        try { TEMP = Files.createTempDirectory("payment-shipping-"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEMP.resolve("test.db"));
        registry.add("shopping.upload-dir", () -> TEMP.resolve("uploads").toString());
        registry.add("shopping.legacy-data-file", () -> TEMP.resolve("absent.json").toString());
        // 测试中手动触发扫描，关闭定时任务干扰
        registry.add("shopping.payment.timeout-scan-initial-delay-ms", () -> "86400000");
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
    @Autowired PaymentTimeoutJob timeoutJob;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;

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

    // ==================== 14257 支付订单 ====================

    /** 验收 1：「客户下单」订单发起支付后状态为「待支付」。 */
    @Test
    void startingPaymentMovesPlacedOrderToPendingPayment() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 2);

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"alipay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_payment"))
                .andExpect(jsonPath("$.statusLabel").value("待支付"))
                .andExpect(jsonPath("$.payChannel").value("alipay"))
                .andExpect(jsonPath("$.amountCents").value(2000));

        mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                .andExpect(jsonPath("$.status").value("pending_payment"))
                .andExpect(jsonPath("$.payable").value(true));
    }

    /** 验收 1 补充：四种渠道都要支持，非法渠道拒绝。 */
    @Test
    void allFourChannelsAreSupportedAndUnknownChannelIsRejected() throws Exception {
        long product = publish("钢笔", "10.00", 10);
        Map<String, String> labels = Map.of(
                "bank_card", "银行卡", "credit_card", "信用卡",
                "alipay", "支付宝", "wechat", "微信支付");
        for (Map.Entry<String, String> channel : labels.entrySet()) {
            long orderId = submit(alice, product, 1);
            mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                            .content("{\"payChannel\":\"" + channel.getKey() + "\",\"success\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payChannel").value(channel.getKey()))
                    .andExpect(jsonPath("$.payChannelLabel").value(channel.getValue()))
                    .andExpect(jsonPath("$.status").value("paid"));
        }

        long bad = submit(alice, product, 1);
        mvc.perform(post("/api/orders/" + bad + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"bitcoin\"}"))
                .andExpect(status().isBadRequest());
        // 未选渠道同样拒绝
        mvc.perform(post("/api/orders/" + bad + "/pay").cookie(alice).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(OrderStatus.placed, orders.findById(bad).orElseThrow().getStatus());
    }

    /** 验收 2：支付成功后状态「已支付」，订单上显示所选渠道。 */
    @Test
    void successfulPaymentMarksPaidAndRecordsChannel() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 3);

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"wechat\",\"success\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.statusLabel").value("已支付"))
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.amountCents").value(3000));

        OrderEntity order = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.paid, order.getStatus());
        assertEquals(PayChannel.wechat, order.getPayChannel(), "支付渠道落库，供原路退款使用");

        mvc.perform(get("/api/orders").cookie(alice))
                .andExpect(jsonPath("$.items[0].payChannel").value("wechat"))
                .andExpect(jsonPath("$.items[0].payChannelLabel").value("微信支付"));
    }

    /** 验收 3：模拟支付失败后订单仍为「待支付」，可再次发起支付并成功。 */
    @Test
    void failedPaymentStaysPendingAndCanRetry() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"alipay\",\"success\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.status").value("pending_payment"))
                .andExpect(jsonPath("$.statusLabel").value("待支付"));

        // 失败不设独立状态、不影响库存
        assertEquals(OrderStatus.pendingPayment, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(4, products.findById(product).orElseThrow().getStock());

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"credit_card\",\"success\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.payChannel").value("credit_card"));
    }

    /** 验收 4：对同一订单重复发起支付，不产生重复扣款记录（幂等）。 */
    @Test
    void repeatedPaymentIsIdempotent() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);

        for (int i = 0; i < 3; i++) {
            var body = read(mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice)
                    .contentType("application/json").content("{\"payChannel\":\"wechat\",\"success\":true}"))
                    .andExpect(status().isOk()).andReturn());
            assertEquals("paid", body.path("status").asText());
            if (i > 0) {
                assertTrue(body.path("idempotent").asBoolean(), "重复支付应按幂等返回");
                assertTrue(body.path("message").asText().contains("无需重复支付"));
            }
        }
        // 只有一笔订单、一个渠道，金额不变
        assertEquals(1, orders.count());
        OrderEntity order = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.paid, order.getStatus());
        assertEquals(PayChannel.wechat, order.getPayChannel());
        assertEquals(1000, order.getTotalCents());
    }

    /** 验收 4 补充：并发重复支付只生效一次，且不产生错误状态。 */
    @Test
    void concurrentPaymentsOnlySettleOnce() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice)
                        .contentType("application/json").content("{\"payChannel\":\"alipay\",\"success\":true}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) assertEquals(200, future.get(), "并发支付都应正常返回");
        pool.shutdown();

        OrderEntity order = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.paid, order.getStatus());
        assertEquals(PayChannel.alipay, order.getPayChannel());
    }

    /** 验收 5：下单 24h 后未支付，订单自动变「已取消」，库存回补，售罄下架商品恢复在售。 */
    @Test
    void unpaidOrderIsAutoCancelledAfter24HoursAndStockRestored() throws Exception {
        long product = publish("限量笔", "10.00", 2);
        long orderId = submit(alice, product, 2);
        // 下单占满库存 → 商品进入已售罄下架
        setProductStatus(product, ProductStatus.depleted);

        // 未超时：扫描不应动它
        assertEquals(0, timeoutJob.cancelExpired());
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());

        backdate(orderId, 25);
        assertEquals(1, timeoutJob.cancelExpired(), "超 24h 的未支付订单应被自动取消");

        OrderEntity cancelled = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.cancelled, cancelled.getStatus());
        ProductEntity restored = products.findById(product).orElseThrow();
        assertEquals(2, restored.getStock(), "库存按明细数量回补");
        assertEquals(ProductStatus.active, restored.getStatus(), "已售罄下架的商品自动恢复在售");

        // 幂等：再扫一次不会重复回补
        assertEquals(0, timeoutJob.cancelExpired());
        assertEquals(2, products.findById(product).orElseThrow().getStock());
    }

    /** 验收 5 补充：已支付订单不会被超时取消。 */
    @Test
    void paidOrderIsNeverTimeoutCancelled() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                .content("{\"payChannel\":\"alipay\",\"success\":true}")).andExpect(status().isOk());

        backdate(orderId, 48);
        assertEquals(0, timeoutJob.cancelExpired());
        assertEquals(OrderStatus.paid, orders.findById(orderId).orElseThrow().getStatus());
    }

    /** 补充：已超时的订单不能再发起支付，支付页也不给入口。 */
    @Test
    void expiredUnpaidOrderRefusesFurtherPayment() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        backdate(orderId, 25);

        mvc.perform(get("/api/orders/" + orderId + "/payment").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.channels.length()").value(4));

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"alipay\",\"success\":true}"))
                .andExpect(status().isConflict());
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());
    }

    /** 验收 6：「已支付」及之后状态的订单无支付入口。 */
    @Test
    void paidAndLaterStatusesHaveNoPaymentEntry() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        for (OrderStatus status : List.of(OrderStatus.paid, OrderStatus.confirmed,
                OrderStatus.packed, OrderStatus.shipped, OrderStatus.completed)) {
            long orderId = submit(alice, product, 1);
            setStatus(orderId, status, status == OrderStatus.paid ? PayChannel.alipay : null);

            mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                    .andExpect(jsonPath("$.payable").value(false));
            mvc.perform(get("/api/orders/" + orderId + "/payment").cookie(alice))
                    .andExpect(jsonPath("$.payable").value(false));
            mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(alice).contentType("application/json")
                            .content("{\"payChannel\":\"alipay\",\"success\":true}"))
                    .andExpect(status().isOk()); // 幂等返回，不做二次扣款
            assertEquals(status, orders.findById(orderId).orElseThrow().getStatus());
        }

        // 「已取消」订单也不能支付
        long cancelled = submit(alice, product, 1);
        mvc.perform(post("/api/orders/" + cancelled + "/cancel").cookie(alice)).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + cancelled + "/pay").cookie(alice).contentType("application/json")
                        .content("{\"payChannel\":\"alipay\",\"success\":true}"))
                .andExpect(status().isConflict());
    }

    /** 越权：不能支付他人订单。 */
    @Test
    void cannotPaySomeoneElsesOrder() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);

        mvc.perform(post("/api/orders/" + orderId + "/pay").cookie(bob).contentType("application/json")
                .content("{\"payChannel\":\"alipay\",\"success\":true}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + orderId + "/payment").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + orderId + "/pay").contentType("application/json")
                .content("{\"payChannel\":\"alipay\",\"success\":true}")).andExpect(status().isUnauthorized());
        assertEquals(OrderStatus.placed, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ==================== 14258 确认收货 ====================

    /** 验收 1：「开始发货」订单显示确认收货按钮；点击后状态「交易完成」。 */
    @Test
    void shippedOrderCanBeConfirmedToCompleted() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 2);
        setStatus(orderId, OrderStatus.shipped, PayChannel.wechat);

        mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                .andExpect(jsonPath("$.confirmable").value(true));

        mvc.perform(post("/api/orders/" + orderId + "/confirm").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.statusLabel").value("交易完成"))
                .andExpect(jsonPath("$.confirmable").value(false));

        assertEquals(OrderStatus.completed, orders.findById(orderId).orElseThrow().getStatus());
        // 交易完成不再变动库存（BR-05：预占在此落账）
        assertEquals(3, products.findById(product).orElseThrow().getStock());
        // 列表里仍是交易完成，不会消失
        mvc.perform(get("/api/orders").cookie(alice)).andExpect(jsonPath("$.items[0].status").value("completed"));
    }

    /** 验收 2：「已支付」「商家确认」「备货完成」订单无确认收货入口。 */
    @Test
    void onlyShippedOrdersCanBeConfirmed() throws Exception {
        long product = publish("钢笔", "10.00", 20);
        for (OrderStatus status : List.of(OrderStatus.placed, OrderStatus.pendingPayment,
                OrderStatus.paid, OrderStatus.confirmed, OrderStatus.packed)) {
            long orderId = submit(alice, product, 1);
            setStatus(orderId, status, status == OrderStatus.paid ? PayChannel.alipay : null);

            mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                    .andExpect(jsonPath("$.confirmable").value(false));
            mvc.perform(post("/api/orders/" + orderId + "/confirm").cookie(alice))
                    .andExpect(status().isConflict());
            assertEquals(status, orders.findById(orderId).orElseThrow().getStatus());
        }
    }

    /** 验收 3：「交易完成」订单无任何状态变更入口；重复确认幂等、已取消不可确认。 */
    @Test
    void completedOrderIsFinalAndConfirmIsIdempotent() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        setStatus(orderId, OrderStatus.shipped, PayChannel.alipay);
        mvc.perform(post("/api/orders/" + orderId + "/confirm").cookie(alice)).andExpect(status().isOk());

        // 交易完成：不可取消、不可再次流转
        mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.confirmable").value(false));
        mvc.perform(post("/api/orders/" + orderId + "/cancel").cookie(alice)).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/" + orderId + "/confirm").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(true));

        // 已取消订单不能确认收货
        long cancelled = submit(alice, product, 1);
        mvc.perform(post("/api/orders/" + cancelled + "/cancel").cookie(alice)).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + cancelled + "/confirm").cookie(alice)).andExpect(status().isConflict());
    }

    /** 验收 4 等价校验：没有定时任务会自动确认收货（只有发货中的订单能被确认）。 */
    @Test
    void noAutomaticConfirmationHappens() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        setStatus(orderId, OrderStatus.shipped, PayChannel.alipay);
        backdate(orderId, 72);

        // 超时扫描只处理未支付订单，已发货订单不受影响
        assertEquals(0, timeoutJob.cancelExpired());
        assertEquals(OrderStatus.shipped, orders.findById(orderId).orElseThrow().getStatus());
    }

    /** 越权：不能确认他人订单。 */
    @Test
    void cannotConfirmSomeoneElsesOrder() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        setStatus(orderId, OrderStatus.shipped, PayChannel.alipay);

        mvc.perform(post("/api/orders/" + orderId + "/confirm").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + orderId + "/confirm")).andExpect(status().isUnauthorized());
        assertEquals(OrderStatus.shipped, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ==================== 14259 查询物流 ====================

    /** 验收 1：「开始发货」订单可见单号与跟踪信息。 */
    @Test
    void shippedOrderExposesTrackingNumberAndTraces() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        ship(orderId, ShipCompany.sf, "SF1234567890", Instant.now());

        mvc.perform(get("/api/orders/" + orderId + "/logistics").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.shipCompany").value("sf"))
                .andExpect(jsonPath("$.shipCompanyLabel").value("顺丰速运"))
                .andExpect(jsonPath("$.currentStage").value("已发货"))
                .andExpect(jsonPath("$.traces[0].stage").value("已发货"));

        // 订单列表也能直接看到物流单号（FR-C-16）
        mvc.perform(get("/api/orders").cookie(alice))
                .andExpect(jsonPath("$.items[0].trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.items[0].logisticsAvailable").value(true));
    }

    /** 跟踪信息为模拟数据，按时间推进：已发货 → 运输中 → 已签收。 */
    @Test
    void tracesAdvanceThroughSimulatedStages() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);

        ship(orderId, ShipCompany.zto, "ZT0001", Instant.now().minus(2, ChronoUnit.DAYS));
        var inTransit = read(mvc.perform(get("/api/orders/" + orderId + "/logistics").cookie(alice)).andReturn());
        assertEquals("运输中", inTransit.path("currentStage").asText());
        assertEquals(2, inTransit.path("traces").size(), "运输中时应有运输中 + 已发货两条轨迹");
        assertEquals("运输中", inTransit.path("traces").get(0).path("stage").asText());

        ship(orderId, ShipCompany.ems, "EMS0002", Instant.now().minus(4, ChronoUnit.DAYS));
        var signed = read(mvc.perform(get("/api/orders/" + orderId + "/logistics").cookie(alice)).andReturn());
        assertEquals("已签收", signed.path("currentStage").asText());
        assertEquals(3, signed.path("traces").size());
        assertEquals("已签收", signed.path("traces").get(0).path("stage").asText());
    }

    /** 验收 2：未发货订单无物流信息展示。 */
    @Test
    void unpaidOrderHasNoLogistics() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        for (OrderStatus status : List.of(OrderStatus.placed, OrderStatus.pendingPayment,
                OrderStatus.paid, OrderStatus.confirmed, OrderStatus.packed)) {
            long orderId = submit(alice, product, 1);
            setStatus(orderId, status, status == OrderStatus.paid ? PayChannel.alipay : null);

            mvc.perform(get("/api/orders/" + orderId + "/logistics").cookie(alice))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.issued").value(false))
                    .andExpect(jsonPath("$.trackingNo").doesNotExist())
                    .andExpect(jsonPath("$.traces.length()").value(0));
            mvc.perform(get("/api/orders/" + orderId).cookie(alice))
                    .andExpect(jsonPath("$.logisticsAvailable").value(false));
        }
    }

    /** 交易完成的订单仍可查物流；发货后被取消的订单仍可查历史轨迹。 */
    @Test
    void completedAndShippedThenCancelledOrdersKeepLogistics() throws Exception {
        long product = publish("钢笔", "10.00", 10);
        long completed = submit(alice, product, 1);
        ship(completed, ShipCompany.sf, "SF999", Instant.now());
        mvc.perform(post("/api/orders/" + completed + "/confirm").cookie(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + completed + "/logistics").cookie(alice))
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.trackingNo").value("SF999"));

        long cancelled = submit(alice, product, 1);
        ship(cancelled, ShipCompany.yd, "YD777", Instant.now());
        mvc.perform(post("/api/orders/" + cancelled + "/cancel").cookie(alice)).andExpect(status().isConflict());
        // 客户发货后不能再取消；此处仅校验物流查询本身对「已发货」订单仍然可用
        mvc.perform(get("/api/orders/" + cancelled + "/logistics").cookie(alice))
                .andExpect(jsonPath("$.issued").value(true));
    }

    /** 验收 3：只能查看本人订单的物流。 */
    @Test
    void cannotReadSomeoneElsesLogistics() throws Exception {
        long product = publish("钢笔", "10.00", 5);
        long orderId = submit(alice, product, 1);
        ship(orderId, ShipCompany.sf, "SF0001", Instant.now());

        mvc.perform(get("/api/orders/" + orderId + "/logistics").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + orderId + "/logistics")).andExpect(status().isUnauthorized());
    }

    // ==================== 与取消订单的衔接（仅支付引入的退款分段）====================

    /** 已付款段取消产生原路退款，未付款段取消无退款（BR-17）。 */
    @Test
    void cancelRefundsOnlyInPaidStage() throws Exception {
        long product = publish("钢笔", "10.00", 10);

        long unpaid = submit(alice, product, 1);
        var noRefund = read(mvc.perform(post("/api/orders/" + unpaid + "/cancel").cookie(alice))
                .andExpect(status().isOk()).andReturn());
        assertFalse(noRefund.path("refunded").asBoolean());
        assertEquals(0, noRefund.path("refundCents").asLong());

        long paid = submit(alice, product, 2);
        mvc.perform(post("/api/orders/" + paid + "/pay").cookie(alice).contentType("application/json")
                .content("{\"payChannel\":\"alipay\",\"success\":true}")).andExpect(status().isOk());
        var refund = read(mvc.perform(post("/api/orders/" + paid + "/cancel").cookie(alice))
                .andExpect(status().isOk()).andReturn());
        assertTrue(refund.path("refunded").asBoolean());
        assertEquals(2000, refund.path("refundCents").asLong());
        assertEquals("alipay", refund.path("refundChannel").asText(), "退款回原支付渠道");
        assertEquals(10, products.findById(product).orElseThrow().getStock(), "库存全部回补");
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

    /** 直接改库设定状态，模拟卖家推进（14207 由开发 C 负责，不在本测试范围内）。 */
    private void setStatus(long orderId, OrderStatus status, PayChannel channel) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        if (channel != null) order.setPayChannel(channel);
        orders.saveAndFlush(order);
    }

    /** 直接改库设定物流信息，模拟卖家推进到「开始发货」并填写单号。 */
    private void ship(long orderId, ShipCompany company, String trackingNo, Instant shippedAt) {
        transaction.execute(status -> {
            orders.markShipped(orderId, shippedAt.toString(), company, trackingNo);
            OrderEntity order = orders.findById(orderId).orElseThrow();
            order.setStatus(OrderStatus.shipped);
            if (order.getPayChannel() == null) order.setPayChannel(PayChannel.alipay);
            orders.saveAndFlush(order);
            return null;
        });
    }

    /** 把下单时间往前推，模拟「已过去 N 小时」。 */
    private void backdate(long orderId, int hours) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        order.setCreatedAt(Instant.now().minus(hours, ChronoUnit.HOURS).toString());
        orders.saveAndFlush(order);
    }

    private void setProductStatus(long productId, ProductStatus status) {
        ProductEntity product = products.findById(productId).orElseThrow();
        product.setStatus(status);
        products.saveAndFlush(product);
    }
}
