package com.rsdp.util;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDate;

/**
 * 「可用出厂报价」统一口径（2026-09-24 评审收口）：价格汇总投影与实际选价必须共用
 * 本定义，不再各自按「未软删 + 价格非空」旧口径计算。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>未软删（{@code @TableLogic} 自动过滤）；</li>
 *   <li>{@code factory_price} 非空；</li>
 *   <li>{@code status = 'active'}（暂停/过期/停售报价不参与价格计算）；</li>
 *   <li>{@code currency_code = 'CNY'}——现阶段只做人民币业务，多币种报价不得按数字
 *       直接比较（不引入汇率系统；未来支持多币种时在此处扩展换算）；</li>
 *   <li>有效期：{@code effective_from IS NULL OR <= 今天} 且
 *       {@code effective_to IS NULL OR >= 今天}。</li>
 * </ul>
 *
 * <p>XML 查询（如 RskuSupplyMapper.xml selectCapableByRspuIds）使用同名 SQL 片段
 * {@code availableFactoryQuote}，两处必须同步修改。</p>
 */
public final class RskuAvailability {

    /** 可用状态。 */
    public static final String STATUS_ACTIVE = "active";
    /** 现阶段唯一参与价格计算的币种。 */
    public static final String CURRENCY_CNY = "CNY";

    private RskuAvailability() {
        // 工具类禁止实例化
    }

    /**
     * 按「可用出厂报价」口径约束 rsku_supply 查询（列名以 rsku_supply 表为准）。
     *
     * @param wrapper 查询条件包装器
     * @param <T>     实体类型
     * @return 同一 wrapper（链式调用）
     */
    public static <T> QueryWrapper<T> applyAvailableFactoryQuote(QueryWrapper<T> wrapper) {
        LocalDate today = LocalDate.now();
        return wrapper
            .isNotNull("factory_price")
            .eq("status", STATUS_ACTIVE)
            .eq("currency_code", CURRENCY_CNY)
            .and(w -> w.isNull("effective_from").or().le("effective_from", today))
            .and(w -> w.isNull("effective_to").or().ge("effective_to", today));
    }
}
