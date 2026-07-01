package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 多风格关联实体。
 *
 * <p>数据库主键为复合主键 (rspu_id, style_code)。MyBatis-Plus 仅支持单一主键，
 * 因此将 rspu_id 标注为 {@code @TableId}，业务查询仍通过 QueryWrapper 按复合条件操作。</p>
 */
@Data
@TableName("rspu_style")
public class RspuStyle {

    @TableId
    private String rspuId;

    private String dictType;

    @TableField("style_code")
    private String styleCode;

    private Boolean isPrimary;

    private LocalDateTime createdAt;
}
