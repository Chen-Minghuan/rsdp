package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网产品定制创建/更新请求。
 */
@Data
public class PlatformCustomizedRequest {

    @NotBlank(message = "定制标题不能为空")
    @Size(max = 128)
    private String title;

    @Size(max = 64)
    private String coverImageId;

    @Size(max = 512)
    private String description;

    @Size(max = 512)
    private String linkValue;

    private Integer sortOrder;

    @Size(max = 16)
    private String status;
}
