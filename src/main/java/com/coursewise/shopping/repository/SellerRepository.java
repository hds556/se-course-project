package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.SellerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 卖家账号仓储（系统仅一个账号）。 */
public interface SellerRepository extends JpaRepository<SellerEntity, Long> {

    Optional<SellerEntity> findByUsername(String username);

    default Optional<SellerEntity> findTheSeller() {
        return findAll().stream().findFirst();
    }
}
