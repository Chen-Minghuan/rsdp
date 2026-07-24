package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网产品定制入口卡片实体。
 */
@Data
@TableName("platform_customized")
public class PlatformCustomized {

    @TableId
    private String customizedId;

    private String title;

    /** 封面图（image_assets.image_id） */
    private String coverImageId;

    private String description;

    /** 跳转目标（URL 或站内路径） */
    private String linkValue;

    private Integer sortOrder;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
