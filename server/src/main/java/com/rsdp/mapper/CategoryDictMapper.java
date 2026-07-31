package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.CategoryDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字典表 Mapper。
 */
@Mapper
public interface CategoryDictMapper extends BaseMapper<CategoryDict> {

    /**
     * 按类型查询有效字典项。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Select("SELECT * FROM category_dict WHERE dict_type = #{dictType} AND status = 'active' ORDER BY sort_order, dict_code")
    List<CategoryDict> selectByType(@Param("dictType") String dictType);

    /**
     * 按类型查询全部字典项（含停用），供字典管理中心与历史数据名称解析使用。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Select("SELECT * FROM category_dict WHERE dict_type = #{dictType} ORDER BY sort_order, dict_code")
    List<CategoryDict> selectAllByType(@Param("dictType") String dictType);

    /**
     * 按类型统计条目数（字典类型汇总）。
     *
     * <p>别名必须加双引号：PostgreSQL 会把未加引号的别名折叠为小写，
     * 导致 Map 结果的键变为 dicttype，服务层取不到值。</p>
     *
     * @return 每行含 dictType 与 count
     */
    @Select("SELECT dict_type AS \"dictType\", COUNT(*) AS \"count\" FROM category_dict GROUP BY dict_type ORDER BY dict_type")
    List<java.util.Map<String, Object>> countGroupByType();
}
