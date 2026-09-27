package com.rsdp.supplychain.policy;

import java.util.Objects;
import java.util.Set;

/**
 * Import Policy（设计文档 v3.3 §47.1.5，policy_schema_version=1，P0-F）。
 *
 * <p>「默认值已完整物化」的不可变 Policy Snapshot：服务端在创建 Attempt 前补齐所有
 * 默认值，再计算 Hash 并把完整正文与 Hash 在同一事务写入
 * {@code excel_import_attempt.policy_payload / policy_hash}；INSERT 后不可更改。
 * 不得包含 requestId / timestamp / 前端展示字段 / worker id。</p>
 *
 * <p>执行优先级固定（§47.1.5）：权限 / 数据库完整性 → manualCorrectionPolicy →
 * nullValuePolicy → quotePolicy。</p>
 *
 * <p>诚实性约束（2026-09-24 评审）：schema v1 的执行内核只实现了
 * {@code updateExisting / quotePolicy / overrideReason} 三个行为分支；
 * {@code allowWarnings / conflictPolicy / nullValuePolicy / manualCorrectionPolicy}
 * 的非默认值「只有协议、没有行为」，物化时直接拒绝（{@link IllegalArgumentException}），
 * 避免产出与声明不符的 Attempt。后续版本实现对应分支后解除。</p>
 */
