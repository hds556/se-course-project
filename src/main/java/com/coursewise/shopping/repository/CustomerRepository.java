package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 客户仓储（FR-C-04 / FR-C-05）。 */
public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {

    /** 凭用户名定位客户（用户名唯一，登录凭证）。 */
    Optional<CustomerEntity> findByUsername(String username);

    /** 用户名是否已被占用（注册时校验，DB 列级 unique 兜底）。 */
    boolean existsByUsername(String username);
}
