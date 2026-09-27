package com.rsdp.dto;

import lombok.Value;

import java.util.List;

/**
 * Normalizer 升级 Dry Run 报告（设计文档 v3.3 §7 第一步输出）。
 *
 * <p>迁移完成条件为 {@code zero conflict + zero stale normalizer version}（§7 第五步）；
 * {@link #isSwitchAllowed()} 为 false 时禁止执行受控迁移与版本切换。</p>
 */
@Value
public class NormalizerUpgradeDryRunReport {

    /** 目标规则集版本。 */
    int targetNormalizerVersion;
    /** 扫描的存量 distinct 身份行数。 */
    int scannedCount;
    /** 归一值不变的行数。 */
    int unchangedCount;
    /** 「旧值 → 新值」变化清单。 */
    List<NormalizerCodeChange> changes;
    /** 唯一键冲突清单（撞码）。 */
    List<NormalizerCodeConflict> conflicts;
    /** 存量数据中规则集版本无法识别（不支持/为空）的行数，需人工核查。 */
    int unknownVersionCount;

    /**
     * 是否允许进入受控迁移/切换：无任何撞码冲突且无无法识别的存量版本。
     *
     * @return true 表示 Dry Run 干净，可进入 §7 第三步
     */
    public boolean isSwitchAllowed() {
        return conflicts.isEmpty() && unknownVersionCount == 0;
    }
}
