package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 100, message = "topK 不能超过 100")
    private Integer topK = 20;
}
