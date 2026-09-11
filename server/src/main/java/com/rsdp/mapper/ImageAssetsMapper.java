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

    /**
     * 按内容哈希查询未软删的图片资产（录入查重：同一张图是否已入库）。
     *
     * @param contentHash SHA-256 哈希
     * @return 匹配的第一条记录，无则 null
     */
    @Select("SELECT * FROM image_assets WHERE content_hash = #{contentHash} AND deleted_at IS NULL LIMIT 1")
    ImageAssets selectByContentHash(String contentHash);

    /**
     * 查询软删超过阈值的图片资产（历史孤儿文件清理用，只删存储文件、行保留）。
     *
     * <p>手写 SQL 不走 @TableLogic 过滤，可查到已软删行。</p>
     *
     * @param threshold 软删时间阈值（deleted_at 早于此时间）
     * @param limit     每批上限
     * @return 软删超期的图片资产列表
     */
    @Select("SELECT * FROM image_assets WHERE deleted_at IS NOT NULL AND deleted_at < #{threshold}"
        + " ORDER BY deleted_at LIMIT #{limit}")
    List<ImageAssets> selectSoftDeletedBefore(@Param("threshold") java.time.LocalDateTime threshold,
                                              @Param("limit") int limit);
}
