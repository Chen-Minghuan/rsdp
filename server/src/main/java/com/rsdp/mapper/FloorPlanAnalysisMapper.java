package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.FloorPlanAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
