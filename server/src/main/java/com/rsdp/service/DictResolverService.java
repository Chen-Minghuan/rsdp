package com.rsdp.service;

import com.rsdp.entity.CategoryDict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 字典解析服务，用于在中文名称与字典码之间转换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictResolverService {

    private final DictService dictService;

    /**
     * 根据字典类型和中文名称查找字典码。
     *
     * <p>如果找不到精确匹配，返回 null，调用方应自行决定降级策略。
     *
     * @param dictType 字典类型，如 style、scene、material
     * @param dictName 中文名称，如"中古风"
     * @return 字典码，如 MC；找不到返回 null
     */
    public String resolveCodeByName(String dictType, String dictName) {
        if (dictName == null || dictName.isBlank()) {
            return null;
        }
        String normalized = dictName.trim();
        return dictService.listByType(dictType).stream()
            .filter(d -> normalized.equals(d.getDictName()) || normalized.equals(d.getDictNameEn()))
            .map(CategoryDict::getDictCode)
            .findFirst()
            .orElseGet(() -> {
                log.warn("未找到字典项: dictType={}, dictName={}", dictType, dictName);
                return null;
            });
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
     * 批量解析中文名称为字典码。
     *
     * @param dictType  字典类型
     * @param dictNames 中文名称列表
     * @return 成功解析的字典码列表
     */
    public List<String> resolveCodesByNames(String dictType, List<String> dictNames) {
        if (dictNames == null || dictNames.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameToCode = dictService.listByType(dictType).stream()
            .collect(Collectors.toMap(
                d -> d.getDictName() != null ? d.getDictName() : "",
                CategoryDict::getDictCode,
                (a, b) -> a
            ));

        return dictNames.stream()
            .map(String::trim)
            .distinct()
            .map(nameToCode::get)
            .filter(java.util.Objects::nonNull)
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
}
