package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.KnowledgeProductType;
import com.rsdp.mapper.KnowledgeProductTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 二级产品类型知识读取服务。 */
@Service
@RequiredArgsConstructor
public class KnowledgeProductTypeService {

    private final KnowledgeProductTypeMapper mapper;

    /**
     * 查询启用的二级产品类型，供 Shadow prompt 注入。
     *
     * @return 按业务品类和排序号排列的类型列表
     */
    public List<KnowledgeProductType> listActive() {
        return mapper.selectList(new QueryWrapper<KnowledgeProductType>()
            .eq("status", "active")
            .orderByAsc("business_category_code", "sort_order", "type_code"));
    }
}
