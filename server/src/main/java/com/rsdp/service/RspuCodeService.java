package com.rsdp.service;

import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RspuCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RSPU 业务编码生成服务。
 *
 * <p>编码规则：{category_code}-{style_code}-{sequence}-{size_code}
 * 例：FS-MC-001-M
 *
 * <p>当未提供 size_code 时，使用 "X" 占位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuCodeService {

    private static final long MAX_SEQUENCE = 999L;

    private final RspuCodeMapper rspuCodeMapper;
    private final DictService dictService;

    /**
     * 生成下一个 RSPU 业务编码。
     *
     * @param categoryCode 品类码，如 FS
     * @param styleCode    风格/职级码，如 MC
     * @param sizeCode     尺寸码，如 M；为空时使用 X
     * @return 业务编码，如 FS-MC-001-M
     */
    public String generateNextCode(String categoryCode, String styleCode, String sizeCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BusinessException("品类码不能为空");
        }
        if (styleCode == null || styleCode.isBlank()) {
            throw new BusinessException("风格码不能为空");
        }
        String effectiveSizeCode = (sizeCode == null || sizeCode.isBlank()) ? "X" : sizeCode.trim().toUpperCase();
        String effectiveStyleCode = styleCode.trim().toUpperCase();
        String effectiveCategoryCode = categoryCode.trim().toUpperCase();

        validateDictCode("category", effectiveCategoryCode, "品类码");
        validateStyleOrGradeCode(effectiveCategoryCode, effectiveStyleCode);
        if (!"X".equals(effectiveSizeCode)) {
            validateDictCode("size", effectiveSizeCode, "尺寸码");
        }

        Long nextSeq = rspuCodeMapper.allocateSequence(effectiveCategoryCode, effectiveStyleCode);
        if (nextSeq == null) {
            throw new BusinessException("无法生成 RSPU 编码流水号");
        }
        if (nextSeq > MAX_SEQUENCE) {
            throw new BusinessException(
                String.format("RSPU 编码流水号已超过最大值 %d，请联系管理员扩容编码规则", MAX_SEQUENCE));
        }
        return String.format("%s-%s-%03d-%s", effectiveCategoryCode, effectiveStyleCode, nextSeq, effectiveSizeCode);
    }

    private void validateStyleOrGradeCode(String categoryCode, String styleCode) {
        List<CategoryDict> styles = dictService.listByType("style");
        boolean styleExists = styles.stream().anyMatch(d -> styleCode.equals(d.getDictCode()));
        List<CategoryDict> grades = dictService.listByType("grade");
        boolean gradeExists = grades.stream().anyMatch(d -> styleCode.equals(d.getDictCode()));
        if (!styleExists && !gradeExists) {
            throw new BusinessException("风格/职级码不存在: " + styleCode);
        }
    }

    private void validateDictCode(String dictType, String code, String label) {
        List<CategoryDict> dicts = dictService.listByType(dictType);
        boolean exists = dicts.stream().anyMatch(d -> code.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException(label + "不存在: " + code);
        }
    }
}
