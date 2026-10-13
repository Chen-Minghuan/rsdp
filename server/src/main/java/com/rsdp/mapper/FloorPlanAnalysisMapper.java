package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.FloorPlanAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 户型图分析批次 Mapper。
 */
@Mapper
public interface FloorPlanAnalysisMapper extends BaseMapper<FloorPlanAnalysis> {

    /**
     * 编辑/激活事务入口锁（V20，设计文档 V1.2.3 §8.4.6：先锁 analysis，再锁目标 revision）。
     *
     * @param analysisId 分析批次 ID
     * @return 分析记录（行级排他锁）
     */
    @Select("SELECT * FROM floor_plan_analysis WHERE analysis_id = #{analysisId} AND deleted_at IS NULL FOR UPDATE")
    FloorPlanAnalysis selectByIdForUpdate(@Param("analysisId") String analysisId);

    /**
     * 以 revision CAS 保存手画布局，防止多个页面静默互相覆盖。
     *
     * @param analysisId 分析批次 ID
     * @param expectedRevision 客户端读取到的版本
     * @param layoutJson 完整布局快照 JSON
     * @return 1=保存成功，0=版本冲突或记录不可写
     */
    @Update("UPDATE floor_plan_analysis SET layout_json = CAST(#{layoutJson} AS jsonb), "
        + "layout_revision = layout_revision + 1, updated_at = now() "
        + "WHERE analysis_id = #{analysisId} AND input_mode = 'manual' AND status = 'draft' "
        + "AND layout_revision = #{expectedRevision} AND deleted_at IS NULL")
    int updateManualLayout(@Param("analysisId") String analysisId,
                           @Param("expectedRevision") long expectedRevision,
                           @Param("layoutJson") String layoutJson);

    /**
     * 已确认手画户型仅保存规划家具层，空间几何保持冻结。
     *
     * @return 1=保存成功，0=版本冲突或状态不允许
     */
    @Update("UPDATE floor_plan_analysis SET layout_json = CAST(#{layoutJson} AS jsonb), "
        + "layout_revision = layout_revision + 1, updated_at = now() "
        + "WHERE analysis_id = #{analysisId} AND input_mode = 'manual' AND status = 'confirmed' "
        + "AND layout_revision = #{expectedRevision} AND deleted_at IS NULL")
    int updateManualFurnitureLayout(@Param("analysisId") String analysisId,
                                    @Param("expectedRevision") long expectedRevision,
                                    @Param("layoutJson") String layoutJson);
}
