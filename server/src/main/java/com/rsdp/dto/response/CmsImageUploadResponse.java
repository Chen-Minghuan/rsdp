package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * CMS 图片上传响应。
 */
@Data
@AllArgsConstructor
public class CmsImageUploadResponse {

    private String imageId;
    /** 图片访问地址（/api/v1/images/{imageId}） */
    private String url;
}