public record ImportPolicy(
    boolean updateExisting,
    boolean allowWarnings,
    String conflictPolicy,
    String quotePolicy,
    String nullValuePolicy,
    String manualCorrectionPolicy,
    String overrideReason
) {

    /** policy_schema_version（§47.1.5），当前唯一版本。 */
    public static final int SCHEMA_VERSION = 1;

    public static final String CONFLICT_FAIL_GROUP = "fail_group";
    public static final String CONFLICT_SKIP_INVALID_TARGET = "skip_invalid_target";

    public static final String QUOTE_REPLACE_IF_NEWER = "replace_if_newer";
    public static final String QUOTE_KEEP_EXISTING = "keep_existing";
    public static final String QUOTE_FORCE_REPLACE = "force_replace";

    public static final String NULL_PRESERVE_EXISTING = "preserve_existing";
    public static final String NULL_CLEAR_EXPLICIT_ONLY = "clear_explicit_only";

    public static final String MANUAL_PRESERVE = "preserve";
    public static final String MANUAL_REPLACE_EXPLICIT = "replace_explicit";

    private static final Set<String> CONFLICT_POLICIES = Set.of(CONFLICT_FAIL_GROUP, CONFLICT_SKIP_INVALID_TARGET);
    private static final Set<String> QUOTE_POLICIES = Set.of(QUOTE_REPLACE_IF_NEWER, QUOTE_KEEP_EXISTING,
        QUOTE_FORCE_REPLACE);
    private static final Set<String> NULL_POLICIES = Set.of(NULL_PRESERVE_EXISTING, NULL_CLEAR_EXPLICIT_ONLY);
    private static final Set<String> MANUAL_POLICIES = Set.of(MANUAL_PRESERVE, MANUAL_REPLACE_EXPLICIT);

    /**
     * 默认值补齐 + 未知枚举拒绝 + overrideReason 联动校验（§47.1.5）。
     *
     * <p>稀疏输入（null / 空白）一律落默认值；每个枚举都拒绝未知值
     * （{@link IllegalArgumentException}，由调用方翻译为 400）。</p>
     *
     * @param updateExisting        null 时默认 false
     * @param allowWarnings         null 时默认 false
     * @param conflictPolicy        null 时默认 fail_group
     * @param quotePolicy           null 时默认 replace_if_newer
     * @param nullValuePolicy       null 时默认 preserve_existing
     * @param manualCorrectionPolicy null 时默认 preserve
     * @param overrideReason        force_replace 必须非空；其他 quotePolicy 必须为 null
     * @return 完整物化的 Policy
     */
    public static ImportPolicy materialize(Boolean updateExisting, Boolean allowWarnings,
                                           String conflictPolicy, String quotePolicy,
                                           String nullValuePolicy, String manualCorrectionPolicy,
                                           String overrideReason) {
        ImportPolicy policy = new ImportPolicy(
            updateExisting != null && updateExisting,
            allowWarnings != null && allowWarnings,
            normalizeEnum(conflictPolicy, CONFLICT_POLICIES, CONFLICT_FAIL_GROUP, "conflictPolicy"),
            normalizeEnum(quotePolicy, QUOTE_POLICIES, QUOTE_REPLACE_IF_NEWER, "quotePolicy"),
            normalizeEnum(nullValuePolicy, NULL_POLICIES, NULL_PRESERVE_EXISTING, "nullValuePolicy"),
            normalizeEnum(manualCorrectionPolicy, MANUAL_POLICIES, MANUAL_PRESERVE, "manualCorrectionPolicy"),
            normalizeReason(overrideReason));
        // 未实现行为分支的非默认值直接拒绝（只有协议没有行为的选项不得成功物化）
        if (policy.allowWarnings()) {
            throw new IllegalArgumentException("allowWarnings=true 当前版本未实现（Issue/Warning 体系已摘除），请勿携带");
        }
        if (!CONFLICT_FAIL_GROUP.equals(policy.conflictPolicy())) {
            throw new IllegalArgumentException("conflictPolicy=" + policy.conflictPolicy()
                + " 当前版本未实现，仅支持 " + CONFLICT_FAIL_GROUP);
        }
        if (!NULL_PRESERVE_EXISTING.equals(policy.nullValuePolicy())) {
            throw new IllegalArgumentException("nullValuePolicy=" + policy.nullValuePolicy()
                + " 当前版本未实现，仅支持 " + NULL_PRESERVE_EXISTING);
        }
        if (!MANUAL_PRESERVE.equals(policy.manualCorrectionPolicy())) {
            throw new IllegalArgumentException("manualCorrectionPolicy=" + policy.manualCorrectionPolicy()
                + " 当前版本未实现，仅支持 " + MANUAL_PRESERVE);
        }
        if (QUOTE_FORCE_REPLACE.equals(policy.quotePolicy())) {
            // force_replace：仅具备报价覆盖权限的人工导入可用，reason 必填并记审计（§47.1.5）；
            // 权限判定在 Service 层（SecurityOperatorContext），本层只校验形态
            if (policy.overrideReason() == null) {
                throw new IllegalArgumentException("quotePolicy=force_replace 时 overrideReason 必须非空（§47.1.5）");
            }
        } else if (policy.overrideReason() != null) {
            throw new IllegalArgumentException("overrideReason 只允许在 quotePolicy=force_replace 时携带（§47.1.5）");
        }
        return policy;
    }

    /** schema v1 默认 Policy（全默认值）。 */
    public static ImportPolicy defaults() {
        return materialize(null, null, null, null, null, null, null);
    }

    private static String normalizeEnum(String value, Set<String> allowed, String defaultValue, String field) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " 包含未知枚举值: " + value
                + "（允许值: " + String.join(", ", allowed.stream().sorted().toList()) + "）");
        }
        return normalized;
    }

    private static String normalizeReason(String overrideReason) {
        if (overrideReason == null || overrideReason.isBlank()) {
            return null;
        }
        return overrideReason.trim();
    }

    public boolean isForceReplace() {
        return QUOTE_FORCE_REPLACE.equals(quotePolicy);
    }

    /** 校验显式构造的 Policy 合法（反序列化路径防御）。 */
    public ImportPolicy {
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        Objects.requireNonNull(quotePolicy, "quotePolicy");
        Objects.requireNonNull(nullValuePolicy, "nullValuePolicy");
        Objects.requireNonNull(manualCorrectionPolicy, "manualCorrectionPolicy");
    }
}
