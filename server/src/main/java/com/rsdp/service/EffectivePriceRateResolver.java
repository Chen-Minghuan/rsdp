package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 生效折扣率解析器（成交价 = 标准售价 × 折扣率）。
 *
 * <p>口径单点：当前用户归属企业时企业 {@code price_ratio} 优先，否则回退全局
 * {@code price_rate}。OrderService 与营销 Agent 报价（P2）共用，避免口径漂移。</p>
 */
@Component
@RequiredArgsConstructor
public class EffectivePriceRateResolver {

    private final CompanyService companyService;
    private final ConfigService configService;

    /**
     * 解析当前操作人生效的折扣率。
     *
     * @return 生效折扣率（0.9 = 九折）
     */
    public BigDecimal resolve() {
        BigDecimal companyRate = companyService.resolveOrderPriceRate(SecurityOperatorContext.currentUserId());
        return companyRate != null ? companyRate : configService.getOrderPriceRate();
    }
}
