package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 同款合并专用 Mapper（无实体表与需绕过 @TableLogic 过滤的改指 SQL）。
 *
 * <p>所有语句为合并工具内部使用：把副本 RSPU 的引用改指到目标 RSPU，
 * 或按合并语义去重。调用方（RspuMergeService）负责顺序与事务。</p>
 */
@Mapper
public interface RspuMergeMapper {

    /** scheme_item 改指目标（含软删行：否则副本永久删除时永远被业务凭证拦截；自定义 SQL 不受 @TableLogic 过滤） */
    @Update("UPDATE scheme_item SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointSchemeItems(@Param("source") String source, @Param("target") String target);

    /** ai_recognition 改指目标（识别历史归拢到主档） */
    @Update("UPDATE ai_recognition SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointAiRecognitions(@Param("source") String source, @Param("target") String target);

    /** matching_feedback 双侧改指目标（无实体表） */
    @Update("UPDATE matching_feedback SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointMatchingFeedback(@Param("source") String source, @Param("target") String target);

    /** matching_feedback 推荐侧改指目标 */
    @Update("UPDATE matching_feedback SET recommended_rspu_id = #{target} WHERE recommended_rspu_id = #{source}")
    int repointMatchingFeedbackRecommended(@Param("source") String source, @Param("target") String target);

    /** product_style_match 改指目标 */
    @Update("UPDATE product_style_match SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointProductStyleMatch(@Param("source") String source, @Param("target") String target);

    /** 收藏改指前去重：同一用户已收藏目标产品的，删除其副本收藏（user_favorite 无 deleted_at，物理删） */
    @Update("DELETE FROM user_favorite WHERE rspu_id = #{source} AND user_id IN "
        + "(SELECT user_id FROM user_favorite WHERE rspu_id = #{target})")
    int deleteDuplicateFavorites(@Param("source") String source, @Param("target") String target);

    /** 收藏改指目标 */
    @Update("UPDATE user_favorite SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointFavorites(@Param("source") String source, @Param("target") String target);

    /** 产品集明细改指前去重：同一产品集已含目标产品的，删除副本明细 */
    @Update("DELETE FROM product_collection_item WHERE rspu_id = #{source} AND collection_id IN "
        + "(SELECT collection_id FROM product_collection_item WHERE rspu_id = #{target})")
    int deleteDuplicateCollectionItems(@Param("source") String source, @Param("target") String target);

    /** 产品集明细改指目标 */
    @Update("UPDATE product_collection_item SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointCollectionItems(@Param("source") String source, @Param("target") String target);

    /** scheme_candidate 改指目标 */
    @Update("UPDATE scheme_candidate SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointSchemeCandidates(@Param("source") String source, @Param("target") String target);

    /** 工厂关联改指前去重：目标已有关联的工厂，删除副本关联行（UNIQUE(rspu_id, factory_code)，无 deleted_at） */
    @Update("DELETE FROM rspu_factory_mapping WHERE rspu_id = #{source} AND factory_code IN "
        + "(SELECT factory_code FROM rspu_factory_mapping WHERE rspu_id = #{target})")
    int deleteDuplicateFactoryMappings(@Param("source") String source, @Param("target") String target);

    /** 工厂关联改指目标 */
    @Update("UPDATE rspu_factory_mapping SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointFactoryMappings(@Param("source") String source, @Param("target") String target);

    /** 主供唯一收敛：目标产品下只保留最早一条主供，其余降级（合并可能造成双主供） */
    @Update("UPDATE rspu_factory_mapping SET is_primary = false WHERE rspu_id = #{target} AND is_primary = true "
        + "AND mapping_id NOT IN (SELECT min(mapping_id) FROM rspu_factory_mapping WHERE rspu_id = #{target} AND is_primary = true)")
    int collapseDuplicatePrimaryMappings(@Param("target") String target);

    /** 搭配关系自环清理：改指后 anchor=related 的行软删 */
    @Update("UPDATE rspu_relation SET deleted_at = now() WHERE deleted_at IS NULL AND anchor_rspu_id = related_rspu_id")
    int softDeleteSelfRelations();

    /** 搭配关系去重：改指后同 (anchor, related, type) 只保留最早一条，其余软删 */
    @Update("UPDATE rspu_relation r SET deleted_at = now() WHERE r.deleted_at IS NULL "
        + "AND (r.anchor_rspu_id = #{target} OR r.related_rspu_id = #{target}) "
        + "AND EXISTS (SELECT 1 FROM rspu_relation x WHERE x.deleted_at IS NULL "
        + "AND x.anchor_rspu_id = r.anchor_rspu_id AND x.related_rspu_id = r.related_rspu_id "
        + "AND x.relation_type = r.relation_type AND x.created_at < r.created_at)")
    int softDeleteDuplicateRelations(@Param("target") String target);
}
