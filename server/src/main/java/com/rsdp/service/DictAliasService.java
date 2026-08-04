package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.DictAlias;
import com.rsdp.mapper.DictAliasMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典别名服务：工厂方言叫法 → 字典码的查询与自学习写回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictAliasService {

    private final DictAliasMapper dictAliasMapper;

    /**
     * 查询单个别名对应的字典码。
     *
     * @param dictType  字典类型，如 category
     * @param aliasName 别名（工厂方言叫法）
     * @return 字典码；不存在返回 null
     */
    public String resolveAlias(String dictType, String aliasName) {
        if (!StringUtils.hasText(dictType) || !StringUtils.hasText(aliasName)) {
            return null;
        }
        DictAlias alias = dictAliasMapper.selectOne(new QueryWrapper<DictAlias>()
            .eq("dict_type", dictType)
            .eq("alias_name", aliasName.trim())
            .last("LIMIT 1"));
        return alias != null ? alias.getDictCode() : null;
    }

    /**
     * 批量查询别名映射（避免逐词查库）。
     *
     * @param dictType   字典类型
     * @param aliasNames 别名列表
     * @return aliasName → dictCode 映射（仅包含命中的词条）
     */
    public Map<String, String> resolveAliases(String dictType, Collection<String> aliasNames) {
        if (!StringUtils.hasText(dictType) || aliasNames == null || aliasNames.isEmpty()) {
            return Map.of();
        }
        List<String> names = aliasNames.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
        if (names.isEmpty()) {
            return Map.of();
        }
        List<DictAlias> aliases = dictAliasMapper.selectList(new QueryWrapper<DictAlias>()
            .eq("dict_type", dictType)
            .in("alias_name", names));
        Map<String, String> result = new HashMap<>();
        for (DictAlias alias : aliases) {
            result.putIfAbsent(alias.getAliasName(), alias.getDictCode());
        }
        return result;
    }

    /**
     * 幂等保存别名：同 (dict_type, alias_name) 已存在则按需更新 dict_code，
     * 不存在则插入；并发下唯一约束冲突时安全跳过。
     *
     * @param dictType  字典类型
     * @param aliasName 别名
     * @param dictCode  字典码
     * @param createdBy 操作人
     */
    public void saveAlias(String dictType, String aliasName, String dictCode, String createdBy) {
        if (!StringUtils.hasText(dictType) || !StringUtils.hasText(aliasName) || !StringUtils.hasText(dictCode)) {
            return;
        }
        String name = aliasName.trim();
        DictAlias existing = dictAliasMapper.selectOne(new QueryWrapper<DictAlias>()
            .eq("dict_type", dictType)
            .eq("alias_name", name)
            .last("LIMIT 1"));
        if (existing != null) {
            if (!dictCode.equals(existing.getDictCode())) {
                existing.setDictCode(dictCode);
                dictAliasMapper.updateById(existing);
            }
            return;
        }
        DictAlias alias = new DictAlias();
        alias.setDictType(dictType);
        alias.setAliasName(name);
        alias.setDictCode(dictCode);
        alias.setSource("ai_confirmed");
        alias.setCreatedBy(createdBy);
        alias.setCreatedAt(LocalDateTime.now());
        try {
            dictAliasMapper.insert(alias);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束兜底：同名字典别名已被其他请求写入，忽略
            log.debug("别名已存在（并发写入），跳过: {} {}", dictType, name);
        }
    }
}
