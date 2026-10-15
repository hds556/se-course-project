package com.coursewise.shopping.repository;

import com.coursewise.shopping.domain.IntentEntity;
import com.coursewise.shopping.domain.IntentStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 意向仓储。
 *
 * <p>排队顺序由 (submittedAt, id) 决定：submittedAt 是 ISO 时间戳字符串，
 * 字典序与时间序一致，故可直接用于 ORDER BY。id 作为同秒提交的稳定兜底。
 */
public interface IntentRepository extends JpaRepository<IntentEntity, Long> {

    /** 凭口令码定位意向（口令码唯一）。 */
    Optional<IntentEntity> findByPasscode(String passcode);

    /** 某商品下的全部意向，按排队顺序（提交时间、id）升序。 */
    List<IntentEntity> findByProductIdOrderBySubmittedAtAscIdAsc(Long productId);

    /** 某商品下处于指定阶段的意向，按排队顺序升序。 */
    List<IntentEntity> findByProductIdAndStageOrderBySubmittedAtAscIdAsc(Long productId, IntentStage stage);

    /** 口令码是否已存在（生成新口令码时排重；正常不会命中，DB 唯一约束兜底）。 */
    boolean existsByPasscode(String passcode);

    /** 某商品下指定阶段的意向数量。 */
    long countByProductIdAndStage(Long productId, IntentStage stage);
}
