package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 搭配方案项实体。
 */
@Data
@TableName("scheme_item")
public class SchemeItem {

    @TableId(type = IdType.AUTO)
    private Long schemeItemId;
    private String schemeId;
    private String rspuId;
    private String rskuId;
    private String factoryCode;
    private BigDecimal factoryPrice;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
