package com.coursewise.shopping.seller;

import com.coursewise.shopping.domain.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 卖家侧订单明细仓储（14209 商品订单记录的汇总口径）。
 *
 * <p>只追加两个聚合查询，用于「按商品维度」页面顶部的汇总：
 * 涉及该商品的订单笔数、累计下单件数。明细本身仍由买家的
 * {@code OrderItemRepository#findByOrderIdInOrderByIdAsc} 按订单批量装载，不做重复实现。
 */
public interface SellerOrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    /** 涉及该商品的订单笔数（同一订单内多行只算一笔）。 */
    @Query("select count(distinct i.orderId) from OrderItemEntity i where i.productId = :productId")
    long orderCountByProduct(@Param("productId") Long productId);

    /** 该商品累计被下单的件数（Σ quantity）。无明细时返回 0（coalesce 兜住 sum 的 null）。 */
    @Query("select coalesce(sum(i.quantity), 0) from OrderItemEntity i where i.productId = :productId")
    long totalQuantityByProduct(@Param("productId") Long productId);
}
