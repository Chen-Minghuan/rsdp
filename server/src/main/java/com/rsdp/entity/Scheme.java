package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 搭配方案主表实体。
 */
@Data
@TableName("scheme")
public class Scheme {

    @TableId
    private String schemeId;
    private String schemeName;
    private String roomType;
    private BigDecimal budgetLimit;
    private BigDecimal totalPrice;
    private Integer factoryCount;
    private Integer maxLeadTimeDays;
    private Integer itemCount;
    private String status;
    private String projectId;
    private Boolean isTemplate;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String templateTags;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
