package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 同款合并专用 Mapper（无实体表与需绕过 @TableLogic 过滤的改指 SQL）。
 *
 * <p>所有语句为合并工具内部使用：把副本 RSPU 的引用改指到目标 RSPU，
 * 或按合并语义去重。调用方（RspuMergeService）负责顺序与事务。</p>
 */
@Mapper
public interface RspuMergeMapper {

    /** 仅在当前合并事务内延迟变体归属组合外键，允许父子行分步改挂。 */
    @Update("SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant DEFERRED")
    void deferVariantOwnershipConstraints();

    /** 迁移完成即检查所有暂缓的归属引用，失败使合并事务整体回滚。 */
    @Update("SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant IMMEDIATE")
    void checkVariantOwnershipConstraints();

    /** 软删供应记录也受归属外键约束，整体改挂变体时同步归属（保留封存业务编码）。 */
    @Update("UPDATE rsku_supply SET rspu_id = #{target}, updated_at = now()"
        + " WHERE rspu_id = #{source} AND variant_id = #{variantId} AND deleted_at IS NOT NULL")
    int repointArchivedVariantRskus(@Param("source") String source, @Param("target") String target,
                                    @Param("variantId") String variantId);

    /** 软删变体图片随整体改挂变体同步归属，图片 ID 和存储对象不变。 */
    @Update("UPDATE image_assets SET rspu_id = #{target}"
        + " WHERE rspu_id = #{source} AND variant_id = #{variantId} AND deleted_at IS NOT NULL")
    int repointArchivedVariantImages(@Param("source") String source, @Param("target") String target,
                                     @Param("variantId") String variantId);


