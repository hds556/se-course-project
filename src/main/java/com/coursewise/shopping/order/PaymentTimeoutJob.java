package com.coursewise.shopping.order;

import com.coursewise.shopping.ShopWriteTransaction;
import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderItemEntity;
import com.coursewise.shopping.domain.OrderStatus;
import com.coursewise.shopping.repository.OrderItemRepository;
import com.coursewise.shopping.repository.OrderRepository;
import com.coursewise.shopping.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 24h 未支付自动取消（FR-C-09 规则 6 / BR-21 / C-31 / AC-02）。
 *
 * <p>这是系统<b>唯一</b>的超时机制：下单起（S-11）24h 内未支付成功，订单自动转「已取消」，
 * 并按明细数量回补库存；因售罄下架的商品随库存恢复为正而自动回到在售（BR-04 / BR-27）。
 * 系统不提供超时自动推进、超时自动确认收货（BR-21 / EX-09）。
 *
 * <p><b>一致性</b>：取消必须与库存回补同事务，且不能与客户支付并发命中同一笔订单。
 * 因此每一笔超时订单都走 {@link ShopWriteTransaction} —— 与支付共用同一把写锁，
 * 再用条件 UPDATE 抢占状态，谁先拿到订单谁生效；另一方看到状态已变即放弃，
 * 不会出现「客户付了款、扫描任务同时取消并退款」的双写资损（BR-57）。
 *
 * <p>扫描周期由 {@code shopping.payment.timeout-scan-interval-ms} 控制（默认 60 秒），
 * 支付窗口由 {@code shopping.payment.timeout-minutes} 控制（默认 1440 = 24h）。
 */
@Component
public class PaymentTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJob.class);

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final ShopWriteTransaction writes;
    private final JdbcTemplate jdbc;
    private final Duration window;

    public PaymentTimeoutJob(OrderRepository orders, OrderItemRepository orderItems,
                             ProductRepository products, ShopWriteTransaction writes, JdbcTemplate jdbc,
                             @Value("${shopping.payment.timeout-minutes:1440}") long timeoutMinutes) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.products = products;
        this.writes = writes;
        this.jdbc = jdbc;
        this.window = Duration.ofMinutes(Math.max(1, timeoutMinutes));
    }

    /** 定时入口：按配置周期扫描一次（默认每 60 秒，首次延迟 20 秒）。 */
    @Scheduled(fixedDelayString = "${shopping.payment.timeout-scan-interval-ms:60000}",
            initialDelayString = "${shopping.payment.timeout-scan-initial-delay-ms:20000}")
    public void scan() {
        try {
            int cancelled = cancelExpired();
            if (cancelled > 0) log.info("超时未支付订单扫描：自动取消 {} 笔订单并回补库存", cancelled);
        } catch (RuntimeException e) {
            // 扫描失败不影响服务可用性，下一轮继续
            log.warn("超时未支付订单扫描失败：{}", e.getMessage());
        }
    }

    /**
     * 取消全部已超 24h 仍未支付的订单。
     *
     * @return 本轮实际取消的订单数
     */
    public int cancelExpired() {
        String deadline = Instant.now().minus(window).toString();
        List<Long> expired = orders.findExpiredUnpaidOrderIds(deadline);
        int cancelled = 0;
        for (Long orderId : expired) {
            if (cancelOne(orderId, deadline)) cancelled++;
        }
        return cancelled;
    }

    /** 单笔超时取消：条件更新成功的那一次才回补库存，保证不重复回补。 */
    private boolean cancelOne(long orderId, String deadline) {
        try {
            return Boolean.TRUE.equals(writes.execute(() -> {
                // 拿到锁后再判一次：等待锁期间可能已被支付或被客户取消
                if (!orders.existsExpiredUnpaid(orderId, deadline)) return false;
                OrderEntity order = orders.findById(orderId).orElse(null);
                if (order == null || !order.getStatus().isAwaitingPayment()) return false;

                int updated = jdbc.update(
                        "UPDATE orders SET status = 'cancelled' WHERE id = ? AND status = ?",
                        orderId, order.getStatus().code());
                if (updated == 0) return false;

                for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) {
                    // 走既有商品更新接口回补，售罄下架的商品自动恢复在售（BR-04 / BR-27）
                    products.restoreStock(line.getProductId(), line.getQuantity());
                }
                return true;
            }));
        } catch (RuntimeException e) {
            log.warn("订单 {} 超时取消失败：{}", orderId, e.getMessage());
            return false;
        }
    }

    /** 开启定时任务调度（本模块自用）。 */
    @Configuration
    @EnableScheduling
    static class Scheduling {
    }
}
