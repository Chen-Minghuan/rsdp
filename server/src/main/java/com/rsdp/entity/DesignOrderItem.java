package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.EncryptTypeHandler;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设计订单明细实体（商品快照）。
 */
@Data
@TableName("design_order_item")
public class DesignOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderId;
    private String rspuId;
    private String rskuId;
    private String variantId;
    private String productName;
    private String model;
    private String imageId;
    private Integer quantity;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal originalPrice;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal finalPrice;

    private String factoryCode;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshotJson;

    private LocalDateTime createdAt;
}
