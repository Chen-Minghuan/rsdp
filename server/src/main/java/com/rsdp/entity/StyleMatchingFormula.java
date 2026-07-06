package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风格搭配公式。
 */
@Data
@TableName("style_matching_formula")
public class StyleMatchingFormula {

    @TableId
    private String formulaId;

    private String name;
    private String dictType;
    private String styleCode;
    private String roomType;
    private Integer priority;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String formulaJson;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceCaseIds;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String negativeCaseIds;

    private Integer successCount;
    private Integer failCount;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
