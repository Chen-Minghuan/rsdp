package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 变体实体，表示同一款式下的尺寸 × 颜色 × 材质组合。
 */
@Data
@TableName("rspu_variant")
public class RspuVariant {

    @TableId
    private String variantId;

    private String rspuId;

    private String displayName;

    private String variantCode;

    private String sizeCode;

    /** 尺寸/规格原文（工厂方言，码归一化前的事实层） */
    private String sizeText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String dimensions;

    private String colorCode;

    /** 颜色原文（工厂方言） */
    private String colorText;

    private String materialCode;

    /** 材质原文（工厂方言） */
    private String materialText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialMix;

    private String referencePriceBand;

    private String productLevel;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
