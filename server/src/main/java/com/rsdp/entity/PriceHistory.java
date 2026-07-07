package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.EncryptTypeHandler;
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
    private Long historyId;

    private String rskuId;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal oldPrice;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal newPrice;

    private String changedBy;

    private String changeReason;

    private LocalDateTime createdAt;
}
