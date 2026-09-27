package com.rsdp.service;

import com.rsdp.dto.SupplierConfigurationDryRunReport;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.supplychain.rsku.SupplierConfigurationKeys;
import com.rsdp.util.SupplierConfigurationNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Supplier Configuration 受控治理服务（设计文档 v3.3 §18.3，P0-D）。
 *
 * <p>两件工具：</p>
 * <ol>
 *   <li><b>TEXT→CODE 受控 Key Migration</b>：单一事务内
 *       {@code SELECT 原 RSKU FOR UPDATE → 检查目标 (variant, factory, CODE key)
 *       未被其他有效 RSKU 占用 → 保留 rsku_id，更新 supplier_configuration_key /
 *       supplier_material_grade_code / normalizer_version → 记录审计与
 *       Identity Migration Result}。目标被占用即冲突，不自动合并、不新建第二条 RSKU；</li>
 *   <li><b>Normalizer 升级 Dry Run</b>：对全部有效 Supplier Configuration 以目标版本
 *       重算 Key，分类 stable / one-to-one / converged / ambiguous；只有
 *       stable / one-to-one 可自动处理，converged / ambiguous 清零前不得切换
 *       Normalizer Version（{@link SupplierConfigurationDryRunReport#isSwitchAllowed()}）。
 *       升级不允许在在线导入中逐条静默重写 Key。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierConfigurationMigrationService {

    private final RskuSupplyMapper rskuSupplyMapper;
    private final AuditLogService auditLogService;

    /**
     * Key Migration 结果（§18.3 Identity Migration Result）。
     *
     * @param rskuId            保持不变的 RSKU ID
     * @param oldKey            旧 supplier_configuration_key
     * @param newKey            新 CODE Key
     * @param normalizerVersion 写入的归一版本
     * @param operator          操作人
     */
    public record KeyMigrationResult(String rskuId, String oldKey, String newKey,
                                     String normalizerVersion, String operator) {
    }

    /**
     * TEXT→CODE 受控 Key Migration（§18.3 单事务流程）。
     *
     * <p>保留 {@code rsku_id}，只更新 Key 表达；目标 CODE Key 被同一
     * Variant + Factory 下其他有效 RSKU 占用时抛 {@link BusinessException}
     * （冲突治理语义，不自动合并）。当前 Key 不是 TEXT 时拒绝迁移。</p>
     *
     * @param rskuId       待迁移 RSKU
     * @param rawGradeCode 治理确认的稳定配置/等级码（原文，迁移时按当前归一版本生成 CODE Key）
     * @param operator     操作人
     * @return Migration 结果
     */
    @Transactional
    public KeyMigrationResult migrateTextKeyToCode(String rskuId, String rawGradeCode, String operator) {
        if (!StringUtils.hasText(rawGradeCode)) {
            throw new BusinessException("目标等级码不能为空");
        }
        RskuSupply rsku = rskuSupplyMapper.selectAliveByIdForUpdate(rskuId);
        if (rsku == null) {
            throw new ResourceNotFoundException("RSKU 不存在或已删除: " + rskuId);
        }
        String oldKey = rsku.getSupplierConfigurationKey();
        if (SupplierConfigurationKeys.kindOf(oldKey) != SupplierConfigurationKeys.Kind.TEXT) {
            throw new BusinessException("仅 TEXT Key 支持受控升级为 CODE，当前 Key: " + oldKey);
        }

        String newKey = SupplierConfigurationKeys.of(rawGradeCode, null);
        if (SupplierConfigurationKeys.kindOf(newKey) != SupplierConfigurationKeys.Kind.CODE) {
            throw new BusinessException("目标等级码归一后为空，无法生成 CODE Key");
        }

        RskuSupply occupant = rskuSupplyMapper.selectAliveByConfig(
            rsku.getVariantId(), rsku.getFactoryCode(), newKey);
        if (occupant != null && !occupant.getRskuId().equals(rskuId)) {
            throw new BusinessException("目标 CODE Key 已被同一 Variant+Factory 下的另一 RSKU 占用: "
                + occupant.getRskuId() + "，进入冲突治理，不自动合并");
        }

        String oldSnapshot = oldKey;
        String newVersion = String.valueOf(SupplierConfigurationNormalizer.CURRENT_VERSION);
        rsku.setSupplierConfigurationKey(newKey);
        rsku.setSupplierMaterialGradeCode(rawGradeCode.trim());
        rsku.setSupplierConfigurationNormalizerVersion(newVersion);
        rskuSupplyMapper.updateById(rsku);

        auditLogService.logUpdate("rsku_supply", rskuId,
            Map.of("supplierConfigurationKey", oldSnapshot),
            Map.of("action", "supplierConfigurationKeyMigration",
                "oldKey", oldSnapshot,
                "newKey", newKey,
                "normalizerVersion", newVersion),
            operator);
        log.info("Supplier Configuration TEXT→CODE 受控迁移完成: rskuId={}, {} -> {}", rskuId, oldSnapshot, newKey);
        return new KeyMigrationResult(rskuId, oldSnapshot, newKey, newVersion, operator);
    }

    /**
     * Normalizer 升级 Dry Run（§18.3）：对全部有效 Supplier Configuration 以目标版本
     * 重算 Key 并分类。
     *
     * <p>重算材料：优先使用 RSKU 保留的供应商等级原文
     * （{@code supplier_material_grade_code/text}）；等级列缺失时退化使用当前 Key 的
     * 身份段载荷（CODE:/TEXT: 后的归一值）——载荷已是旧版本归一结果，以原文待遇重算
     * 可能放大差异，该近似只影响 Dry Run 的分类保守性（倾向 one-to-one/converged），
     * 不会把不同配置误判为 stable。BASE 行恒为 stable。</p>
     *
     * @param targetVersion 目标归一版本（必须受支持且不同于当前线上版本才有意义）
     * @return Dry Run 报告；{@code converged / ambiguous} 未清零前 isSwitchAllowed=false
     */
    public SupplierConfigurationDryRunReport dryRunNormalizerUpgrade(int targetVersion) {
        if (!SupplierConfigurationNormalizer.isSupported(targetVersion)) {
            throw new IllegalArgumentException(
                "Unsupported supplier_configuration_normalizer_version: " + targetVersion);
        }
        List<RskuSupply> rows = rskuSupplyMapper.selectAliveForConfigurationScan();

        int stable = 0;
        int unknownVersion = 0;
        List<SupplierConfigurationDryRunReport.KeyChange> oneToOne = new ArrayList<>();
        // (variant, factory, newKey) → 旧 Key 集合 / RSKU 集合（收敛检测）
        Map<String, TreeSet<String>> newKeyToOldKeys = new LinkedHashMap<>();
        Map<String, TreeSet<String>> newKeyToRskuIds = new LinkedHashMap<>();
        // 当前已占用 Key 快照：(variant, factory, key) → rskuId（占用检测）
        Map<String, String> currentKeyOwner = new LinkedHashMap<>();

        for (RskuSupply row : rows) {
            currentKeyOwner.put(identityKey(row.getVariantId(), row.getFactoryCode(),
                row.getSupplierConfigurationKey()), row.getRskuId());
        }

        // 每条身份的旧→新映射（先全量计算，再分类，保证分组完整）
        record Recomputed(RskuSupply row, String newKey) { }
        List<Recomputed> recomputed = new ArrayList<>(rows.size());
        for (RskuSupply row : rows) {
            if (parseVersion(row.getSupplierConfigurationNormalizerVersion()) == null) {
                unknownVersion++;
            }
            recomputed.add(new Recomputed(row, recomputeKey(row, targetVersion)));
        }

        for (Recomputed rc : recomputed) {
            RskuSupply row = rc.row();
            String oldKey = row.getSupplierConfigurationKey();
            String newKey = rc.newKey();
            if (oldKey.equals(newKey)) {
                stable++;
                continue;
            }
            String groupKey = identityKey(row.getVariantId(), row.getFactoryCode(), newKey);
            newKeyToOldKeys.computeIfAbsent(groupKey, k -> new TreeSet<>()).add(oldKey);
            newKeyToRskuIds.computeIfAbsent(groupKey, k -> new TreeSet<>()).add(row.getRskuId());
        }

        // 收敛/歧义/一一对应分类
        List<SupplierConfigurationDryRunReport.KeyConvergence> converged = new ArrayList<>();
        List<SupplierConfigurationDryRunReport.KeyAmbiguity> ambiguous = new ArrayList<>();
        TreeSet<String> convergedGroupKeys = new TreeSet<>();
        for (Map.Entry<String, TreeSet<String>> e : newKeyToOldKeys.entrySet()) {
            if (e.getValue().size() > 1) {
                convergedGroupKeys.add(e.getKey());
            }
        }
        for (Recomputed rc : recomputed) {
            RskuSupply row = rc.row();
            String oldKey = row.getSupplierConfigurationKey();
            String newKey = rc.newKey();
            if (oldKey.equals(newKey)) {
                continue;
            }
            String groupKey = identityKey(row.getVariantId(), row.getFactoryCode(), newKey);
            if (convergedGroupKeys.contains(groupKey)) {
                continue; // 整组按 converged 输出一次
            }
            String occupant = currentKeyOwner.get(groupKey);
            if (occupant != null && !occupant.equals(row.getRskuId())) {
                ambiguous.add(new SupplierConfigurationDryRunReport.KeyAmbiguity(
                    row.getRskuId(), row.getVariantId(), row.getFactoryCode(),
                    oldKey, newKey, occupant));
                continue;
            }
            oneToOne.add(new SupplierConfigurationDryRunReport.KeyChange(
                row.getRskuId(), row.getVariantId(), row.getFactoryCode(),
                oldKey, newKey, row.getSupplierConfigurationNormalizerVersion()));
        }
        for (String groupKey : convergedGroupKeys) {
            String[] parts = groupKey.split(KEY_SEPARATOR, -1);
            converged.add(new SupplierConfigurationDryRunReport.KeyConvergence(
                parts[0], parts[1], parts[2],
                List.copyOf(newKeyToOldKeys.get(groupKey)),
                List.copyOf(newKeyToRskuIds.get(groupKey))));
        }

        if (!converged.isEmpty() || !ambiguous.isEmpty()) {
            log.warn("Supplier Configuration Normalizer Dry Run: converged={}, ambiguous={}，冲突清零前不得切换版本",
                converged.size(), ambiguous.size());
        }
        return new SupplierConfigurationDryRunReport(
            targetVersion, rows.size(), stable,
            List.copyOf(oneToOne), List.copyOf(converged), List.copyOf(ambiguous),
            unknownVersion);
    }

    /**
     * 以目标版本重算一行的 Supplier Configuration Key。
     * 有等级原文按原文重算；否则退化用当前 Key 载荷（见类注释的近似说明）。
     */
    private String recomputeKey(RskuSupply row, int targetVersion) {
        if (StringUtils.hasText(row.getSupplierMaterialGradeCode())
            || StringUtils.hasText(row.getSupplierMaterialGradeText())) {
            return SupplierConfigurationKeys.of(
                row.getSupplierMaterialGradeCode(), row.getSupplierMaterialGradeText(), targetVersion);
        }
        SupplierConfigurationKeys.Kind kind = SupplierConfigurationKeys.kindOf(row.getSupplierConfigurationKey());
        if (kind == SupplierConfigurationKeys.Kind.BASE) {
            return SupplierConfigurationKeys.BASE;
        }
        String payload = SupplierConfigurationKeys.payloadOf(row.getSupplierConfigurationKey());
        String recomputed = kind == SupplierConfigurationKeys.Kind.CODE
            ? SupplierConfigurationKeys.CODE_PREFIX
                + nullToEmpty(SupplierConfigurationNormalizer.normalizeCode(payload, targetVersion))
            : SupplierConfigurationKeys.TEXT_PREFIX
                + nullToEmpty(SupplierConfigurationNormalizer.normalizeText(payload, targetVersion));
        return recomputed;
    }

    private static Integer parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(version.trim());
            return SupplierConfigurationNormalizer.isSupported(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 身份键内部分隔符（控制字符，不会出现在编码原文中）。 */
    private static final String KEY_SEPARATOR = "\u0001";

    private static String identityKey(String variantId, String factoryCode, String key) {
        return variantId + KEY_SEPARATOR + factoryCode + KEY_SEPARATOR + key;
    }
}
