package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.EncryptTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * RSKU 供应单元实体。
 */
@Data
@TableName("rsku_supply")
public class RskuSupply {

    @TableId
    private String rskuId;
    private String rspuId;
    private String variantId;
    private String factoryCode;
    private String factorySku;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal factoryPrice;

    private String priceBand;
    private String productLevel;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String shippingWarehouseId;
    private String structureStrengthRating;
    private String flameRetardantCapability;
    private String factoryPhotoPath;
    private Integer factoryCreditScore;
    private BigDecimal onTimeRate;
    private BigDecimal qualityReturnRate;
    private String diffNotes;
    private String quoteConfidence;
    private String reviewStatus;
    private LocalDate priceUpdated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
