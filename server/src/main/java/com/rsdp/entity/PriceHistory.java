package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格历史实体。
 */
@Data
@TableName("price_history")
public class PriceHistory {

    @TableId(type = IdType.AUTO)
    private Integer historyId;

    private String rskuId;

    private BigDecimal oldPrice;

    private BigDecimal newPrice;

    private String changedBy;

    private String changeReason;

    private LocalDateTime createdAt;
}
