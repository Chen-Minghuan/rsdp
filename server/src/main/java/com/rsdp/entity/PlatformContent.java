package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网内容配置实体（按 code 唯一读取：服务协议/客服咨询等）。
 */
@Data
@TableName("platform_content")
public class PlatformContent {

    @TableId
    private String contentId;

    /** 内容编码（如 platform_user_agreement） */
    private String code;

    private String title;

    /** 内容类型：image=单图 / rich_text=富文本 / embed=嵌入代码 */
    private String contentType;

    /** 富文本 HTML / 图片 image_id / 嵌入代码 */
    private String content;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
