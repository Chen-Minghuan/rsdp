package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/** 扩展品类 Shadow Mode 旁路结果。 */
@Data
@TableName("ai_category_shadow_result")
public class AiCategoryShadowResult {

    @TableId(type = IdType.AUTO)
    private Long shadowId;
    private String recognitionId;
    private String rspuId;
    private String imageId;
    private String legacyCategory;
    private String extendedCategory;
    private String productType;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sixDimResult;

    private String confidence;
    private String differenceReason;
    private String modelVersion;
    private LocalDateTime createdAt;
}
