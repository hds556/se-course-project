package com.coursewise.shopping.repository;
import com.coursewise.shopping.domain.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface RefundRepository extends JpaRepository<RefundEntity,Long>{ Optional<RefundEntity> findByOrderId(Long orderId); }
