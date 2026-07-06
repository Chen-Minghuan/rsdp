package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产品集实体。
 */
@Data
@TableName("product_collection")
public class ProductCollection {

    @TableId
    private String collectionId;
    private String collectionCode;
    private String name;
    private String description;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String categoryCodes;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String styleCodes;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String targetSegments;

    private Boolean isFeatured;
    private Integer sortOrder;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
