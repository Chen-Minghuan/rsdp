package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设计师画像实体。
 */
@Data
@TableName("designer_profile")
public class DesignerProfile {

    @TableId
    private String profileId;

    private String userId;
    private String realName;
    private String avatarUrl;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String specialties;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String preferredStyles;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String preferredCategories;

    private String priceSensitivity;
    private String location;
    private String companyName;
    private String contactPhone;
    private String bio;

    private BigDecimal defaultBudgetMin;
    private BigDecimal defaultBudgetMax;

    private Boolean isPublic;
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
