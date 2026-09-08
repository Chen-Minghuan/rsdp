package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.entity.Scheme;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 搭配方案 Mapper。
 */
@Mapper
public interface SchemeMapper extends BaseMapper<Scheme> {

    /**
     * 按 ID 查询方案（含软删行——@TableLogic 只影响自动 SQL，自定义 SQL 不带 deleted_at 过滤）。
     *
     * <p>回收站/彻底删除场景使用，参照 RspuMapper.selectAnyById 既有模式。</p>
     *
     * @param schemeId 方案 ID
     * @return 方案实体（含软删），不存在返回 null
     */
    @Select("SELECT * FROM scheme WHERE scheme_id = #{schemeId}")
    Scheme selectAnyById(String schemeId);

    /**
     * 分页查询回收站中的方案（仅软删行，按删除时间倒序）。
     *
     * @param page 分页参数
     * @return 已软删方案分页结果
     */
    @Select("SELECT * FROM scheme WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    Page<Scheme> selectDeletedPage(Page<Scheme> page);

    /**
     * 物理删除方案行（彻底删除用，调用方须先物理清除 scheme_item 明细）。
     *
     * @param schemeId 方案 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM scheme WHERE scheme_id = #{schemeId}")
    int physicalDeleteById(String schemeId);
}
