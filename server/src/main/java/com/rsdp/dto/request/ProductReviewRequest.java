package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 产品复核请求。
 */
@Data
public class ProductReviewRequest {

    @NotBlank(message = "复核状态不能为空")
    private String reviewStatus;

    private String reviewComment;
}
