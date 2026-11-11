package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 订单仓储（FR-C-08 / FR-C-09 / FR-C-10）。 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /**
     * 本人订单分页，按下单时间倒序（FR-C-10：只显示本人订单、按压单时间倒序并分页）。
     * 同一时间戳再用 id 倒序兜底，保证排序稳定。
     */
    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId, Pageable pageable);

    /**
     * 按「本人 + 订单号」定位订单（FR-C-10 / AC-10 越权访问）。
     *
     * <p>刻意不提供只按 id 查询的入口：所有客户侧订单访问都必须带 customer_id 约束，
     * 他人订单号一律返回「不存在」，不会泄露订单是否存在。
     */
    Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);
}
