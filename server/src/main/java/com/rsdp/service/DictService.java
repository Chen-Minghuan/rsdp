package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.dto.response.DictTypeSummaryResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字典服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictService {

    /**
     * 允许通过界面维护（新增/编辑/启停用）的字典类型。
     * 业务状态枚举（design_order_status 等被代码状态机引用的类型）不在其列，仅脚本维护。
     */
    private static final Set<String> EDITABLE_TYPES = Set.of(
        // 产品属性
        "category", "style", "scene", "material", "fabric", "color", "size", "wood_type",
        // 六维标签
        "six_dim_A", "six_dim_B", "six_dim_C", "six_dim_D", "six_dim_E", "six_dim_F",
        // 工厂与供应链
        "factory_level", "factory_source_type", "equipment_type", "process_type",
        "material_grade", "packaging_type", "logistics_method");

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DISABLED = "disabled";

    private final CategoryDictMapper categoryDictMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 按类型查询有效字典项（AI 枚举注入、前端下拉、字典归一匹配使用）。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Cacheable(value = CacheConfig.CACHE_NAME_DICTS, keyGenerator = "simpleKeyGenerator")
    public List<CategoryDict> listByType(String dictType) {
        return categoryDictMapper.selectByType(dictType);
    }

    /**
     * 按类型查询全部字典项（含停用），供字典管理中心展示。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    public List<CategoryDict> listAllByType(String dictType) {
        return categoryDictMapper.selectAllByType(dictType);
    }

    /**
     * 字典类型汇总（类型 + 条目数）。
     *
     * @return 类型汇总列表
     */
    public List<DictTypeSummaryResponse> listTypeSummary() {
        return categoryDictMapper.countGroupByType().stream()
            .map(row -> new DictTypeSummaryResponse(
                String.valueOf(row.get("dictType")),
                ((Number) row.get("count")).longValue()))
            .collect(Collectors.toList());
    }

    /**
     * 创建字典项。
     *
     * <p>仅允许扩展 {@link #EDITABLE_TYPES} 中的业务标签字典，
     * 核心受控字典（业务状态枚举等）由管理员通过数据脚本维护。</p>
     *
     * @param dict 待创建的字典项
     */
    @CacheEvict(value = CacheConfig.CACHE_NAME_DICTS, allEntries = true)
    public void createDict(CategoryDict dict) {
        String dictType = validateAndNormalizeType(dict.getDictType());
        String dictCode = validateAndNormalizeCode(dictType, dict.getDictCode());
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
        dict.setParentCode(StringUtils.hasText(dict.getParentCode()) ? dict.getParentCode().trim() : null);
        dict.setRemark(StringUtils.hasText(dict.getRemark()) ? dict.getRemark().trim() : null);
        dict.setSortOrder(resolveNextSortOrder(dictType));
        dict.setStatus(STATUS_ACTIVE);
        categoryDictMapper.insert(dict);
        auditLogService.logCreate("category_dict", dictType + ":" + dictCode, dict,
            SecurityOperatorContext.currentUsername());
    }

    /**
     * 更新字典项（名称/英文名/别名/排序/备注），编码与类型不可改。
     *
     * @param dictType  字典类型
     * @param dictCode  字典编码
     * @param dictName  新中文名（null 表示不修改）
     * @param dictNameEn 新英文名（null 表示不修改）
     * @param aliases   新别名列表（null 表示不修改，否则整体替换）
     * @param sortOrder 新排序号（null 表示不修改）
     * @param remark    新备注（null 表示不修改，空串表示清空）
     * @return 更新后的字典项
     */
    @CacheEvict(value = CacheConfig.CACHE_NAME_DICTS, allEntries = true)
    public CategoryDict updateDict(String dictType, String dictCode, String dictName,
                                   String dictNameEn, List<String> aliases, Integer sortOrder, String remark) {
        CategoryDict dict = requireEditableDict(dictType, dictCode);
        CategoryDict oldSnapshot = snapshot(dict);

        if (dictName != null) {
            dict.setDictName(validateAndNormalizeName(dictName));
        }
        if (dictNameEn != null) {
            dict.setDictNameEn(dictNameEn.trim().isEmpty() ? null : dictNameEn.trim());
        }
        if (aliases != null) {
            dict.setAliases(serializeAliases(aliases));
        }
        if (sortOrder != null) {
            dict.setSortOrder(sortOrder);
        }
        if (remark != null) {
            dict.setRemark(remark.trim().isEmpty() ? null : remark.trim());
        }

        categoryDictMapper.updateById(dict);
        auditLogService.logUpdate("category_dict", dict.getDictType() + ":" + dict.getDictCode(),
            oldSnapshot, dict, SecurityOperatorContext.currentUsername());
        return dict;
    }

    /**
     * 启停用字典项。停用后不再进入 AI 枚举注入与前端下拉，历史数据名称解析不受影响。
     *
     * @param dictType 字典类型
     * @param dictCode 字典编码
     * @param status   目标状态：active / disabled
     * @return 更新后的字典项
     */
    @CacheEvict(value = CacheConfig.CACHE_NAME_DICTS, allEntries = true)
    public CategoryDict updateDictStatus(String dictType, String dictCode, String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BusinessException("状态只能是 active 或 disabled: " + status);
        }
        CategoryDict dict = requireEditableDict(dictType, dictCode);
        CategoryDict oldSnapshot = snapshot(dict);

        dict.setStatus(status);
        categoryDictMapper.updateById(dict);
        auditLogService.logUpdate("category_dict", dict.getDictType() + ":" + dict.getDictCode(),
            oldSnapshot, dict, SecurityOperatorContext.currentUsername());
        return dict;
    }

    /**
     * 解析别名 JSON 为列表（响应输出用），解析失败按无别名处理。
     *
     * @param aliasesJson 别名 JSON
     * @return 别名列表
     */
    public List<String> parseAliases(String aliasesJson) {
        if (!StringUtils.hasText(aliasesJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(aliasesJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("解析字典别名失败，按无别名处理: {}", aliasesJson, e);
            return List.of();
        }
    }

    private CategoryDict requireEditableDict(String dictType, String dictCode) {
        String canonicalType = normalizeEditableType(dictType);
        if (canonicalType == null) {
            throw new BusinessException("该字典类型不允许通过界面维护: " + dictType);
        }
        CategoryDict dict = categoryDictMapper.selectOne(
            new QueryWrapper<CategoryDict>()
                .eq("dict_type", canonicalType)
                .eq("dict_code", dictCode)
        );
        if (dict == null) {
            throw new BusinessException("字典项不存在: " + dictType + "=" + dictCode);
        }
        return dict;
    }

    /**
     * 归一化字典类型为规范形式（six_dim_* 保留大写后缀，其余小写）。
     *
     * @param dictType 原始类型
     * @return 规范类型；不在可维护白名单内返回 null
     */
    private String normalizeEditableType(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return null;
        }
        String trimmed = dictType.trim();
        for (String editable : EDITABLE_TYPES) {
            if (editable.equalsIgnoreCase(trimmed)) {
                return editable;
            }
        }
        return null;
    }

    private String serializeAliases(List<String> aliases) {
        List<String> cleaned = aliases.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new BusinessException("别名格式非法");
        }
    }

    private CategoryDict snapshot(CategoryDict source) {
        CategoryDict copy = new CategoryDict();
        copy.setDictType(source.getDictType());
        copy.setDictCode(source.getDictCode());
        copy.setDictName(source.getDictName());
        copy.setDictNameEn(source.getDictNameEn());
        copy.setParentCode(source.getParentCode());
        copy.setSortOrder(source.getSortOrder());
        copy.setStatus(source.getStatus());
        copy.setAliases(source.getAliases());
        copy.setRemark(source.getRemark());
        return copy;
    }

    private String validateAndNormalizeType(String dictType) {
        String normalized = normalizeEditableType(dictType);
        if (normalized == null) {
            if (!StringUtils.hasText(dictType)) {
                throw new BusinessException("字典类型不能为空");
            }
            throw new BusinessException("不允许扩展该字典类型: " + dictType);
        }
        return normalized;
    }

    private String validateAndNormalizeCode(String dictType, String dictCode) {
        if (!StringUtils.hasText(dictCode)) {
            throw new BusinessException("字典编码不能为空");
        }
        String trimmed = dictCode.trim();
        // 六维类型遵循 V24 编码规范：{品类码}-{中文名}（如 SF-宽厚扶手），品类码前缀归一为大写
        if (dictType.startsWith("six_dim_")) {
            if (trimmed.length() > 64) {
                throw new BusinessException("字典编码长度不能超过 64");
            }
            if (!trimmed.matches("^[A-Za-z0-9]{2,8}-\\S.{0,55}$")) {
                throw new BusinessException("六维字典编码格式应为 {品类码}-{中文名}，如 SF-宽厚扶手: " + dictCode);
            }
            int dash = trimmed.indexOf('-');
            return trimmed.substring(0, dash).toUpperCase() + trimmed.substring(dash);
        }
        String normalized = trimmed.toUpperCase();
        if (normalized.length() > 32) {
            throw new BusinessException("字典编码长度不能超过 32");
        }
        if (!normalized.matches("^[A-Z0-9]+$")) {
            throw new BusinessException("字典编码只能包含大写字母和数字: " + dictCode);
        }
        return normalized;
    }

    private String validateAndNormalizeName(String dictName) {
        if (!StringUtils.hasText(dictName)) {
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
