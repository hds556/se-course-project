package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.OrderItemEntity;
import com.coursewise.shopping.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 订单明细仓储（订单快照的读取与写入）。 */
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    /** 单笔订单的明细，按主键升序（= 下单时的写入顺序）。 */
    List<OrderItemEntity> findByOrderIdOrderByIdAsc(Long orderId);

    /** 批量取多笔订单的明细，供订单列表一次性装载，避免逐单查询。 */
    List<OrderItemEntity> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /**
     * 14213 / C-27：统计某商品上仍处于「已支付 / 商家确认 / 备货完成」的订单数。
     *
     * <p>这批订单是商品调库存 0 与手动下架的阻塞条件；「开始发货」之后订单已进入履约，
     * 不再阻塞，「交易完成」「已取消」同样不阻塞。用子查询而不是 join，避免同一商品
     * 在一笔订单里出现多条明细时重复计数。
     */
    @Query("select count(distinct i.orderId) from OrderItemEntity i "
            + "where i.productId = :productId and i.orderId in "
            + "(select o.id from OrderEntity o where o.status in :statuses)")
    long countBlockingOrders(@Param("productId") Long productId,
                             @Param("statuses") Collection<OrderStatus> statuses);
}
