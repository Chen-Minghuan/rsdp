package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RspuVariant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RspuVariantMapper extends BaseMapper<RspuVariant> {

    /**
     * 恢复指定 RSPU 下被级联软删的变体（回收站还原用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE rspu_variant SET deleted_at = NULL, updated_at = now() WHERE rspu_id = #{rspuId} AND deleted_at IS NOT NULL")
    int restoreByRspuId(String rspuId);

    /**
     * 物理删除指定 RSPU 下的全部变体（回收站彻底删除用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rspu_variant WHERE rspu_id = #{rspuId}")
    int physicalDeleteByRspuId(String rspuId);
}
