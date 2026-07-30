package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.CategoryDict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 字典解析服务，用于在中文名称与字典码之间转换。
 *
 * <p>匹配优先级：标准名称/英文名精确匹配 → 别名（aliases）匹配。
 * 别名用于归一 AI 识别的常见叫法（如"头层牛皮"→皮革 LE），避免差字导致匹配失败被丢弃。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictResolverService {

    private final DictService dictService;
    private final ObjectMapper objectMapper;

    /**
     * 根据字典类型和中文名称查找字典码。
     *
     * <p>优先精确匹配 {@code category_dict} 的标准名称/英文名，未命中再按别名匹配；
     * 都找不到时返回 null。统一走字典与风格数据库，不使用硬编码别名。</p>
     *
     * @param dictType 字典类型，如 style、scene、material、fabric
     * @param dictName 中文名称，如"中古风"
     * @return 字典码，如 MC；找不到返回 null
     */
    public String resolveCodeByName(String dictType, String dictName) {
        if (dictName == null || dictName.isBlank()) {
            return null;
        }
        String code = buildNameToCodeMap(dictType).get(dictName.trim());
        if (code == null) {
            log.warn("未找到字典项: dictType={}, dictName={}", dictType, dictName);
        }
        return code;
    }

    /**
     * 根据字典类型和字典码查找中文名称。
     *
     * @param dictType 字典类型
     * @param dictCode 字典码
     * @return 中文名称；找不到返回原码
     */
    public String resolveNameByCode(String dictType, String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return dictCode;
        }
        return dictService.listByType(dictType).stream()
            .filter(d -> dictCode.equals(d.getDictCode()))
            .map(CategoryDict::getDictName)
            .findFirst()
            .orElse(dictCode);
    }

    /**
     * 批量解析中文名称为字典码（精确匹配优先，其次别名匹配）。
     *
     * @param dictType  字典类型
     * @param dictNames 中文名称列表
     * @return 成功解析的字典码列表
     */
    public List<String> resolveCodesByNames(String dictType, List<String> dictNames) {
        if (dictNames == null || dictNames.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameToCode = buildNameToCodeMap(dictType);
        return dictNames.stream()
            .map(String::trim)
            .distinct()
            .map(nameToCode::get)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * 根据字典类型和字典码批量查找中文名称。
     *
     * @param dictType 字典类型
     * @param codes    字典码列表
     * @return 中文名称列表
     */
    public List<String> resolveNamesByCodes(String dictType, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, String> codeToName = dictService.listByType(dictType).stream()
            .collect(Collectors.toMap(
                CategoryDict::getDictCode,
                d -> d.getDictName() != null ? d.getDictName() : d.getDictCode(),
                (a, b) -> a
            ));

        return codes.stream()
            .map(code -> Optional.ofNullable(codeToName.get(code)).orElse(code))
            .collect(Collectors.toList());
    }

    /**
     * 构建指定字典类型的"名称/英文名/别名 → 字典码"查找映射。
     *
     * <p>标准名称与英文名优先放入，别名后放且不覆盖已有键，保证精确匹配永远优先于别名。</p>
     *
     * @param dictType 字典类型
     * @return 查找映射
     */
    private Map<String, String> buildNameToCodeMap(String dictType) {
        Map<String, String> map = new HashMap<>();
        for (CategoryDict d : dictService.listByType(dictType)) {
            Stream.of(d.getDictName(), d.getDictNameEn())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .forEach(k -> map.putIfAbsent(k, d.getDictCode()));
        }
        for (CategoryDict d : dictService.listByType(dictType)) {
            for (String alias : parseAliases(d.getAliases())) {
                map.putIfAbsent(alias, d.getDictCode());
            }
        }
        return map;
    }

    /**
     * 解析字典项的别名 JSON 数组，解析失败按无别名处理。
     *
     * @param aliasesJson 别名 JSON，如 ["真皮","头层牛皮"]
     * @return 别名列表
     */
    private List<String> parseAliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> aliases = objectMapper.readValue(aliasesJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return aliases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析字典别名失败，按无别名处理: {}", aliasesJson, e);
            return List.of();
        }
    }
}
