package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/** 产品属性定义知识实体；只描述属性语义及归属层，不保存具体产品属性值。 */
@Data
@TableName("knowledge_product_attribute")
public class KnowledgeProductAttribute {

    @TableId
    private String attributeId;
    private String attributeCode;
    private String attributeName;
    private String categoryDictType;
    private String categoryCode;
    private String productTypeCode;
    private String valueLayer;
    private String valueType;
    private String unit;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String enumOptions;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String aliases;

    private String description;
    private Boolean aiExtractable;
    private String requiredLevel;
    private Integer sortOrder;
    private String status;
    private String knowledgeVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
