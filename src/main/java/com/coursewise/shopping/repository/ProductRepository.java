package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 商品仓储。 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<ProductEntity> {

    /**
     * 下单占用库存（BR-02 / BR-03 / BR-09）：条件扣减，一条语句内完成「判断 + 扣减」。
     *
     * <p>{@code WHERE stock >= :qty} 让并发提交在数据库层串行化——库存不足时影响行数为 0，
     * 调用方据此判定该项缺货，绝不会把库存扣成负数（INV-1 / 不超卖）。
     * 同时要求商品仍在售，避免对已下架商品下单。
     *
     * @return 受影响行数，1 = 扣减成功，0 = 库存不足或商品不可售
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.stock = p.stock - :qty, p.version = p.version + 1 "
            + "where p.id = :id and p.stock >= :qty and p.status in ('active', 'resumed')")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * 取消订单回补库存（BR-04）：按数量加回，并顺带把因售罄下架（depleted）的商品
     * 恢复在售（SRS §5.3：已售罄下架 → 可售恢复为正 → 在售）。
     *
     * <p>只针对 {@code depleted} 成因；手动下架（withdrawn）不受影响（INV-2）。
     *
     * @return 受影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.stock = p.stock + :qty, p.version = p.version + 1, "
            + "p.status = case when p.status = 'depleted' then 'active' else p.status end "
            + "where p.id = :id")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

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
