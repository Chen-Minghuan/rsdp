package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板标签响应。
 */
@Data
public class TemplateTagResponse {

    private String tagId;
    private String tagName;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
