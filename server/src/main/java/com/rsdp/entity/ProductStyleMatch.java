package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品-风格匹配结果。
 */
@Data
@TableName("product_style_match")
public class ProductStyleMatch {

    @TableId
    private Long matchId;

    private String rspuId;
    private String dictType;
    private String styleCode;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String elementMatch;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String formulaScores;

    private BigDecimal overallScore;
    private String confidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
