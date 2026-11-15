package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
    @Query(value = """
            UPDATE product SET stock = stock - :qty,
                status = CASE WHEN stock = :qty THEN 'depleted' ELSE 'active' END,
                offline_at = CASE WHEN stock = :qty THEN :now ELSE NULL END,
                version = version + 1
            WHERE id = :id AND stock >= :qty AND status = 'active'
            """, nativeQuery = true)
    int deductStock(@Param("id") Long id, @Param("qty") int qty, @Param("now") String now);

    /**
     * 取消订单回补库存（BR-04）：按数量加回，并顺带把因售罄下架（depleted）的商品
     * 恢复在售（SRS §5.3：已售罄下架 → 可售恢复为正 → 在售）。
     *
     * <p>只针对 {@code depleted} 成因；手动下架（withdrawn）不受影响（INV-2）。
     *
     * @return 受影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE product SET stock = stock + :qty, version = version + 1,
                status = CASE WHEN status = 'depleted' THEN 'active' ELSE status END,
                offline_at = CASE WHEN status = 'depleted' THEN NULL ELSE offline_at END
            WHERE id = :id
            """, nativeQuery = true)
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

    boolean existsByCategoryId(Long categoryId);

    List<ProductEntity> findByStatusIn(java.util.Collection<ProductStatus> statuses);
}
