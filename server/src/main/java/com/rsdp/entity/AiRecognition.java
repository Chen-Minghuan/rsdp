package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_recognition")
public class AiRecognition {
    @TableId
    private String recognitionId;
    private String imageId;
    private String rspuId;
    private String taskId;
    private String modelName;
    private String recognitionType;
    private String endpoint;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String inputData;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String outputData;

    private String parsedStyle;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parsedSixDim;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parsedColorHsv;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parsedSceneTags;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parsedOcr;
    private String confidence;
    private Integer processingTimeMs;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
}
