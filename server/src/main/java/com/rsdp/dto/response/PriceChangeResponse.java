package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 价格变动提示项。
 * 用于方案重新生成报价单时，提示用户哪些 RSKU 的价格较保存时发生了变化。
 */
@Data
public class PriceChangeResponse {

    /** RSPU ID。 */
    private String rspuId;

    /** RSPU 名称。 */
    private String rspuName;

    /** RSKU ID。 */
    private String rskuId;

    /** 保存方案时的价格（快照）。 */
    private BigDecimal oldPrice;

    /** 当前最新价格。 */
    private BigDecimal newPrice;
}
