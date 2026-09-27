package com.rsdp.dto;

import java.util.List;

/**
 * Supplier Configuration Normalizer 升级 Dry Run 报告（设计文档 v3.3 §18.3，P0-D）。
 *
 * <p>切换 Normalizer Version 前必须对全部有效 Supplier Configuration 做 Dry Run，
 * 分类：</p>
 * <ul>
 *   <li>{@code stable}：新旧结果不变；</li>
 *   <li>{@code one-to-one}：唯一安全更名；</li>
 *   <li>{@code converged}：多个旧 Key 收敛到同一新 Key；</li>
 *   <li>{@code ambiguous}：无法唯一绑定（新 Key 被其他配置占用等）。</li>
 * </ul>
 *
 * <p>只有 {@code stable / one-to-one} 可自动处理；{@code converged / ambiguous}
 * 必须列入报告待人工治理，冲突清零前不得切换 Normalizer Version
 * （{@link #isSwitchAllowed()} 恒为 false）。</p>
 *
 * @param targetVersion 目标归一版本
 * @param totalScanned  扫描的未软删 RSKU 数
 * @param stableCount   stable 条数
 * @param oneToOne      one-to-one 更名清单
 * @param converged     converged 冲突清单
 * @param ambiguous     ambiguous 冲突清单
 * @param unknownVersionCount 存量 normalizer version 无法解析的行数
 */
public record SupplierConfigurationDryRunReport(
    int targetVersion,
    int totalScanned,
    int stableCount,
    List<KeyChange> oneToOne,
    List<KeyConvergence> converged,
    List<KeyAmbiguity> ambiguous,
    int unknownVersionCount
) {
    /**
     * 是否允许切换 Normalizer Version（converged / ambiguous 清零才允许）。
     *
     * @return 切换许可
     */
    public boolean isSwitchAllowed() {
        return converged.isEmpty() && ambiguous.isEmpty();
    }

    /**
     * 一条唯一安全更名（one-to-one）。
     *
     * @param rskuId       RSKU ID
     * @param variantId    变体 ID
     * @param factoryCode  工厂码
     * @param oldKey       旧 supplier_configuration_key
     * @param newKey       目标版本下的新 Key
     * @param oldVersion   旧归一版本（原文，可能不可解析）
     */
    public record KeyChange(String rskuId, String variantId, String factoryCode,
                            String oldKey, String newKey, String oldVersion) {
    }

    /**
     * 多个旧 Key 收敛到同一新 Key（converged，必须人工治理）。
     *
     * @param variantId   变体 ID
     * @param factoryCode 工厂码
     * @param newKey      收敛目标 Key
     * @param oldKeys     收敛来源的全部旧 Key（升序）
     * @param rskuIds     涉及的 RSKU ID（升序）
     */
    public record KeyConvergence(String variantId, String factoryCode, String newKey,
                                 List<String> oldKeys, List<String> rskuIds) {
    }

    /**
     * 无法唯一绑定（ambiguous）：新 Key 已被另一当前有效配置占用。
     *
     * @param rskuId          待升级 RSKU ID
     * @param variantId       变体 ID
     * @param factoryCode     工厂码
     * @param oldKey          旧 Key
     * @param newKey          目标版本下的新 Key
     * @param occupantRskuId  占用新 Key 的另一 RSKU ID
     */
    public record KeyAmbiguity(String rskuId, String variantId, String factoryCode,
                               String oldKey, String newKey, String occupantRskuId) {
    }
}
