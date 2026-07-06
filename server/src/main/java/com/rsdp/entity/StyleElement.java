package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风格案例拆解元素。
 */
@Data
@TableName("style_element")
public class StyleElement {

    @TableId
    private String elementId;

    private String caseId;
    private String elementType;
    private String elementValue;
    private String normalizedCode;
    private Boolean isPrimary;
    private String confidence;
    private String notes;
    private LocalDateTime createdAt;
}
