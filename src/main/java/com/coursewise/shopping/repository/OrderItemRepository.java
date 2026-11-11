package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 订单明细仓储（订单快照的读取与写入）。 */
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    /** 单笔订单的明细，按主键升序（= 下单时的写入顺序）。 */
    List<OrderItemEntity> findByOrderIdOrderByIdAsc(Long orderId);

    /** 批量取多笔订单的明细，供订单列表一次性装载，避免逐单查询。 */
    List<OrderItemEntity> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);
}
