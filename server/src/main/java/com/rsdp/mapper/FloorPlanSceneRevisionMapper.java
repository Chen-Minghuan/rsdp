package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.FloorPlanSceneRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 户型 Scene Revision 头表 Mapper（联合主键 analysis_id + analysis_revision，
 * 常规读写走 QueryWrapper/UpdateWrapper 双条件）。
 *
 * <p><b>禁止使用 {@code updateById/saveOrUpdate}</b>：本表为复合主键，实体不标
 * {@code @TableId}，按 ID 更新会缺失 analysis_revision 条件或抛主键缺失；
 * 更新一律走 {@link #updateByRevision}（双条件）。</p>
 */
@Mapper
public interface FloorPlanSceneRevisionMapper extends BaseMapper<FloorPlanSceneRevision> {

    /**
     * Revision 行级锁（V20，§8.4.6：锁顺序为 analysis → revision，必须在 analysis 锁之后调用）。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision Revision 序号
     * @return Revision 记录（行级排他锁）
     */
    @Select("SELECT * FROM floor_plan_scene_revision"
        + " WHERE analysis_id = #{analysisId} AND analysis_revision = #{analysisRevision} FOR UPDATE")
    FloorPlanSceneRevision selectForUpdate(@Param("analysisId") String analysisId,
                                           @Param("analysisRevision") Integer analysisRevision);

    /**
     * 按复合主键双条件更新（SET 取实体非 null 字段，与 updateById 语义一致，
     * 但 WHERE 强制 analysis_id + analysis_revision 双条件，避免命中同分析全部 Revision 行）。
     *
     * @param revision Revision 实体（analysisId/analysisRevision 必非空）
     * @return 影响行数
     */
    default int updateByRevision(FloorPlanSceneRevision revision) {
        return update(revision, new LambdaUpdateWrapper<FloorPlanSceneRevision>()
            .eq(FloorPlanSceneRevision::getAnalysisId, revision.getAnalysisId())
            .eq(FloorPlanSceneRevision::getAnalysisRevision, revision.getAnalysisRevision()));
    }
}
