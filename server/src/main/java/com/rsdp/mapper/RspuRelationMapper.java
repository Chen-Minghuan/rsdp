package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RspuRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * RSPU 产品间关系 Mapper。
 */
@Mapper
public interface RspuRelationMapper extends BaseMapper<RspuRelation> {

    /**
     * 恢复涉及指定 RSPU 的被级联软删关系（双向，回收站还原用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE rspu_relation SET deleted_at = NULL WHERE (anchor_rspu_id = #{rspuId} OR related_rspu_id = #{rspuId}) AND deleted_at IS NOT NULL")
    int restoreByRspuId(String rspuId);

    /**
     * 物理删除涉及指定 RSPU 的全部关系（双向，回收站彻底删除用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rspu_relation WHERE anchor_rspu_id = #{rspuId} OR related_rspu_id = #{rspuId}")
    int physicalDeleteByRspuId(String rspuId);
}
