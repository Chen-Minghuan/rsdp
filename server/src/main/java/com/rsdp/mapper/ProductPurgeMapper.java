package com.rsdp.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 产品彻底删除（回收站物理清除）的级联清理 Mapper。
 *
 * <p>集中存放无实体/弱实体表的物理删除 SQL。外键依赖顺序由调用方
 * （ProductQueryService.permanentDeleteProduct）保证。</p>
 */
@Mapper
public interface ProductPurgeMapper {

    /**
     * 统计引用指定 RSPU 的方案明细数（含软删——外键约束不看 deleted_at）。
     * 大于 0 时禁止彻底删除（方案是业务凭证，不可级联抹掉）。
     *
     * @param rspuId RSPU ID
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM scheme_item WHERE rspu_id = #{rspuId}")
    long countSchemeItemRefs(String rspuId);

    /**
     * 导入行记录的生成结果引用置空（保留导入历史，断开外键）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE excel_import_row SET generated_rspu_id = NULL WHERE generated_rspu_id = #{rspuId}")
    int nullifyImportRowRspuRefs(String rspuId);

    /**
     * 导入行记录的生成变体引用置空（该 RSPU 下全部变体）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE excel_import_row SET generated_variant_id = NULL WHERE generated_variant_id IN (SELECT variant_id FROM rspu_variant WHERE rspu_id = #{rspuId})")
    int nullifyImportRowVariantRefs(String rspuId);

    /**
     * 物理删除 AI 识别记录（同时解除对 image_assets.image_id 的引用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM ai_recognition WHERE rspu_id = #{rspuId}")
    int deleteAiRecognitions(String rspuId);

    /**
     * 物理删除搭配反馈（双向引用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM matching_feedback WHERE rspu_id = #{rspuId} OR recommended_rspu_id = #{rspuId}")
    int deleteMatchingFeedback(String rspuId);

    /**
     * 物理删除产品集明细引用。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM product_collection_item WHERE rspu_id = #{rspuId}")
    int deleteCollectionItems(String rspuId);

    /**
     * 物理删除 AI 推荐候选（同时解除对 rsku_supply.rsku_id 的引用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM scheme_candidate WHERE rspu_id = #{rspuId}")
    int deleteSchemeCandidates(String rspuId);

    /**
     * 物理删除该 RSPU 全部变体的工厂产能记录。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM factory_variant_capacity WHERE variant_id IN (SELECT variant_id FROM rspu_variant WHERE rspu_id = #{rspuId})")
    int deleteFactoryVariantCapacity(String rspuId);

    /**
     * 物理删除该 RSPU 全部 RSKU 的价格历史。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM price_history WHERE rsku_id IN (SELECT rsku_id FROM rsku_supply WHERE rspu_id = #{rspuId})")
    int deletePriceHistory(String rspuId);

    /**
     * 物理删除价格列映射。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rspu_price_column_mapping WHERE rspu_id = #{rspuId}")
    int deletePriceColumnMappings(String rspuId);

    /**
     * 物理删除变体编码计数器。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM variant_code_counter WHERE rspu_id = #{rspuId}")
    int deleteVariantCodeCounter(String rspuId);
}
