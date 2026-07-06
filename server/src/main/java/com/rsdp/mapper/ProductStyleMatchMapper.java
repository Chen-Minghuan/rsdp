package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ProductStyleMatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductStyleMatchMapper extends BaseMapper<ProductStyleMatch> {

    @Select("SELECT * FROM product_style_match WHERE rspu_id = #{rspuId} ORDER BY overall_score DESC")
    List<ProductStyleMatch> selectByRspuId(@Param("rspuId") String rspuId);
}
