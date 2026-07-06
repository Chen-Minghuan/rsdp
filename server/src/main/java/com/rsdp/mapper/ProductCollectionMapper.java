package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ProductCollection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 产品集 Mapper。
 */
@Mapper
public interface ProductCollectionMapper extends BaseMapper<ProductCollection> {

    /**
     * 批量查询产品集及其创建人信息。
     *
     * @param status 状态筛选
     * @return 产品集列表
     */
    List<ProductCollection> selectListWithCreator(@Param("status") String status);
}
