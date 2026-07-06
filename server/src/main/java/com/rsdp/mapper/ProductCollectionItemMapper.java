package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ProductCollectionItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 产品集项 Mapper。
 */
@Mapper
public interface ProductCollectionItemMapper extends BaseMapper<ProductCollectionItem> {

    /**
     * 根据产品集 ID 查询项列表。
     *
     * @param collectionId 产品集 ID
     * @return 项列表
     */
    List<ProductCollectionItem> selectByCollectionId(@Param("collectionId") String collectionId);

    /**
     * 批量插入产品集项。
     *
     * @param items 项列表
     * @return 插入行数
     */
    int insertBatch(@Param("list") List<ProductCollectionItem> items);

    /**
     * 根据产品集 ID 删除所有项。
     *
     * @param collectionId 产品集 ID
     * @return 删除行数
     */
    int deleteByCollectionId(@Param("collectionId") String collectionId);
}
