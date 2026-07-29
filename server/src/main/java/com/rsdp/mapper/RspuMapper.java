package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.entity.RspuMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
