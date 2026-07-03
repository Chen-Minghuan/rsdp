package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 锚点搭配推荐请求。
 */
@Data
public class AnchorMatchingRequest {

    /** 锚点 RSPU ID。 */
    @NotBlank(message = "锚点 RSPU ID 不能为空")
    private String existingRspuId;

    /** 目标品类代码，如 FS/SF。 */
    @NotBlank(message = "目标品类代码不能为空")
    private String targetCategoryCode;
}
