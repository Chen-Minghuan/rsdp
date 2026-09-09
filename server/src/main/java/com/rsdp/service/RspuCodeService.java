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
     * <p>noRollbackFor = BusinessException：校验类业务异常不污染外层事务
     * （对齐 RspuVariantService.createVariant / RskuService.upsertRsku 既有模式），
     * 调用方（如 AI 识别持久化）捕获后可降级继续主流程。</p>
     *
     * @param rspuId       RSPU ID
     * @param categoryCode 品类码
     * @param styleCode    风格/职级码
     * @param sizeCode     尺寸码
     * @return 生成的业务编码
     */
    @Transactional(noRollbackFor = BusinessException.class)
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
     * 容错发号：为指定 RSPU 生成并写入业务编码，失败不抛异常。
     *
     * <p>内部调用 {@link #assignCode}，捕获 {@link BusinessException} 后记录告警并返回 null，
     * 供手工录入 / 工厂录入等"尺寸码可选、不调 AI"的创建链路使用：
     * 缺尺寸码等校验失败时 rspu_code 留空、录入照常成功，后续可经补码链路补发。
     * {@link #assignCode} 的事务注解含 noRollbackFor = BusinessException，
     * 捕获后不回滚外层录入事务。</p>
     *
     * @param rspuId             RSPU ID
     * @param categoryCode       品类码
     * @param styleOrGradeCode   风格/职级码
     * @param sizeCode           尺寸码（可为空）
     * @return 发放的编码；返回 null 表示暂无法发号，rspu_code 留空
     */
    public String tryAssignCode(String rspuId, String categoryCode, String styleOrGradeCode, String sizeCode) {
        try {
            return assignCode(rspuId, categoryCode, styleOrGradeCode, sizeCode);
        } catch (BusinessException e) {
            log.warn("RSPU 业务编码暂无法发号，留空待补发，rspuId={}，categoryCode={}，原因={}",
                rspuId, categoryCode, e.getMessage());
            return null;
        }
    }

    /**
     * 根据 AI 识别结果推断尺寸码。
     *
     * <p>优先使用 OCR 解析出的长宽高最大值，按阈值映射为 S/M/L/X；
     * 推断结果不在尺寸字典中时，按等级距离降级为最接近且存在的码（如 X→L），
     * 避免"推断出字典不存在的码 → 业务编码生成整体失败"；
     * 无法推断或字典中无任何可降级码时返回 null。</p>
     *
     * @param labels AI 识别标签
     * @return 尺寸码（字典中存在的 S/M/L/X 等）或 null
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
        // 阈值按 mm 设计，OCR 尺寸可能带 cm/m/inch 单位，先统一换算为 mm 再比较
        max = Math.round(max * unitToMmFactor(dims.getUnit()));
        return inferSizeCodeFromMm(max);
    }

    /**
     * 按最大边毫米数推断尺寸码（字典感知降级）。
     *
     * <p>供 OCR 无尺寸时的变体尺寸回退路径使用：变体 dimensions JSON / sizeText
     * 解析出最大边毫米数后据此推断。推断不出或字典中无可降级码时返回 null。</p>
     *
     * @param maxMm 最大边尺寸（毫米）
     * @return 尺寸码（字典中存在的 S/M/L/X 等）或 null
     */
    public String inferSizeCodeFromMm(Long maxMm) {
        if (maxMm == null || maxMm <= 0) {
            return null;
        }
        String inferred;
        if (maxMm < SIZE_SMALL_THRESHOLD) {
            inferred = "S";
        } else if (maxMm < SIZE_MEDIUM_THRESHOLD) {
            inferred = "M";
        } else if (maxMm < SIZE_LARGE_THRESHOLD) {
            inferred = "L";
        } else {
            inferred = "X";
        }
        return degradeToExistingSizeCode(inferred);
    }

    /** 尺寸码等级（用于降级距离比较；非等级码如 SINGLE/DOUBLE 不参与）。 */
    private static final java.util.Map<String, Integer> SIZE_GRADE_RANK = java.util.Map.of(
        "S", 0, "M", 1, "L", 2, "X", 3);

    /** 尺寸单位转 mm 的换算系数；空值/未知单位按 mm 处理（OCR 默认单位即 mm）。 */
    private static double unitToMmFactor(String unit) {
        if (unit == null) {
            return 1.0;
        }
        return switch (unit.trim().toLowerCase()) {
            case "cm", "厘米" -> 10.0;
            case "m", "米" -> 1000.0;
            case "inch", "英寸" -> 25.4;
            default -> 1.0;
        };
    }

    /**
     * 推断码不在尺寸字典时降级为等级最接近且存在的码（并列时取较小档）。
     * 字典为空时不做干预（维持原行为，由 generateNextCode 统一校验）。
     */
    private String degradeToExistingSizeCode(String inferred) {
        List<CategoryDict> sizeDict = dictService.listByType("size");
        if (sizeDict == null || sizeDict.isEmpty()) {
            return inferred;
        }
        boolean exists = sizeDict.stream().anyMatch(d -> inferred.equals(d.getDictCode()));
        if (exists) {
            return inferred;
        }
        Integer inferredRank = SIZE_GRADE_RANK.get(inferred);
        String degraded = sizeDict.stream()
            .map(CategoryDict::getDictCode)
            .filter(SIZE_GRADE_RANK::containsKey)
            .min((a, b) -> {
                int distA = Math.abs(SIZE_GRADE_RANK.get(a) - inferredRank);
                int distB = Math.abs(SIZE_GRADE_RANK.get(b) - inferredRank);
                if (distA != distB) {
                    return Integer.compare(distA, distB);
                }
                // 距离相同取较小档（保守，避免夸大尺寸）
                return Integer.compare(SIZE_GRADE_RANK.get(a), SIZE_GRADE_RANK.get(b));
            })
            .orElse(null);
        if (degraded != null) {
            log.info("推断尺寸码 {} 不在字典中，降级为最接近的 {} ", inferred, degraded);
        }
        return degraded;
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
