package com.rsdp.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 以图搜图 / 以文搜图请求。
 */
@Data
public class SimilarProductRequest {

    /**
     * 查询图片（与 text 二选一）。
     */
    private MultipartFile image;

    /**
     * 查询文本（与 image 二选一）。
     */
    private String text;

    /**
     * 按类别过滤（可选）。
     */
    private String categoryCode;

    /**
     * 按风格/定位过滤（可选）。
     */
    private String positioningLabel;

    /**
     * 返回数量，默认 20。
     */
    private Integer topK = 20;
}
