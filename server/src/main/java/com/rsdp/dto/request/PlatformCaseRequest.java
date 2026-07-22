package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网落地案例创建/更新请求。
 */
@Data
public class PlatformCaseRequest {

    @NotBlank(message = "案例标题不能为空")
    @Size(max = 128)
    private String title;

    @Size(max = 64)
    private String coverImageId;

    /** 富文本 HTML */
    private String content;

    private Integer sortOrder;

    @Size(max = 16)
    private String status;
}
