package com.coursewise.shopping.repository;
import com.coursewise.shopping.domain.AddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AddressRepository extends JpaRepository<AddressEntity,Long> {
 List<AddressEntity> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId);
 Optional<AddressEntity> findByIdAndCustomerId(Long id,Long customerId);
}
