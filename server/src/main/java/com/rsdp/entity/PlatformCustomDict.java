package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网自定义字典实体。
 */
@Data
@TableName("platform_custom_dict")
public class PlatformCustomDict {

    @TableId
    private String dictId;

    private String dictName;

    private String dictType;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
