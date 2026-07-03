package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rspu_master")
public class RspuMaster {
    @TableId
    private String rspuId;
    private String externalCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sixDimTags;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String styleVector;

    private String colorPrimaryName;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String colorPrimaryHsv;

    private String colorSecondary;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialTags;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sceneTags;

    private String referencePriceBand;

    private String productLevel;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String budgetRange;

    private Integer warrantyYears;

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
