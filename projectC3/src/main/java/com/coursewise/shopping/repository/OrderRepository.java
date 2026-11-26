package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderStatus;
import com.coursewise.shopping.domain.PayChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储（FR-C-08 / FR-C-09 / FR-C-10 / FR-C-11 / FR-C-16）。
 *
 * <p><b>状态变更一律用条件更新</b>（{@code WHERE status = :expected}）：支付、确认收货与
 * 24h 超时取消三处都会并发命中同一笔订单，「先查后写」会让两次请求同时通过校验
 * （重复扣款、重复取消、重复回补库存）。把「校验当前状态 + 写入新状态」压成一条语句后，
 * 受影响行数为 0 即说明状态已被他人变更，调用方据此按幂等 / 冲突处理（BR-55、AC-14）。
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update OrderEntity o set o.status = com.coursewise.shopping.domain.OrderStatus.cancelled where o.id=:id and o.status=:expected")
    int cancelIfStatus(@Param("id") Long id,@Param("expected") OrderStatus expected);

    /**
     * 本人订单分页，按下单时间倒序（FR-C-16：只显示本人订单、按下单时间倒序并分页）。
     * 同一时间戳再用 id 倒序兜底，保证排序稳定。
     */
    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId, Pageable pageable);

    /**
     * 按「本人 + 订单号」定位订单（FR-C-16 / AC-10 越权访问）。
     *
     * <p>刻意不提供只按 id 查询的客户侧入口：所有客户侧订单访问都必须带 customer_id 约束，
     * 他人订单号一律返回「不存在」，不会泄露订单是否存在。
     */
    Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * 支付成功：「客户下单 / 待支付」→「已支付」，同时记录支付渠道（FR-C-09 规则 3）。
     *
     * <p>渠道在同一个 UPDATE 里落库，因此不存在「状态已变已支付但渠道还没写」的中间态
     * （IC-11：已支付及之后 pay_channel 必填）。
     *
     * @return 受影响行数，1 = 支付成功，0 = 订单已不是未付款状态（重复请求或已推进）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderEntity o set o.status = com.coursewise.shopping.domain.OrderStatus.paid, "
            + "o.payChannel = :channel, o.paymentCaptured = true where o.id = :id and o.status in "
            + "(com.coursewise.shopping.domain.OrderStatus.placed, "
            + "com.coursewise.shopping.domain.OrderStatus.pendingPayment)")
    int markPaid(@Param("id") Long id, @Param("channel") PayChannel channel);

    /**
     * 发起支付：「客户下单」→「待支付」，并暂存客户选定的渠道（FR-C-09 规则 1）。
     * 已经是「待支付」时允许换渠道重试，同样用条件更新保证只有一个请求生效。
     *
     * @return 受影响行数，1 = 进入待支付，0 = 订单已不在未付款状态
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderEntity o set o.status = com.coursewise.shopping.domain.OrderStatus.pendingPayment, "
            + "o.payChannel = :channel where o.id = :id and o.status in "
            + "(com.coursewise.shopping.domain.OrderStatus.placed, "
            + "com.coursewise.shopping.domain.OrderStatus.pendingPayment)")
    int markPendingPayment(@Param("id") Long id, @Param("channel") PayChannel channel);

    /**
     * 确认收货：「开始发货」→「交易完成」（FR-C-11 / BR-13）。
     * 只有「开始发货」能进「交易完成」，其余状态受影响行数为 0，天然拒绝越级与重复确认。
     *
     * @return 受影响行数，1 = 交易完成，0 = 订单不在「开始发货」
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderEntity o set o.status = com.coursewise.shopping.domain.OrderStatus.completed "
            + "where o.id = :id and o.status = com.coursewise.shopping.domain.OrderStatus.shipped")
    int markCompleted(@Param("id") Long id);

    /**
     * 24h 未支付超时取消（FR-C-09 规则 6 / BR-21）：只有「客户下单 / 待支付」且下单时间早于
     * 截止时刻的订单会被取消；一次可以命中多笔，供系统超时扫描批量处理。
     *
     * @return 被取消的订单 id 列表
     */
    @Query("select o.id from OrderEntity o where o.status in "
            + "(com.coursewise.shopping.domain.OrderStatus.placed, "
            + "com.coursewise.shopping.domain.OrderStatus.pendingPayment) "
            + "and o.createdAt < :deadline order by o.id asc")
    List<Long> findExpiredUnpaidOrderIds(@Param("deadline") String deadline);

    /**
     * 未支付订单是否已超 24h。用于客户操作的兜底校验：即使超时扫描还没跑到，
     * 客户也不能再对已超时订单发起支付（否则会出现「付了款但扫描任务同时取消」的资损风险）。
     */
    @Query("select count(o) > 0 from OrderEntity o where o.id = :id and o.status in "
            + "(com.coursewise.shopping.domain.OrderStatus.placed, "
            + "com.coursewise.shopping.domain.OrderStatus.pendingPayment) and o.createdAt < :deadline")
    boolean existsExpiredUnpaid(@Param("id") Long id, @Param("deadline") String deadline);

    /**
     * 卖家推进到「开始发货」时写入物流信息（FR-S-08 规则 3 / BR-58 / IC-12）。
     *
     * <p>字段归订单表所有，写入入口也只提供这一条：其他角色不直接改实体字段，
     * 因此「开始发货及之后 tracking_no 必填」这条约束只有一处在写，容易保证不破。
     *
     * @return 受影响行数，1 = 写入成功
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderEntity o set o.shippedAt = :shippedAt, o.shipCompany = :company, "
            + "o.trackingNo = :trackingNo where o.id = :id")
    int markShipped(@Param("id") Long id, @Param("shippedAt") String shippedAt,
                    @Param("company") com.coursewise.shopping.domain.ShipCompany company,
                    @Param("trackingNo") String trackingNo);

    /** 按状态集合统计（卖家推进用）。 */
    long countByStatusIn(Collection<OrderStatus> statuses);
}
