package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {
    List<CategoryEntity> findAllByOrderByIdAsc();
    boolean existsByParentId(Long parentId);
}
