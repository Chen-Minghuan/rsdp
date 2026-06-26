package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 多风格关联表：一个款式可属于多个风格。
 */
@Data
@TableName("rspu_style")
public class RspuStyle {

    @TableId
    private String rspuId;

    private String dictType;

    private String styleCode;

    @TableField(value = "is_primary")
    private Boolean primary;

    private LocalDateTime createdAt;
}
