package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.CartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 购物车仓储（FR-C-07）。同一客户 + 同一商品只有一行（IC-02）。 */
public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> {

    List<CartItemEntity> findByCustomerIdOrderByIdAsc(Long customerId);

    Optional<CartItemEntity> findByCustomerIdAndProductId(Long customerId, Long productId);

    Optional<CartItemEntity> findByIdAndCustomerId(Long id, Long customerId);

    List<CartItemEntity> findByCustomerIdAndIdIn(Long customerId, Collection<Long> ids);

    void deleteByCustomerIdAndIdIn(Long customerId, Collection<Long> ids);
}
