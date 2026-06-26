package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 款式变体表：一个具体的尺寸 × 颜色 × 材质组合。
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

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String dimensions;

    private String colorCode;

    private String materialCode;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialMix;

    private String referencePriceBand;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
