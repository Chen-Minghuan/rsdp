package com.rsdp.service;

import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RspuCodeMapper;
import com.rsdp.mapper.RspuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RSPU 业务编码生成服务。
 *
 * <p>编码规则：{category_code}-{style_code}-{sequence}-{size_code}
 * 例：FS-MC-001-M
 *
 * <p>尺寸码必须显式传入或由 AI 推断；不允许使用占位符生成编码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuCodeService {

    private static final long MAX_SEQUENCE = 999L;

    private static final long SIZE_SMALL_THRESHOLD = 600L;
    private static final long SIZE_MEDIUM_THRESHOLD = 1200L;
    private static final long SIZE_LARGE_THRESHOLD = 1800L;

    private final RspuCodeMapper rspuCodeMapper;
    private final DictService dictService;
    private final RspuMapper rspuMapper;

    /**
     * 生成下一个 RSPU 业务编码。
     *
     * @param categoryCode 品类码，如 FS
     * @param styleCode    风格/职级码，如 MC
     * @param sizeCode     尺寸码，如 M；不可为空
     * @return 业务编码，如 FS-MC-001-M
     */
    public String generateNextCode(String categoryCode, String styleCode, String sizeCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BusinessException("品类码不能为空");
        }
        if (styleCode == null || styleCode.isBlank()) {
            throw new BusinessException("风格码不能为空");
        }
        if (sizeCode == null || sizeCode.isBlank()) {
            throw new BusinessException("尺寸码不能为空，需显式传入或由 AI 推断");
        }
        String effectiveSizeCode = sizeCode.trim().toUpperCase();
        String effectiveStyleCode = styleCode.trim().toUpperCase();
        String effectiveCategoryCode = categoryCode.trim().toUpperCase();

        validateDictCode("category", effectiveCategoryCode, "品类码");
        validateStyleOrGradeCode(effectiveCategoryCode, effectiveStyleCode);
        validateDictCode("size", effectiveSizeCode, "尺寸码");

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

    /**
     * 为指定 RSPU 生成并写入业务编码。
     *
     * <p>若该 RSPU 已有业务编码，则直接返回已有编码；否则生成新编码并写入数据库。</p>
     *
     * @param rspuId       RSPU ID
     * @param categoryCode 品类码
     * @param styleCode    风格/职级码
     * @param sizeCode     尺寸码
     * @return 生成的业务编码
     */
    @Transactional
    public String assignCode(String rspuId, String categoryCode, String styleCode, String sizeCode) {
        if (!StringUtils.hasText(rspuId)) {
            throw new BusinessException("RSPU ID 不能为空");
        }
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            throw new BusinessException("RSPU 不存在: " + rspuId);
        }
        if (StringUtils.hasText(rspu.getRspuCode())) {
            return rspu.getRspuCode();
        }
        String code = generateNextCode(categoryCode, styleCode, sizeCode);
        rspu.setRspuCode(code);
        rspu.setUpdatedAt(java.time.LocalDateTime.now());
        rspuMapper.updateById(rspu);
        return code;
    }

    /**
     * 根据 AI 识别结果推断尺寸码。
     *
     * <p>优先使用 OCR 解析出的长宽高最大值，按阈值映射为 S/M/L/X；
     * 无法推断时返回 null。</p>
     *
     * @param labels AI 识别标签
     * @return 尺寸码（S/M/L/X）或 null
     */
    public String inferSizeCode(AiLabels labels) {
        if (labels == null || labels.getOcr() == null) {
            return null;
        }
        Dimensions dims = labels.getOcr().getDimensions();
        if (dims == null) {
            return null;
        }
        long max = maxDimension(dims);
        if (max <= 0) {
            return null;
        }
        if (max < SIZE_SMALL_THRESHOLD) {
            return "S";
        } else if (max < SIZE_MEDIUM_THRESHOLD) {
            return "M";
        } else if (max < SIZE_LARGE_THRESHOLD) {
            return "L";
        } else {
            return "X";
        }
    }

    private long maxDimension(Dimensions dims) {
        long max = 0;
        max = Math.max(max, safeValue(dims.getW()));
        max = Math.max(max, safeValue(dims.getH()));
        max = Math.max(max, safeValue(dims.getD()));
        return max;
    }

    private long safeValue(Integer value) {
        return value == null ? 0L : value.longValue();
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
