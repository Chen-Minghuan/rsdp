package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rspu_master")
public class RspuMaster {
    @TableId
    private String rspuId;
    private String externalCode;
    /** 产品名称（AI OCR 提取 / Excel 导入品名；纯图无文字时为空） */
    private String productName;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sixDimTags;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String styleVector;

    private String colorPrimaryName;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String colorPrimaryHsv;

    private String colorSecondary;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialTags;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sceneTags;

    private String referencePriceBand;

    private String productLevel;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String budgetRange;

    private Integer warrantyYears;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String keySpecs;
    private String status;
    private String reviewStatus;
    private String reviewComment;
    private String aestheticsConfidence;
    private String sourceAgentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
