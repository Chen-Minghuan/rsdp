package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 多风格关联实体。
 */
@Data
@TableName("rspu_style")
public class RspuStyle {

    private String rspuId;
    private String dictType;
    private String styleCode;
    private Boolean isPrimary;
    private LocalDateTime createdAt;
}
