package com.rsdp.service;

import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 价格体系服务：成本价 → 标准售价 → 成交价 三级语义。
 *
 * <p><b>成本价</b>：{@code rsku_supply.factory_price}（工厂供货价，AES 加密，内部可见）；<br>
 * <b>标准售价</b>（按 RSKU 计价，允许同产品不同材质不同价）解析优先级：
 * ① {@code rspu_master.retail_price}（录入的建议销售价，有则以此为准）
 * → ② 成本 × 全局加价倍率（sys_config 键 {@code pricing.markup.global}，缺省 2.5）
 * → ③ 都空则该 RSKU 无法定价（订单/报价拦截报错"未定价"）；<br>
 * <b>成交价</b>：标准售价 × 折扣率（{@code company.price_ratio} 优先于 {@code order.price_rate}，
 * 0.9 = 九折）。售价低于成本不拦截（清库存场景），由调用方在响应中给出警告。</p>
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final ConfigService configService;

    /**
     * 解析 RSKU 的标准售价。
     *
     * <p>优先级：RSPU 建议销售价 retail_price（保留原值精度）→ 成本 × 全局加价倍率
     * （保留两位，HALF_UP）→ 都为空返回 {@code null}（调用方应拦截"未定价"）。</p>
     *
     * @param rspu 产品主档（可空）
     * @param rsku 供应单元（可空；空则视为无成本）
     * @return 标准售价；无法定价时返回 {@code null}
     */
    public BigDecimal resolveSalePrice(RspuMaster rspu, RskuSupply rsku) {
        if (rspu != null && rspu.getRetailPrice() != null) {
            return rspu.getRetailPrice();
        }
        BigDecimal factoryPrice = rsku != null ? rsku.getFactoryPrice() : null;
        if (factoryPrice != null) {
            return factoryPrice.multiply(configService.getGlobalMarkupMultiplier())
                .setScale(2, RoundingMode.HALF_UP);
        }
        return null;
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
