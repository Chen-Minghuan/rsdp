package com.rsdp.supplychain.rsku;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Quote Policy 决策器（设计文档 v3.3 §47.1.5 quotePolicy 条件表，P0-F）。
 *
 * <p>纯函数，不读库；输入为「既有 current quote」与「incoming quote」的日期与
 * {@code quoteValueSame}（比较标准化后的价格 HMAC、基础口径、MOQ 和有效期等业务字段，
 * 排除 {@code quote_date}、来源坐标、密文随机性与 Runtime 字段——由调用方计算）。</p>
 *
 * <p>基础口径（currency_code + price_unit + tax_included）不同的 Quote 不走日期表，
 * 直接 {@link Decision#CONFLICT_QUOTE_BASIS}，只能在具备权限和 reason 的
 * {@code force_replace} 下处理（§47.1.5）。</p>
 *
 * <p>不得用 SQL null ordering 或遍历顺序猜测；NULL 日期语义全部显式枚举（条件表后 5 行）。</p>
 */
public final class QuotePolicyDecider {

    /**
     * 决策结果。
     */
    public enum Decision {
        /** 更新（值变化写 price_history 并 quote_version+1）。 */
        UPDATE,
        /** 值未变但日期/有效期更新：只记 validity_update，不记伪价格变更。 */
        UPDATE_VALIDITY_ONLY,
        /** 保留已有值（incoming 严格早于 existing），记 STALE_QUOTE_IGNORED。 */
        KEEP_STALE_IGNORED,
        /** 完全等值：no-op，不增长 quote_version / History。 */
        NO_OP,
        /** quotePolicy=keep_existing：不改写，记 skipped result。 */
        KEEP_EXISTING_SKIPPED,
        /** force_replace：具备权限 + reason 的人工覆盖。 */
        FORCE_REPLACE,
        /** 相同 quote_date 但业务值不同。 */
        CONFLICT_SAME_DATE,
        /** incoming 无日期、existing 有日期且业务值不同。 */
        CONFLICT_DATE_MISSING,
        /** 双均无日期且业务值不同。 */
        CONFLICT_UNDATED,
        /** 基础口径（币种/单位/含税）不同。 */
        CONFLICT_QUOTE_BASIS
    }

    /** 决策结果附带的 Issue Code（冲突/跳过场景；无则 null）。 */
    public record Outcome(Decision decision, String issueCode) {

        public boolean isConflict() {
            return decision == Decision.CONFLICT_SAME_DATE
                || decision == Decision.CONFLICT_DATE_MISSING
                || decision == Decision.CONFLICT_UNDATED
                || decision == Decision.CONFLICT_QUOTE_BASIS;
        }

        /** 是否允许写入（更新或强制覆盖）。 */
        public boolean allowsWrite() {
            return decision == Decision.UPDATE
                || decision == Decision.UPDATE_VALIDITY_ONLY
                || decision == Decision.FORCE_REPLACE;
        }
    }

    private QuotePolicyDecider() {
    }

    /**
     * 按 §47.1.5 条件表决策。
     *
     * @param quotePolicy      replace_if_newer / keep_existing / force_replace（调用方已校验枚举）
     * @param incomingQuoteDate incoming 报价日期（可空）
     * @param existingQuoteDate existing 报价日期（可空）
     * @param quoteValueSame   标准化业务值是否一致（价格 HMAC + 基础口径 + MOQ + 有效期等，
     *                         排除 quote_date；基础口径比较见下方 basisSame）
     * @param basisSame        基础口径（currency_code + price_unit + tax_included）是否一致
     * @return 决策结果（确定性：同输入必同输出）
     */
    public static Outcome decide(String quotePolicy,
                                 LocalDate incomingQuoteDate,
                                 LocalDate existingQuoteDate,
                                 boolean quoteValueSame,
                                 boolean basisSame) {
        Objects.requireNonNull(quotePolicy, "quotePolicy");

        // force_replace 不隐式绕过 manualCorrectionPolicy=preserve（§47.1.5）——
        // 人工修正优先级在调用方（RskuService）先于本决策执行；到这里才处理 Quote 层
        if ("force_replace".equals(quotePolicy)) {
            return new Outcome(Decision.FORCE_REPLACE, null);
        }
        if ("keep_existing".equals(quotePolicy)) {
            return new Outcome(Decision.KEEP_EXISTING_SKIPPED, null);
        }
        // replace_if_newer（默认）：基础口径不同不跨口径自动覆盖（§47.1.5）
        if (!basisSame) {
            return new Outcome(Decision.CONFLICT_QUOTE_BASIS, "QUOTE_BASIS_CONFLICT");
        }

        if (incomingQuoteDate != null && existingQuoteDate != null) {
            if (incomingQuoteDate.isAfter(existingQuoteDate)) {
                // 严格晚于：更新；值未变时只记 validity_update，不记伪价格变更
                return new Outcome(quoteValueSame ? Decision.UPDATE_VALIDITY_ONLY : Decision.UPDATE, null);
            }
            if (incomingQuoteDate.isBefore(existingQuoteDate)) {
                return new Outcome(Decision.KEEP_STALE_IGNORED, "STALE_QUOTE_IGNORED");
            }
            // 相同日期
            return quoteValueSame
                ? new Outcome(Decision.NO_OP, null)
                : new Outcome(Decision.CONFLICT_SAME_DATE, "QUOTE_SAME_DATE_CONFLICT");
        }
        if (incomingQuoteDate != null) {
            // incoming 有值 / existing NULL：有日期事实优先于无日期事实
            return new Outcome(Decision.UPDATE, null);
        }
        if (existingQuoteDate != null) {
            return quoteValueSame
                ? new Outcome(Decision.NO_OP, null)
                : new Outcome(Decision.CONFLICT_DATE_MISSING, "QUOTE_DATE_MISSING_CONFLICT");
        }
        return quoteValueSame
            ? new Outcome(Decision.NO_OP, null)
            : new Outcome(Decision.CONFLICT_UNDATED, "QUOTE_UNDATED_CONFLICT");
    }
}
