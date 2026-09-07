package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.CategoryDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字典表 Mapper。
 *
 * <p>category_dict 为 (dict_type, dict_code) 复合主键表，继承 {@link CompositeKeyMapper}
 * 禁用按伪主键单列的 updateById/deleteById/selectById；单行读写请使用下方复合键方法，
 * 部分列更新按复合条件构造 UpdateWrapper（参照 DictService.updateDict）。</p>
 */
@Mapper
public interface CategoryDictMapper extends CompositeKeyMapper<CategoryDict> {

    /**
     * 按复合主键 (dict_type, dict_code) 查询单条字典。
     *
     * @param dictType 字典类型
     * @param dictCode 字典码
     * @return 字典实体，不存在时为 null
     */
    default CategoryDict selectByCompositeKey(String dictType, String dictCode) {
        return selectOne(new QueryWrapper<CategoryDict>()
            .eq("dict_type", dictType)
            .eq("dict_code", dictCode));
    }

    /**
     * 按复合主键 (dict_type, dict_code) 更新（SET 取实体非空字段）。
     *
     * <p>只需更新个别列时，应改用 {@code update(null, UpdateWrapper)} 显式指定列，
     * 避免覆盖并发写入的其他列。</p>
     *
     * @param entity 字典实体，dictType/dictCode 定位行
     * @return 影响行数
     */
    default int updateByCompositeKey(CategoryDict entity) {
        return update(entity, new QueryWrapper<CategoryDict>()
            .eq("dict_type", entity.getDictType())
            .eq("dict_code", entity.getDictCode()));
    }

    /**
     * 按复合主键 (dict_type, dict_code) 删除单条字典。
     *
     * @param dictType 字典类型
     * @param dictCode 字典码
     * @return 影响行数
     */
    default int deleteByCompositeKey(String dictType, String dictCode) {
        return delete(new QueryWrapper<CategoryDict>()
            .eq("dict_type", dictType)
            .eq("dict_code", dictCode));
    }

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
