package com.rsdp.service;

import com.rsdp.dto.NormalizerCodeChange;
import com.rsdp.dto.NormalizerCodeConflict;
import com.rsdp.dto.NormalizerUpgradeDryRunReport;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.mapper.SupplierProductRefMapper;
import com.rsdp.util.SourceCodeNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Source Product Code Normalizer 升级 Dry Run 服务骨架（设计文档 v3.3 §7 五步流程）。
 *
 * <p>本类实现可离线执行的部分：</p>
 * <ol>
 *   <li>扫描存量 distinct 源编码身份行；</li>
 *   <li>以目标版本重算归一值，输出「旧值 → 新值」变化清单；</li>
 *   <li>检出按目标版本归一后会撞唯一键
 *       {@code (factory_code, source_namespace, normalized_source_product_code)} 的冲突，
 *       列入报告并以 WARN 日志输出——冲突未治理前
 *       {@link NormalizerUpgradeDryRunReport#isSwitchAllowed()} 恒为 false
 *       （治理队列持久化表已于 2026-09-24 评审后取消：Dry Run 可重复执行，
 *       报告即事实源）；</li>
 * </ol>
 *
 * <p>不在本类范围（后续阶段）：受控迁移中的原子更新（第三步）、读切换与写切换（第四步）。</p>
 */
@Slf4j
@Service
public class SourceCodeNormalizerDryRunService {

    private final SupplierProductRefMapper supplierProductRefMapper;

    public SourceCodeNormalizerDryRunService(SupplierProductRefMapper supplierProductRefMapper) {
        this.supplierProductRefMapper = supplierProductRefMapper;
    }

    /**
     * 以目标规则集版本对全量 Supplier Ref 做 Dry Run。
     *
     * @param targetVersion 目标 normalizer 版本（必须受支持）
     * @return Dry Run 报告（变化清单 + 撞码冲突清单 + 切换许可判定）
     * @throws IllegalArgumentException 目标版本不受支持
     */
    public NormalizerUpgradeDryRunReport dryRun(int targetVersion) {
        if (!SourceCodeNormalizer.isSupported(targetVersion)) {
            throw new IllegalArgumentException("Unsupported code_normalizer_version: " + targetVersion);
        }

        // 第一步：扫描存量 distinct 源编码
        List<SupplierProductRef> rows = supplierProductRefMapper.selectDistinctIdentityCodes();

        // 第二步：新旧版本归一值对比
        List<NormalizerCodeChange> changes = new ArrayList<>();
        int unchanged = 0;
        int unknownVersion = 0;
        // key: factory + namespace + 新归一值 → 撞码候选的全部不同原文
        Map<String, Map<String, TreeSet<String>>> newValueToRawCodes = new LinkedHashMap<>();

        for (SupplierProductRef row : rows) {
            String oldVersion = row.getCodeNormalizerVersion();
            boolean versionKnown = parseVersion(oldVersion) != null;
            if (!versionKnown) {
                unknownVersion++;
            }

            String newNormalized = SourceCodeNormalizer.normalize(row.getSourceProductCode(), targetVersion);
            boolean valueChanged = !newNormalized.equals(row.getNormalizedSourceProductCode());
            Integer oldVersionNumber = parseVersion(oldVersion);
            boolean versionChanged = oldVersionNumber == null || oldVersionNumber != targetVersion;

            if (valueChanged || versionChanged) {
                changes.add(new NormalizerCodeChange(
                    row.getFactoryCode(),
                    row.getSourceNamespace(),
                    row.getSourceProductCode(),
                    row.getNormalizedSourceProductCode(),
                    newNormalized,
                    oldVersion,
                    targetVersion));
            } else {
                unchanged++;
            }

            newValueToRawCodes
                .computeIfAbsent(identityKey(row.getFactoryCode(), row.getSourceNamespace()), k -> new LinkedHashMap<>())
                .computeIfAbsent(newNormalized, k -> new TreeSet<>())
                .add(row.getSourceProductCode());
        }

        // 撞码检出：同一 (factory, namespace) 下，同一新归一值对应多个不同原文 → 迁移必撞唯一键
        List<NormalizerCodeConflict> conflicts = new ArrayList<>();
        newValueToRawCodes.forEach((identityKey, byNewValue) -> byNewValue.forEach((newValue, raws) -> {
            if (raws.size() > 1) {
                String[] parts = identityKey.split(KEY_SEPARATOR, -1);
                conflicts.add(new NormalizerCodeConflict(parts[0], parts[1], newValue, List.copyOf(raws)));
            }
        }));

        // 冲突列入报告并 WARN 输出（不持久化；未治理前 isSwitchAllowed 恒 false）
        if (!conflicts.isEmpty()) {
            log.warn("Normalizer 升级 Dry Run 检出 {} 起撞码冲突，未治理前禁止切换", conflicts.size());
        }

        return new NormalizerUpgradeDryRunReport(
            targetVersion, rows.size(), unchanged, List.copyOf(changes), List.copyOf(conflicts), unknownVersion);
    }

    private static Integer parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(version.trim());
            return SourceCodeNormalizer.isSupported(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 身份键内部分隔符（控制字符，不会出现在编码原文中）。 */
    private static final String KEY_SEPARATOR = "\u0001";

    private static String identityKey(String factoryCode, String sourceNamespace) {
        return factoryCode + KEY_SEPARATOR + sourceNamespace;
    }
}
