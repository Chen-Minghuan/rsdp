package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.KnowledgeProductAttribute;
import com.rsdp.mapper.KnowledgeProductAttributeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 产品属性定义知识读取服务。 */
@Service
@RequiredArgsConstructor
public class KnowledgeProductAttributeService {

    private final KnowledgeProductAttributeMapper mapper;

    /**
     * 查询指定品类及跨品类通用的启用属性定义。
     *
     * @param categoryCode 一级业务品类码
     * @return 按适用范围、排序号和属性编码排列的启用定义
     */
    public List<KnowledgeProductAttribute> listActiveByCategory(String categoryCode) {
        return mapper.selectList(new QueryWrapper<KnowledgeProductAttribute>()
            .eq("status", "active")
            .and(scope -> scope.isNull("category_code").isNull("product_type_code")
                .or(categoryCode != null && !categoryCode.isBlank(),
                    category -> category.eq("category_code", categoryCode.trim().toUpperCase())))
            .orderByAsc("category_code", "sort_order", "attribute_code"));
    }
}