    /** scheme_item 改指目标（含软删行：否则副本永久删除时永远被业务凭证拦截；自定义 SQL 不受 @TableLogic 过滤） */
    @Update("UPDATE scheme_item SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointSchemeItems(@Param("source") String source, @Param("target") String target);

    /** ai_recognition 改指目标（识别历史归拢到主档） */
    @Update("UPDATE ai_recognition SET rspu_id = #{target} WHERE rspu_id = #{source}")
    int repointAiRecognitions(@Param("source") String source, @Param("target") String target);


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

    /**
     * 供应商身份改指前去重：目标已有同身份键（factory_code, source_namespace,
     * normalized_source_product_code）记录的，删除副本记录
     * （uk_supplier_product_ref 冲突；supplier_product_ref 无 deleted_at，物理删）。
     */
    @Update("DELETE FROM supplier_product_ref s WHERE s.rspu_id = #{source} AND EXISTS ("
        + "SELECT 1 FROM supplier_product_ref t WHERE t.rspu_id = #{target} "
        + "AND t.factory_code = s.factory_code AND t.source_namespace = s.source_namespace "
        + "AND t.normalized_source_product_code = s.normalized_source_product_code)")
    int deleteDuplicateSupplierProductRefs(@Param("source") String source, @Param("target") String target);

    /**
     * 供应商源商品身份改指目标：合并后副本厂型号必须命中目标 RSPU，
     * 否则再导入时解析"命中已软删副本"按未命中处理，重导链路被 SourceCodeReusedException 卡死。
     */
    @Update("UPDATE supplier_product_ref SET rspu_id = #{target}, updated_at = now() WHERE rspu_id = #{source}")
    int repointSupplierProductRefs(@Param("source") String source, @Param("target") String target);

    /** 回滚记录用：产品名下供应商身份记录 ID 列表。 */
    @Select("SELECT ref_id FROM supplier_product_ref WHERE rspu_id = #{rspuId}")
    java.util.List<Long> listSupplierRefIds(@Param("rspuId") String rspuId);

    /** 回滚记录用：anchor 侧未删搭配关系 ID 列表。 */
    @Select("SELECT relation_id FROM rspu_relation WHERE deleted_at IS NULL AND anchor_rspu_id = #{rspuId}")
    java.util.List<String> listAnchorRelationIds(@Param("rspuId") String rspuId);

    /** 回滚记录用：related 侧未删搭配关系 ID 列表。 */
    @Select("SELECT relation_id FROM rspu_relation WHERE deleted_at IS NULL AND related_rspu_id = #{rspuId}")
    java.util.List<String> listRelatedRelationIds(@Param("rspuId") String rspuId);

    /** 回滚记录用：产品名下工厂关联 ID 列表。 */
    @Select("SELECT mapping_id FROM rspu_factory_mapping WHERE rspu_id = #{rspuId}")
    java.util.List<Long> listFactoryMappingIds(@Param("rspuId") String rspuId);

    /** 回滚用：恢复软删 RSPU 主档行（自定义 SQL 不受 @TableLogic 过滤）。 */
    @Update("UPDATE rspu_master SET deleted_at = NULL, updated_at = now() WHERE rspu_id = #{rspuId}")
    int restoreRspuRow(@Param("rspuId") String rspuId);

    /** 回滚用：恢复软删变体行。 */
    @Update("UPDATE rspu_variant SET deleted_at = NULL, updated_at = now() WHERE variant_id = #{variantId}")
    int restoreVariantRow(@Param("variantId") String variantId);

    /** 回滚用：恢复软删 RSKU 报价行。 */
    @Update("UPDATE rsku_supply SET deleted_at = NULL, updated_at = now() WHERE rsku_id = #{rskuId}")
    int restoreRskuRow(@Param("rskuId") String rskuId);

    /** 回滚用：变体改挂回源 RSPU。 */
    @Update("UPDATE rspu_variant SET rspu_id = #{rspuId}, updated_at = now() WHERE variant_id = #{variantId}")
    int repointVariantTo(@Param("variantId") String variantId, @Param("rspuId") String rspuId);

    /** 回滚用：图片改挂回源 RSPU（变体归属随行修正）。 */
    @Update("UPDATE image_assets SET rspu_id = #{rspuId}, variant_id = #{variantId} WHERE image_id = #{imageId}")
    int repointImageTo(@Param("imageId") String imageId, @Param("rspuId") String rspuId,
                       @Param("variantId") String variantId);

    /** 回滚用：供应商身份改挂回源 RSPU。 */
    @Update("UPDATE supplier_product_ref SET rspu_id = #{rspuId}, updated_at = now() WHERE ref_id = #{refId}")
    int repointSupplierRefTo(@Param("refId") Long refId, @Param("rspuId") String rspuId);

    /** 回滚用：工厂关联改挂回源 RSPU。 */
    @Update("UPDATE rspu_factory_mapping SET rspu_id = #{rspuId} WHERE mapping_id = #{mappingId}")
    int repointFactoryMappingTo(@Param("mappingId") Long mappingId, @Param("rspuId") String rspuId);

    /** 回滚用：供应商身份改挂回源 RSPU（仅当前仍在目标上的行；撞键物理删除的行自然跳过）。 */
    @Update("UPDATE supplier_product_ref SET rspu_id = #{sourceId}, updated_at = now() WHERE ref_id = #{refId} AND rspu_id = #{targetId}")
    int repointSupplierRefToIfOn(@Param("refId") Long refId, @Param("sourceId") String sourceId,
                                 @Param("targetId") String targetId);

    /** 回滚用：工厂关联改挂回源 RSPU（仅当前仍在目标上的行；去重物理删除的行自然跳过）。 */
    @Update("UPDATE rspu_factory_mapping SET rspu_id = #{sourceId} WHERE mapping_id = #{mappingId} AND rspu_id = #{targetId}")
    int repointFactoryMappingToIfOn(@Param("mappingId") Long mappingId, @Param("sourceId") String sourceId,
                                    @Param("targetId") String targetId);

    /** 回滚用：搭配关系 anchor 侧改回源 RSPU。 */
    @Update("UPDATE rspu_relation SET anchor_rspu_id = #{rspuId}, updated_at = now() WHERE relation_id = #{relationId}")
    int repointRelationAnchorTo(@Param("relationId") String relationId, @Param("rspuId") String rspuId);

    /** 回滚用：搭配关系 related 侧改回源 RSPU。 */
    @Update("UPDATE rspu_relation SET related_rspu_id = #{rspuId}, updated_at = now() WHERE relation_id = #{relationId}")
    int repointRelationRelatedTo(@Param("relationId") String relationId, @Param("rspuId") String rspuId);

    /** 合并历史：audit_log 中 action=merge 的记录（新→旧）。jsonb 明细按文本投影，
     *  由服务层自行解析（自定义 SQL 不走实体 @TableField 的 JSONB TypeHandler，直接映射会拿不到 Map）。 */
    @Select("SELECT id, record_id, new_value::text AS new_value_json, operator, created_at "
        + "FROM audit_log WHERE table_name = 'rspu_master' AND action = 'UPDATE' "
        + "AND new_value->>'action' = 'merge' ORDER BY created_at DESC LIMIT 200")
    java.util.List<MergeHistoryRow> listMergeHistory();

    /** 合并历史查询行投影（jsonb 明细为文本，服务层解析）。 */
    @lombok.Data
    class MergeHistoryRow {
        private Long id;
        private String recordId;
        private String newValueJson;
        private String operator;
        private java.time.LocalDateTime createdAt;
    }

    /** 回滚标记计数：该合并是否已被回滚过。 */
    @Select("SELECT COUNT(*) FROM audit_log WHERE table_name = 'rspu_master' AND action = 'UPDATE' "
        + "AND new_value->>'action' = 'merge_rollback' AND (new_value->>'mergeAuditId')::bigint = #{mergeAuditId}")
    long countRollbacksForMerge(@Param("mergeAuditId") long mergeAuditId);

    /** 统计 RSPU 名下供应商身份记录数（合并预览展示用）。 */
    @Select("SELECT COUNT(*) FROM supplier_product_ref WHERE rspu_id = #{rspuId}")
    long countSupplierProductRefs(@Param("rspuId") String rspuId);

    /** 搭配关系去重：改指后同 (anchor, related, type) 只保留最早一条，其余软删 */
    @Update("UPDATE rspu_relation r SET deleted_at = now() WHERE r.deleted_at IS NULL "
        + "AND (r.anchor_rspu_id = #{target} OR r.related_rspu_id = #{target}) "
        + "AND EXISTS (SELECT 1 FROM rspu_relation x WHERE x.deleted_at IS NULL "
        + "AND x.anchor_rspu_id = r.anchor_rspu_id AND x.related_rspu_id = r.related_rspu_id "
        + "AND x.relation_type = r.relation_type AND x.created_at < r.created_at)")
    int softDeleteDuplicateRelations(@Param("target") String target);
}
