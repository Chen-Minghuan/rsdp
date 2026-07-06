package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典服务。
 */
@Service
@RequiredArgsConstructor
public class DictService {

    private final CategoryDictMapper categoryDictMapper;

    /**
     * 按类型查询有效字典项。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Cacheable(value = CacheConfig.CACHE_NAME_DICTS, keyGenerator = "simpleKeyGenerator")
    public List<CategoryDict> listByType(String dictType) {
        return categoryDictMapper.selectByType(dictType);
    }

    /**
     * 创建字典项。
     *
     * <p>仅允许扩展 {@code material} 与 {@code scene} 两类业务标签字典，
     * 核心受控字典（category、factory_level、style 等）由管理员通过数据脚本维护。</p>
     *
     * @param dict 待创建的字典项
     */
    @CacheEvict(value = CacheConfig.CACHE_NAME_DICTS, allEntries = true)
    public void createDict(CategoryDict dict) {
        String dictType = validateAndNormalizeType(dict.getDictType());
        String dictCode = validateAndNormalizeCode(dict.getDictCode());
        String dictName = validateAndNormalizeName(dict.getDictName());

        CategoryDict existing = categoryDictMapper.selectOne(
            new QueryWrapper<CategoryDict>()
                .eq("dict_type", dictType)
                .eq("dict_code", dictCode)
        );
        if (existing != null) {
            throw new BusinessException("字典项已存在: " + dictType + "=" + dictCode);
        }

        // 回写归一化后的值，方便调用方复用对象返回
        dict.setDictType(dictType);
        dict.setDictCode(dictCode);
        dict.setDictName(dictName);
        dict.setSortOrder(resolveNextSortOrder(dictType));
        dict.setStatus("active");
        categoryDictMapper.insert(dict);
    }

    private String validateAndNormalizeType(String dictType) {
        if (!org.springframework.util.StringUtils.hasText(dictType)) {
            throw new BusinessException("字典类型不能为空");
        }
        String normalized = dictType.trim().toLowerCase();
        if (!List.of("material", "scene").contains(normalized)) {
            throw new BusinessException("不允许扩展该字典类型: " + dictType);
        }
        return normalized;
    }

    private String validateAndNormalizeCode(String dictCode) {
        if (!org.springframework.util.StringUtils.hasText(dictCode)) {
            throw new BusinessException("字典编码不能为空");
        }
        String normalized = dictCode.trim().toUpperCase();
        if (normalized.length() > 32) {
            throw new BusinessException("字典编码长度不能超过 32");
        }
        if (!normalized.matches("^[A-Z0-9]+$")) {
            throw new BusinessException("字典编码只能包含大写字母和数字: " + dictCode);
        }
        return normalized;
    }

    private String validateAndNormalizeName(String dictName) {
        if (!org.springframework.util.StringUtils.hasText(dictName)) {
            throw new BusinessException("字典名称不能为空");
        }
        String normalized = dictName.trim();
        if (normalized.length() > 64) {
            throw new BusinessException("字典名称长度不能超过 64");
        }
        return normalized;
    }

    private Integer resolveNextSortOrder(String dictType) {
        CategoryDict last = categoryDictMapper.selectOne(
            new QueryWrapper<CategoryDict>()
                .eq("dict_type", dictType)
                .orderByDesc("sort_order")
                .last("LIMIT 1")
        );
        return last != null && last.getSortOrder() != null ? last.getSortOrder() + 1 : 1;
    }
}
