package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ImageAssets;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImageAssetsMapper extends BaseMapper<ImageAssets> {

    /**
     * 批量插入图片资产。
     *
     * @param assets 图片资产列表
     * @return 插入条数
     */
    int insertBatch(@Param("list") List<ImageAssets> assets);

    /**
     * 查询指定 RSPU 的全部图片资产（含已软删除，彻底删除时收集存储文件用）。
     *
     * @param rspuId RSPU ID
     * @return 图片资产列表
     */
    @Select("SELECT * FROM image_assets WHERE rspu_id = #{rspuId}")
    List<ImageAssets> selectAnyByRspuId(String rspuId);

    /**
     * 恢复指定 RSPU 下被级联软删的图片资产（回收站还原用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE image_assets SET deleted_at = NULL WHERE rspu_id = #{rspuId} AND deleted_at IS NOT NULL")
    int restoreByRspuId(String rspuId);

    /**
     * 物理删除指定 RSPU 下的全部图片资产记录（回收站彻底删除用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM image_assets WHERE rspu_id = #{rspuId}")
    int physicalDeleteByRspuId(String rspuId);
}
