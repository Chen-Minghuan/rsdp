package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RSPU 价格投影汇总实体。
 *
 * <p>只存业务允许暴露的聚合指标（min/max 出厂价、有效 RSKU 数），
 * 安全口径见 {@code database/migrations/V44__rspu_price_summary.sql} 表注释；
 * 单 RSKU 精确报价仍只存于 {@code rsku_supply.factory_price} 加密列。</p>
 */
@Data
@TableName("rspu_price_summary")
public class RspuPriceSummary {

    @TableId
    private String rspuId;
    private BigDecimal minFactoryPrice;
    private BigDecimal maxFactoryPrice;
    private Integer activeRskuCount;
    private LocalDateTime updatedAt;
}
