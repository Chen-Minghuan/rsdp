package com.rsdp.service;

import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RspuCodeMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RSPU 业务编码生成服务。
 *
 * <p>编码规则（2026-10-14 起）：{category_code}-{style_code}-{sequence:4位}
 * 例：FS-MC-0001。流水按（品类 + 风格/职级）维度分配，上限 9999。</p>
 *
 * <p>规则要点：</p>
 * <ul>
 *   <li>尺寸是变体级属性，不进入款式编码（历史 4 段编码 FS-MC-001-M 合法存续，不受影响）；</li>
 *   <li>风格/职级缺失或未归一时降级为保留段 {@value #STYLE_CODE_TBD} 照常发号——
 *       编码是发放时刻的快照，发放后永不变更，风格治理改判不回改编码；</li>
 *   <li>发号并发安全：{@code WHERE rspu_code IS NULL} 条件写入 + 撞唯一索引重试一次。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuCodeService {

    /** 风格/职级缺失或未归一时的编码保留段（category_dict style 字典已收录，含义「待识别」）。 */
    public static final String STYLE_CODE_TBD = "TBD";

    private static final long MAX_SEQUENCE = 9999L;

    private final RspuCodeMapper rspuCodeMapper;
    private final DictService dictService;
    private final RspuMapper rspuMapper;
    private final AuditLogService auditLogService;

    /**
     * 生成下一个 RSPU 业务编码。
     *
     * @param categoryCode 品类码，如 FS
     * @param styleCode    风格/职级码，如 MC；缺失或不在字典时降级为 {@value #STYLE_CODE_TBD}
     * @return 业务编码，如 FS-MC-0001
     */
    public String generateNextCode(String categoryCode, String styleCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BusinessException("品类码不能为空");
        }
        String effectiveCategoryCode = categoryCode.trim().toUpperCase();
        validateDictCode("category", effectiveCategoryCode, "品类码");
        String effectiveStyleCode = resolveEffectiveStyleCode(styleCode);

        Long nextSeq = rspuCodeMapper.allocateSequence(effectiveCategoryCode, effectiveStyleCode);
        if (nextSeq == null) {
            throw new BusinessException("无法生成 RSPU 编码流水号");
        }
        if (nextSeq > MAX_SEQUENCE) {
            throw new BusinessException(
                String.format("RSPU 编码流水号已超过最大值 %d，请联系管理员扩容编码规则", MAX_SEQUENCE));
        }
        return String.format("%s-%s-%04d", effectiveCategoryCode, effectiveStyleCode, nextSeq);
    }

    /**
     * 为指定 RSPU 生成并写入业务编码。
     *
     * <p>若该 RSPU 已有业务编码，则直接返回已有编码；否则生成新编码并写入数据库。</p>
     *
     * <p>noRollbackFor = BusinessException：校验类业务异常不污染外层事务
     * （对齐 {@code RspuVariantService.createVariant} / {@code RskuService.upsertRsku} 既有模式），
     * 调用方（如 AI 识别持久化）捕获后可降级继续主流程。</p>
     *
     * @param rspuId       RSPU ID
     * @param categoryCode 品类码
     * @param styleCode    风格/职级码（缺失/未归一降级 TBD）
     * @return 生成的业务编码
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public String assignCode(String rspuId, String categoryCode, String styleCode) {
        return assignCode(rspuId, categoryCode, styleCode, null);
    }

    /**
     * 为指定 RSPU 生成并写入业务编码（显式指定审计操作人）。
     *
     * <p>异步链路（AI 识别补码）由调用方透传任务创建人；operator 为空时回落
     * {@link SecurityOperatorContext#currentUsername()}（同步链路现状）。
     * 发号改写 rspu_code 记审计，新旧值：null → 编码。</p>
     *
     * <p>并发安全：写入走条件 UPDATE（{@code WHERE rspu_code IS NULL}），并发双发时
     * 只有一个事务命中；影响 0 行说明编码已被并发发放，回读现有编码直接返回
     * （幂等安全，不覆盖）。编码撞唯一索引（uk_rspu_code_alive）时重试生成一次。</p>
     *
     * @param rspuId       RSPU ID
     * @param categoryCode 品类码
     * @param styleCode    风格/职级码（缺失/未归一降级 TBD）
     * @param operator     审计操作人（可空，空时回落当前登录用户）
     * @return 生成的业务编码
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public String assignCode(String rspuId, String categoryCode, String styleCode, String operator) {
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
        String code = generateNextCode(categoryCode, styleCode);
        int updated;
        try {
            updated = rspuMapper.assignCodeIfAbsent(rspuId, code);
        } catch (DataIntegrityViolationException e) {
            log.warn("RSPU 业务编码唯一冲突，重试生成，rspuId={}", rspuId);
            code = generateNextCode(categoryCode, styleCode);
            updated = rspuMapper.assignCodeIfAbsent(rspuId, code);
        }
        if (updated == 0) {
            // 并发已发号：回读现有编码返回（幂等安全，不覆盖、不重复记审计）
            RspuMaster current = rspuMapper.selectById(rspuId);
            if (current != null && StringUtils.hasText(current.getRspuCode())) {
                log.info("RSPU 业务编码已被并发发放，返回现有编码，rspuId={}，code={}", rspuId, current.getRspuCode());
                return current.getRspuCode();
            }
            throw new BusinessException("RSPU 业务编码发放冲突，请重试: " + rspuId);
        }
        RspuMaster oldSnapshot = new RspuMaster();
        oldSnapshot.setRspuId(rspu.getRspuId());
        oldSnapshot.setRspuCode(rspu.getRspuCode());
        rspu.setRspuCode(code);
        rspu.setUpdatedAt(java.time.LocalDateTime.now());
        auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, resolveOperator(operator));
        return code;
    }

    /**
     * 容错发号：为指定 RSPU 生成并写入业务编码，失败不抛异常。
     *
     * <p>内部调用 {@link #assignCode}，捕获 {@link BusinessException} 后记录告警并返回 null。
     * {@link #assignCode} 的事务注解含 noRollbackFor = BusinessException，
     * 捕获后不回滚外层录入事务。</p>
     *
     * @param rspuId           RSPU ID
     * @param categoryCode     品类码
     * @param styleOrGradeCode 风格/职级码（缺失/未归一降级 TBD）
     * @return 发放的编码；返回 null 表示暂无法发号，rspu_code 留空
     */
    public String tryAssignCode(String rspuId, String categoryCode, String styleOrGradeCode) {
        try {
            return assignCode(rspuId, categoryCode, styleOrGradeCode);
        } catch (BusinessException e) {
            log.warn("RSPU 业务编码暂无法发号，留空待补发，rspuId={}，categoryCode={}，原因={}",
                rspuId, categoryCode, e.getMessage());
            return null;
        }
    }

    /**
     * 解析有效风格/职级码：在 style 或 grade 字典中存在则用原码（大写），
     * 缺失/未归一时降级为保留段 {@value #STYLE_CODE_TBD}（保证任何款式都能发号）。
     */
    private String resolveEffectiveStyleCode(String styleCode) {
        if (StringUtils.hasText(styleCode)) {
            String candidate = styleCode.trim().toUpperCase();
            List<CategoryDict> styles = dictService.listByType("style");
            boolean styleExists = styles != null && styles.stream()
                .anyMatch(d -> candidate.equals(d.getDictCode()));
            if (!styleExists) {
                List<CategoryDict> grades = dictService.listByType("grade");
                styleExists = grades != null && grades.stream()
                    .anyMatch(d -> candidate.equals(d.getDictCode()));
            }
            if (styleExists) {
                return candidate;
            }
            log.info("风格/职级码未归一，编码风格段降级为 {}: {}", STYLE_CODE_TBD, styleCode);
        }
        return STYLE_CODE_TBD;
    }

    /**
     * 解析审计操作人：显式传入优先，为空回落当前登录用户。
     *
     * @param operator 显式操作人（可空）
     * @return 有效操作人
     */
    private String resolveOperator(String operator) {
        return StringUtils.hasText(operator) ? operator : SecurityOperatorContext.currentUsername();
    }

    private void validateDictCode(String dictType, String code, String label) {
        List<CategoryDict> dicts = dictService.listByType(dictType);
        boolean exists = dicts != null && dicts.stream().anyMatch(d -> code.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException(label + "不存在: " + code);
        }
    }
}
