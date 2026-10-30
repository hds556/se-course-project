package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 商品仓储。 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<ProductEntity> {

    boolean existsByCategoryId(Long categoryId);

    /**
     * 当前未售出的商品（系统中至多一件）。
     * status != sold 即未售出；取 id 最大者以防历史数据异常时仍能稳定取值。
     */
    Optional<ProductEntity> findFirstByStatusNotOrderByIdDesc(ProductStatus status);

    /** 是否已存在未售出商品（发布商品的前置校验）。 */
    boolean existsByStatusNot(ProductStatus status);

    /** 历史商品：全部已下架商品，按发布时间倒序（FR-S-08）。 */
    List<ProductEntity> findByStatusOrderByIdDesc(ProductStatus status);

    /** 交易工作台：在售 / 冻结 / 售罄等需要处理的商品。 */
    List<ProductEntity> findByStatusInOrderByIdDesc(java.util.Collection<ProductStatus> statuses);

    /**
     * 历史商品：发生过交易结果（成功或失败）的商品，按结果时间倒序。
     *
     * <p>口径与"已下架"不同：交易失败的商品仍回到在售，但按需求要出现在历史商品列表；
     * 手动下架与售罄的商品 result 为空，天然不进列表。
     */
    List<ProductEntity> findByResultIsNotNullOrderByResultAtDescIdDesc();
}
