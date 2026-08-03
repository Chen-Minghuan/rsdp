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
    /**
     * 根据字典类型和字典码查找中文名称。
     *
     * <p>使用含停用项的全量字典，保证历史数据在字典项停用后仍能正常显示名称。</p>
     *
     * @param dictType 字典类型
     * @param dictCode 字典码
     * @return 中文名称；找不到返回原码
     */
    public String resolveNameByCode(String dictType, String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return dictCode;
        }
        return dictService.listAllByType(dictType).stream()
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
        // 含停用项：历史数据名称解析不受停用影响
        Map<String, String> codeToName = dictService.listAllByType(dictType).stream()
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
     * 六维标签值归一：在指定品类（parent_code）范围内匹配 {@code six_dim_{dimKey}} 字典。
     *
     * <p>匹配优先级：已是带前缀的合法枚举码 → 枚举名/英文名精确匹配 → 别名匹配 →
     * "其他"系列兜底（字典名为"其他"或"异形/其他"的条目）。
     * 命中返回带品类前缀的 dict_code（如 {@code SF-宽厚扶手}），未命中或品类为空返回 null，
     * 由调用方保留原文并记日志（供字典运营补充枚举/别名）。</p>
     *
     * @param dimKey       维度键，如 A/B/C/D/F
     * @param categoryCode 品类码，如 SF/TB；为空时不做归一（避免跨品类同名歧义）
     * @param value        AI 输出的六维值（枚举中文名或自由文本）
     * @return 带前缀的 dict_code；未命中返回 null
     */
    public String resolveSixDimCode(String dimKey, String categoryCode, String value) {
        if (value == null || value.isBlank() || dimKey == null || dimKey.isBlank()
                || categoryCode == null || categoryCode.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        List<CategoryDict> entries = dictService.listByType("six_dim_" + dimKey.toUpperCase()).stream()
            .filter(d -> categoryCode.equalsIgnoreCase(d.getParentCode() == null ? "" : d.getParentCode()))
            .toList();
        if (entries.isEmpty()) {
            return null;
        }
        // 已是带前缀的合法枚举码：原样保留（幂等）
        for (CategoryDict d : entries) {
            if (trimmed.equals(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        // 枚举名/英文名精确匹配 → 别名匹配（别名不覆盖已有键，精确优先）
        Map<String, String> nameToCode = new HashMap<>();
        for (CategoryDict d : entries) {
            Stream.of(d.getDictName(), d.getDictNameEn())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .forEach(k -> nameToCode.putIfAbsent(k, d.getDictCode()));
        }
        for (CategoryDict d : entries) {
            for (String alias : parseAliases(d.getAliases())) {
                nameToCode.putIfAbsent(alias, d.getDictCode());
            }
        }
        String code = nameToCode.get(trimmed);
        if (code != null) {
            return code;
        }
        // "其他"兜底：各品类兜底条目名不统一（"其他"/"异形/其他"），宽松匹配
        if ("其他".equals(trimmed) || "异形".equals(trimmed) || "异形/其他".equals(trimmed)) {
            return entries.stream()
                .filter(d -> d.getDictName() != null
                    && ("其他".equals(d.getDictName()) || d.getDictName().startsWith("异形")))
                .map(CategoryDict::getDictCode)
                .findFirst()
                .orElse(null);
        }
        return null;
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
