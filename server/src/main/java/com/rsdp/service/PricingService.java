package com.rsdp.service;

import com.rsdp.entity.PricingRule;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 价格体系服务：成本价 → 标准售价 → 成交价 三级语义。
 *
 * <p><b>成本价</b>：{@code rsku_supply.factory_price}（工厂供货价，AES 加密，内部可见）；<br>
 * <b>标准售价</b>（按 RSKU 计价，允许同产品不同材质不同价）解析优先级：
 * ① {@code rspu_master.retail_price}（录入的建议销售价，有则以此为准）
 * → ② 成本 × 品类倍率（{@code pricing_rule}，按 RSPU 品类命中）
 * → ③ 成本 × 全局倍率（sys_config 键 {@code pricing.markup.global}，缺省 2.5，兜底）
 * → ④ 都空则该 RSKU 无法定价（订单/报价拦截报错"未定价"）；<br>
 * <b>成交价</b>：标准售价 × 折扣率（{@code company.price_ratio} 优先于 {@code order.price_rate}，
 * 0.9 = 九折）。售价低于成本不拦截（清库存场景），由调用方在响应中给出警告。</p>
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    /** 售价来源：人工录入建议销售价（retail_price）。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 售价来源：成本 × 品类倍率（pricing_rule）。 */
    public static final String SOURCE_CATEGORY_RULE = "CATEGORY_RULE";
    /** 售价来源：成本 × 全局倍率（pricing.markup.global）。 */
    public static final String SOURCE_GLOBAL = "GLOBAL";
    /** 售价来源：未定价（无建议销售价且无成本）。 */
    public static final String SOURCE_NONE = "NONE";

    private final ConfigService configService;
    private final PricingRuleService pricingRuleService;

    /**
     * 标准售价解析结果（含来源信息，供定价试算与审计展示使用）。
     *
     * @param salePrice         标准售价（未定价为 {@code null}）
     * @param source            售价来源（MANUAL / CATEGORY_RULE / GLOBAL / NONE）
     * @param appliedMultiplier 生效倍率（自动计价时非空）
     * @param cost              成本价（RSKU 出厂价，可为空）
     */
    public record SalePriceDetail(BigDecimal salePrice, String source, BigDecimal appliedMultiplier,
                                  BigDecimal cost) {
    }

    /**
     * 解析 RSKU 的标准售价。
     *
     * <p>优先级：RSPU 建议销售价 retail_price（保留原值精度）→ 成本 × 品类/全局倍率
     * （保留两位，HALF_UP）→ 都为空返回 {@code null}（调用方应拦截"未定价"）。</p>
     *
     * @param rspu 产品主档（可空）
     * @param rsku 供应单元（可空；空则视为无成本）
     * @return 标准售价；无法定价时返回 {@code null}
     */
    public BigDecimal resolveSalePrice(RspuMaster rspu, RskuSupply rsku) {
        return resolveSalePriceDetail(rspu, rsku).salePrice();
    }

    /**
     * 解析 RSKU 的标准售价并返回来源信息（售价、来源、生效倍率、成本）。
     *
     * @param rspu 产品主档（可空）
     * @param rsku 供应单元（可空；空则视为无成本）
     * @return 售价解析详情
     */
    public SalePriceDetail resolveSalePriceDetail(RspuMaster rspu, RskuSupply rsku) {
        BigDecimal cost = rsku != null ? rsku.getFactoryPrice() : null;
        if (rspu != null && rspu.getRetailPrice() != null) {
            return new SalePriceDetail(rspu.getRetailPrice(), SOURCE_MANUAL, null, cost);
        }
        if (cost == null) {
            return new SalePriceDetail(null, SOURCE_NONE, null, null);
        }
        String categoryCode = rspu != null ? rspu.getCategoryCode() : null;
        PricingRule rule = StringUtils.hasText(categoryCode)
            ? pricingRuleService.listAllRules().get(categoryCode)
            : null;
        BigDecimal multiplier = rule != null ? rule.getMarkupMultiplier() : configService.getGlobalMarkupMultiplier();
        return new SalePriceDetail(
            cost.multiply(multiplier).setScale(2, RoundingMode.HALF_UP),
            rule != null ? SOURCE_CATEGORY_RULE : SOURCE_GLOBAL,
            multiplier,
            cost);
    }

    /**
     * 解析指定品类的生效加价倍率：pricing_rule 命中返回品类倍率，否则回退全局倍率。
     *
     * @param categoryCode 品类编码（可空，空则直接回退全局）
     * @return 生效倍率（&gt; 0）
     */
    public BigDecimal resolveMarkupMultiplier(String categoryCode) {
        PricingRule rule = StringUtils.hasText(categoryCode)
            ? pricingRuleService.listAllRules().get(categoryCode)
            : null;
        return rule != null ? rule.getMarkupMultiplier() : configService.getGlobalMarkupMultiplier();
    }

    /**
     * 判定售价是否低于成本（清库存场景：允许继续，仅作警告提示）。
     *
     * @param salePrice 标准售价（可空）
     * @param cost      成本价（可空）
     * @return 两者均非空且售价 &lt; 成本时返回 {@code true}
     */
    public static boolean isBelowCost(BigDecimal salePrice, BigDecimal cost) {
        return salePrice != null && cost != null && salePrice.compareTo(cost) < 0;
    }
}

