package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风格百科案例。
 */
@Data
@TableName("style_case")
public class StyleCase {

    @TableId
    private String caseId;

    private String caseName;
    private String dictType;
    private String styleCode;
    private String roomType;
    private Boolean isSuccess;
    private String sourceType;
    private String sourceUrl;
    private String description;
    private String imageUrl;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String aiRawOutput;

    private String negativeLesson;
    private String reviewStatus;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
