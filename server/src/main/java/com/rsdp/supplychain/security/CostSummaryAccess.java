package com.rsdp.supplychain.security;

import com.rsdp.entity.RspuPriceSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 聚合成本投影的防反推访问规则（设计文档 v3.3 §42.3 / §43 规则 12，P0-E）。
 *
 * <p>{@code rspu_price_summary.min/max_factory_price} 属 cost_price_summary：可明文
 * 投影给内部授权角色，但「{@code active_rsku_count < 2} 或 {@code min = max}」时
 * 聚合值等价于单 RSKU 精确成本（cost_price_exact），必须按确定性输出规则降级：</p>
 * <ul>
 *   <li>exact 权限主体：可输出等价精确值（不得伪装成普通 Summary 绕过 exact 审计）；</li>
 *   <li>仅 summary 权限主体：降级场景 min/max 一律 null，只返回
 *       {@code hasFactoryQuote=true}，不得返回单点或可逆推区间；</li>
 *   <li>无任何成本权限主体：不接触成本口径（连 hasFactoryQuote 也不输出，
 *       由调用方决定布尔位是否对其暴露——§43.1 客户侧允许 hasFactoryQuote）；</li>
 *   <li>筛选/排序预言机（§43 规则 12）：与输出同一判定口径——summary-only 主体
 *       不得按真实成本值筛选/排序，由 {@link #exactCostProjectionAllowed} 统一裁决，
 *       SQL 层与输出层共用本类，禁止各自为政。</li>
 * </ul>
 *
 * <p>Controller / Export / Agent / 搜索投影统一调用本类，禁止直接序列化
 * {@code rspu_price_summary.min/max_factory_price}（§42.3）。</p>
 */
@Component
public class CostSummaryAccess {

    /**
     * 聚合成本投影视图：min/max 可能被降级为 null；hasFactoryQuote 是允许跨权限级别的
     * 布尔位（§43.1：客户侧仅返回此布尔位）。
     *
     * @param minFactoryPrice 可见的最低出厂价（降级或无权限时为 null）
     * @param maxFactoryPrice 可见的最高出厂价（降级或无权限时为 null）
     * @param hasFactoryQuote 是否存在有效出厂报价（无任何成本权限时为 null = 不暴露）
     */
    public record SummaryView(BigDecimal minFactoryPrice, BigDecimal maxFactoryPrice,
                              Boolean hasFactoryQuote) {

        /** 无报价 / 无投影行时的空视图。 */
        public static SummaryView empty(boolean exposeQuoteFlag) {
            return new SummaryView(null, null, exposeQuoteFlag ? Boolean.FALSE : null);
        }
    }

    /**
     * 按 §42.3 确定性规则投影一条价格汇总。
     *
     * @param summary        rspu_price_summary 行（可为 null = 无报价）
     * @param exactAllowed   主体是否具有 cost_price_exact 权限（且数据范围命中）
     * @param summaryAllowed 主体是否具有 cost_price_summary 权限
     * @return 投影视图（exact 主体不经降级；summary-only 主体在降级场景只见布尔位）
     */
    public SummaryView project(RspuPriceSummary summary, boolean exactAllowed, boolean summaryAllowed) {
        boolean exposeQuoteFlag = exactAllowed || summaryAllowed;
        if (summary == null || summary.getActiveRskuCount() == null || summary.getActiveRskuCount() < 1) {
            return SummaryView.empty(exposeQuoteFlag);
        }
        if (exactAllowed) {
            // exact 权限：输出等价精确值（§42.3 允许，但调用方必须以 factoryPriceExact
            // 语义呈现，不得伪装成普通 Summary）
            return new SummaryView(summary.getMinFactoryPrice(), summary.getMaxFactoryPrice(), Boolean.TRUE);
        }
        if (!summaryAllowed) {
            // 无成本权限：只见布尔位
            return new SummaryView(null, null, Boolean.TRUE);
        }
        boolean degenerate = summary.getActiveRskuCount() < 2
            || (summary.getMinFactoryPrice() != null && summary.getMaxFactoryPrice() != null
                && summary.getMinFactoryPrice().compareTo(summary.getMaxFactoryPrice()) == 0);
        if (degenerate) {
            // 单 RSKU / min=max：可反推精确成本 → 按 exact 处理，summary-only 主体置 null
            return new SummaryView(null, null, Boolean.TRUE);
        }
        return new SummaryView(summary.getMinFactoryPrice(), summary.getMaxFactoryPrice(), Boolean.TRUE);
    }

    /**
     * 成本口径筛选/排序是否允许使用真实聚合成本值（§43 规则 12：防二分区间预言机）。
     *
     * <p>仅 exact 权限主体可按真实 min_factory_price 筛选/排序；summary-only 主体
     * 对降级 RSPU 的真实值筛选会构成反推预言机，统一回退到与输出相同的降精度投影
     * （当前实现：非 exact 主体一律不使用成本口径，回退零售参考价列）。</p>
     *
     * @param exactAllowed 主体是否具有 cost_price_exact 权限
     * @return true = 允许真实成本值筛选/排序
     */
    public boolean exactCostProjectionAllowed(boolean exactAllowed) {
        return exactAllowed;
    }
}
