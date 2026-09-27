package com.rsdp.service;

import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.supplychain.rsku.SupplierConfigurationKeys;
import com.rsdp.util.SupplierConfigurationNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Supplier Configuration Resolver（设计文档 v3.3 §18.3，P0-D）。
 *
 * <p>职责：把一次报价输入携带的供应商等级/配置信息解析为 RSKU Identity
 * {@code (variant_id, factory_code, supplier_configuration_key)}，保证
 * 「同一配置从 TEXT 获得稳定 CODE 或 Normalizer 升级后仍指向原 rsku_id」。</p>
 *
 * <p>固定解析顺序（§18.3）：</p>
 * <ol>
 *   <li>在同一 {@code variant_id + factory_code} 下查找已存在的精确 CODE Key；</li>
 *   <li>未命中时，利用输入同时携带的 code/text 与 RSKU 保留的
 *       {@code supplier_material_grade_code/text}（已治理的 code↔text 映射的事实载体）
 *       匹配旧 TEXT Key；</li>
 *   <li>精确命中一条 → 绑定原 {@code rsku_id}；零条且不存在无法排除的 TEXT/BASE 候选
 *       → 允许创建新 CODE Key；输入只有 code 但存在未治理旧候选、命中多条、或新 CODE Key
 *       已属于另一 RSKU → {@code SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT}；</li>
 *   <li>绑定不自动重写存量 Key——TEXT→CODE 的 Key 升级由
 *       {@link SupplierConfigurationMigrationService} 受控执行（单事务 SELECT FOR UPDATE）。</li>
 * </ol>
 *
 * <p>本类只做解析决策，不写库；创建/冲突 Issue 的持久化由调用方负责。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierConfigurationResolver {

    /** 冲突 Issue 代码（§18.3，quote_blocking 目标）。 */
    public static final String ISSUE_SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT =
        "SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT";

    private final RskuSupplyMapper rskuSupplyMapper;

    /**
     * 解析结果类型。
     */
    public enum ResolutionKind {
        /** 精确命中既有 Key（CODE/TEXT/BASE），绑定原 rsku_id。 */
        EXACT_HIT,
        /** 输入为 CODE，经 code↔text 对应关系命中旧 TEXT Key 的原 RSKU（绑定不升级，
         *  Key 迁移走受控流程）。 */
        BOUND_LEGACY_TEXT_KEY,
        /** 输入为 TEXT，经原文对应命中已升级 CODE Key 的原 RSKU。 */
        BOUND_UPGRADED_CODE_KEY,
        /** 允许以该 Key 创建新 RSKU（零候选且无无法排除的旧候选）。 */
        CREATE_NEW,
        /** 身份冲突：命中多条 / 存在无法排除的旧候选。不生成 targetRskuKey、不进入 Persist。 */
        CONFLICT
    }

    /**
     * 解析结果。
     *
     * @param kind                     结果类型
     * @param rskuId                   命中的原 RSKU ID（EXACT_HIT / BOUND_* 时非空）
     * @param supplierConfigurationKey 解析落定的 Key（CREATE_NEW 时为候选新 Key；
     *                                 命中时为该 RSKU 当前持有的 Key——绑定不自动升级 Key）
     * @param conflictReason           冲突原因（CONFLICT 时非空）
     */
    public record Resolution(ResolutionKind kind, String rskuId,
                             String supplierConfigurationKey, String conflictReason) {

        public boolean isConflict() {
            return kind == ResolutionKind.CONFLICT;
        }

        public boolean isCreateNew() {
            return kind == ResolutionKind.CREATE_NEW;
        }

        static Resolution hit(ResolutionKind kind, RskuSupply rsku) {
            return new Resolution(kind, rsku.getRskuId(), rsku.getSupplierConfigurationKey(), null);
        }

        static Resolution conflict(String reason) {
            return new Resolution(ResolutionKind.CONFLICT, null, null, reason);
        }
    }

    /**
     * 解析一次报价输入的 Supplier Configuration Identity。
     *
     * @param variantId   目标变体 ID（非空）
     * @param factoryCode 工厂码（非空）
     * @param gradeCode   供应商原始等级/配置码（可空）
     * @param gradeText   供应商原始等级/配置原文（可空）
     * @return 解析结果
     */
    public Resolution resolve(String variantId, String factoryCode, String gradeCode, String gradeText) {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(factoryCode, "factoryCode");

        String candidateKey = SupplierConfigurationKeys.of(gradeCode, gradeText);
        SupplierConfigurationKeys.Kind kind = SupplierConfigurationKeys.kindOf(candidateKey);

        // 步骤 1：精确 Key 命中（CODE/TEXT/BASE 同一查询路径）
        RskuSupply exact = rskuSupplyMapper.selectAliveByConfig(variantId, factoryCode, candidateKey);
        if (exact != null) {
            return Resolution.hit(ResolutionKind.EXACT_HIT, exact);
        }

        List<RskuSupply> aliveRows = rskuSupplyMapper.selectAliveByVariantFactory(variantId, factoryCode);

        if (kind == SupplierConfigurationKeys.Kind.BASE) {
            // BASE：无配置拆分身份；未命中精确 BASE 即允许创建
            return new Resolution(ResolutionKind.CREATE_NEW, null, candidateKey, null);
        }

        if (kind == SupplierConfigurationKeys.Kind.CODE) {
            return resolveCodeKey(variantId, factoryCode, gradeCode, gradeText, candidateKey, aliveRows);
        }
        return resolveTextKey(gradeText, candidateKey, aliveRows);
    }

    /**
     * CODE 输入的后续解析（§18.3 步骤 2-3）。
     */
    private Resolution resolveCodeKey(String variantId, String factoryCode,
                                      String gradeCode, String gradeText,
                                      String candidateKey, List<RskuSupply> aliveRows) {
        String normCode = SupplierConfigurationNormalizer.normalizeCode(gradeCode);
        String normText = StringUtils.hasText(gradeText)
            ? SupplierConfigurationNormalizer.normalizeText(gradeText) : null;

        // 步骤 2：用 code↔text 对应关系匹配旧 TEXT Key 候选
        List<RskuSupply> legacyTextCandidates = aliveRows.stream()
            .filter(r -> SupplierConfigurationKeys.kindOf(r.getSupplierConfigurationKey())
                == SupplierConfigurationKeys.Kind.TEXT)
            .filter(r -> matchesGrade(r, normCode, normText))
            .toList();

        if (legacyTextCandidates.size() == 1) {
            return Resolution.hit(ResolutionKind.BOUND_LEGACY_TEXT_KEY, legacyTextCandidates.get(0));
        }
        if (legacyTextCandidates.size() > 1) {
            log.warn("{}: CODE 输入命中多条旧 TEXT Key 候选, variantId={}, factoryCode={}, key={}",
                ISSUE_SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT, variantId, factoryCode, candidateKey);
            return Resolution.conflict("CODE 输入命中多条旧 TEXT Key 候选（" + legacyTextCandidates.size()
                + " 条），需人工治理 code↔text 对应关系");
        }

        // 零候选：检查是否存在无法排除的 TEXT/BASE 旧候选（输入只有 code、旧行无等级信息时
        // 无法证明它们不是同一配置）
        if (normText == null) {
            List<RskuSupply> unexcludable = aliveRows.stream()
                .filter(r -> !StringUtils.hasText(r.getSupplierMaterialGradeCode())
                    && !StringUtils.hasText(r.getSupplierMaterialGradeText()))
                .toList();
            if (!unexcludable.isEmpty()) {
                log.warn("{}: 输入只有 code 且存在 {} 条无法排除的旧候选, variantId={}, factoryCode={}",
                    ISSUE_SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT, variantId, factoryCode,
                    unexcludable.size());
                return Resolution.conflict("输入只有 code 且同一 Variant+Factory 下存在 "
                    + unexcludable.size() + " 条无等级信息、无法排除的旧候选，需人工治理后再创建");
            }
        }
        return new Resolution(ResolutionKind.CREATE_NEW, null, candidateKey, null);
    }

    /**
     * TEXT 输入的后续解析：反向匹配已升级为 CODE Key 的原 RSKU（其保留的
     * grade text/code 与输入原文对应），防止因 Key 表达升级创建第二条 RSKU。
     */
    private Resolution resolveTextKey(String gradeText, String candidateKey,
                                      List<RskuSupply> aliveRows) {
        String normText = SupplierConfigurationNormalizer.normalizeText(gradeText);
        List<RskuSupply> upgradedCandidates = aliveRows.stream()
            .filter(r -> SupplierConfigurationKeys.kindOf(r.getSupplierConfigurationKey())
                == SupplierConfigurationKeys.Kind.CODE)
            .filter(r -> normTextEquals(r.getSupplierMaterialGradeText(), normText))
            .toList();
        if (upgradedCandidates.size() == 1) {
            return Resolution.hit(ResolutionKind.BOUND_UPGRADED_CODE_KEY, upgradedCandidates.get(0));
        }
        if (upgradedCandidates.size() > 1) {
            return Resolution.conflict("TEXT 输入命中多条已升级 CODE Key 候选（"
                + upgradedCandidates.size() + " 条），需人工治理");
        }
        return new Resolution(ResolutionKind.CREATE_NEW, null, candidateKey, null);
    }

    /**
     * code↔text 对应匹配：旧行保留的 grade code（归一后）等于输入码，
     * 或（输入同时携带 text 时）旧行 grade text（归一后）等于输入原文。
     */
    private boolean matchesGrade(RskuSupply row, String normCode, String normText) {
        if (StringUtils.hasText(row.getSupplierMaterialGradeCode())
            && SupplierConfigurationNormalizer.normalizeCode(row.getSupplierMaterialGradeCode())
                .equals(normCode)) {
            return true;
        }
        return normText != null && normTextEquals(row.getSupplierMaterialGradeText(), normText);
    }

    private boolean normTextEquals(String storedRawText, String normalizedInputText) {
        return StringUtils.hasText(storedRawText)
            && SupplierConfigurationNormalizer.normalizeText(storedRawText).equals(normalizedInputText);
    }
}
