package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网落地案例实体。
 */
@Data
@TableName("platform_case")
public class PlatformCase {

    @TableId
    private String caseId;

    private String title;

    /** 封面图（image_assets.image_id） */
    private String coverImageId;

    /** 富文本 HTML 详情 */
    private String content;

    private Integer sortOrder;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
