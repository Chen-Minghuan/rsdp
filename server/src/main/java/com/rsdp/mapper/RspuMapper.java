package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.entity.RspuMaster;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RspuMapper extends BaseMapper<RspuMaster> {

    /**
     * 回收站分页查询：仅返回已软删除的 RSPU（自定义 SQL 不追加 @TableLogic 条件）。
     *
     * @param page 分页参数
     * @return 分页结果
     */
    @Select("SELECT * FROM rspu_master WHERE deleted_at IS NOT NULL ORDER BY created_at DESC")
    Page<RspuMaster> selectRecycledPage(Page<RspuMaster> page);

    /**
     * 回收站总数（已软删除的 RSPU 数量）。
     *
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM rspu_master WHERE deleted_at IS NOT NULL")
    long selectRecycledCount();

    /**
     * 按 ID 查询 RSPU（含已软删除，自定义 SQL 不追加 @TableLogic 条件）。
     *
     * @param rspuId RSPU ID
     * @return RSPU 记录（含回收站中的），不存在返回 null
     */
    @Select("SELECT * FROM rspu_master WHERE rspu_id = #{rspuId}")
    RspuMaster selectAnyById(String rspuId);

    /**
     * 恢复软删除（回收站还原）：清除 deleted_at。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE rspu_master SET deleted_at = NULL, updated_at = now() WHERE rspu_id = #{rspuId} AND deleted_at IS NOT NULL")
    int restoreById(String rspuId);

    /**
     * 物理删除（回收站彻底清除，仅彻底删除接口使用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rspu_master WHERE rspu_id = #{rspuId}")
    int physicalDeleteById(String rspuId);
}
