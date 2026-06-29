package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
