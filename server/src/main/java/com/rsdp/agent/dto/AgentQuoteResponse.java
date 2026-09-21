package com.rsdp.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话报价响应（报价卡片）。
 *
 * <p>红线：只含售价口径字段（标准售价/折扣率/预计成交价），
 * 绝不出现 factoryPrice/cost/margin 任何成本字段（序列化断言测试守卫）。</p>
 */
@Data
public class AgentQuoteResponse {

    private String quoteId;

    private List<QuoteLine> lines;

    /** 标准售价合计（仅含可报价行）。 */
    private BigDecimal listTotal;

    /** 生效折扣率（企业 price_ratio 优先于全局 price_rate）。 */
    private BigDecimal priceRate;

    /** 预计成交合计（listTotal × priceRate）。 */
    private BigDecimal dealTotal;

    /** 最长交期（天，可报价行取 max）。 */
    private Integer maxLeadTimeDays;

    private LocalDateTime generatedAt;

    /** 口径提示文案。 */
    private String priceNote;

    /** 报价行。 */
    @Data
    public static class QuoteLine {

        private String confirmedItemId;

        private String rspuId;

        /** 选中的最低售价 RSKU（不可报价时为 null）。 */
        private String rskuId;

        private String productName;

        private String primaryImageUrl;

        private Integer quantity;

        /** 标准售价单价（不可报价时为 null）。 */
        private BigDecimal unitSalePrice;

        /** 售价来源（MANUAL/CATEGORY_RULE/GLOBAL；不可报价时为 null）。 */
        private String priceSource;

        /** 小计（unitSalePrice × quantity；不可报价时为 null）。 */
        private BigDecimal subtotal;

        private Integer leadTimeDays;

        /** 是否可报价（false = 暂不可报价，该行不计入合计）。 */
        private Boolean quotable;
    }
}
