package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网 Banner 创建/更新请求。
 */
@Data
public class PlatformBannerRequest {

    @Size(max = 32)
    private String position;

    @Size(max = 128)
    private String title;

    @NotBlank(message = "Banner 图片不能为空")
    @Size(max = 64)
    private String imageId;

    /** none=不跳转 / rspu=产品详情 / url=外链 */
    @Size(max = 16)
    private String linkType;

    @Size(max = 512)
    private String linkValue;

    private Integer sortOrder;

    @Size(max = 16)
    private String status;
}
