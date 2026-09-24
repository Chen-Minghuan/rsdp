package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/** 二级产品类型知识实体；不与 RSPU 绑定，也不参与业务编码。 */
@Data
@TableName("knowledge_product_type")
public class KnowledgeProductType {

    @TableId
    private String typeCode;
    private String typeName;
    private String businessCategoryCode;
    private String schemaCategoryCode;
    private String parentTypeCode;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String aliases;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String roomTags;

    private String description;
    private Integer sortOrder;
    private String status;
    private String knowledgeVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
