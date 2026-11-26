package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.FavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 收藏仓储（FR-C-06）。同一客户 + 同一商品只有一条记录（IC-02）。 */
public interface FavoriteRepository extends JpaRepository<FavoriteEntity, Long> {

    List<FavoriteEntity> findByCustomerIdOrderByIdDesc(Long customerId);

    Optional<FavoriteEntity> findByCustomerIdAndProductId(Long customerId, Long productId);

    boolean existsByCustomerIdAndProductId(Long customerId, Long productId);
}
