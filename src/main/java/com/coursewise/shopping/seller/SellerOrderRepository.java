package com.coursewise.shopping.seller;

import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 卖家侧订单仓储（14207 推进订单状态 / 14208 卖家取消 / 14209 商品订单记录 / 14214 客户购买历史）。
 *
 * <p><b>为什么不复用买家的 {@code OrderRepository}</b>：本迭代的订单表由开发 A（范君豪）所有，
 * 开发 B 只被允许写 {@code order.status} 字段。为了让「谁的领域谁负责」在代码上也成立，
 * 这里为本领域单开一个只读查询 + 一个状态条件更新的仓储，<b>不改动</b>买家侧仓储的任何一行；
 * 两个仓储指向同一张 {@code orders} 表，Spring Data 允许同一实体存在多个仓储接口。
 *
 * <p><b>为什么状态推进用条件更新而不是「先读后写」</b>：卖家可能重复点击、或两个后台页面同时操作，
 * 「先 select 再 setStatus」会让两次请求都读到 {@code placed} 并各自写入，等于跳过了中间状态。
 * 条件更新把「校验当前状态 + 写入新状态」压成一条语句，受影响行数为 0 即说明状态已被他人变更，
 * 调用方据此返回 409，语义与买家的库存条件扣减一致。
 */
public interface SellerOrderRepository extends JpaRepository<OrderEntity, Long> {

    /** 14214 按客户维度：某客户的全部订单，按下单时间倒序（同时间用 id 倒序兜底）。 */
    Page<OrderEntity> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * 14209 按商品维度：涉及该商品的<b>全部订单</b>（只要明细里出现过该商品就算，不限状态）。
     *
     * <p>用子查询而不是 join：一条订单可能含同一商品的多条明细，join 会让订单重复出现，
     * 还要靠 {@code distinct} 去重，分页计数也会跟着算错。
     */
    @Query(value = "select o from OrderEntity o where o.id in "
            + "(select i.orderId from OrderItemEntity i where i.productId = :productId)",
            countQuery = "select count(o) from OrderEntity o where o.id in "
                    + "(select i.orderId from OrderItemEntity i where i.productId = :productId)")
    Page<OrderEntity> findByProductId(@Param("productId") Long productId, Pageable pageable);

    /**
     * 条件更新订单状态：仅当库里当前状态仍等于 {@code expected} 时才写入 {@code next}。
     *
     * <p>推进（14207）与取消（14208）共用这一条语句，两者都要求「状态是我刚校验过的那个」，
     * 从而杜绝并发下的二次推进与重复回补库存。
     *
     * @return 受影响行数，1 = 写入成功，0 = 状态已被其他操作改动
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OrderEntity o set o.status = :next where o.id = :id and o.status = :expected")
    int changeStatus(@Param("id") Long id, @Param("expected") OrderStatus expected, @Param("next") OrderStatus next);
}
