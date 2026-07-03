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
}
