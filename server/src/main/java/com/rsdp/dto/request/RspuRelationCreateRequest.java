package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RSPU 产品间关系创建请求。
 */
@Data
public class RspuRelationCreateRequest {

    /**
     * 搭配产品 RSPU ID。
     */
    @NotBlank(message = "搭配产品 ID 不能为空")
    private String relatedRspuId;

    /**
     * 关系类型：official / ai_verified / exclude。
     */
    private String relationType = "official";

    /**
     * 搭配原因或说明。
     */
    private String reason;

    /**
     * 排序，越小越靠前。
     */
    private Integer sortOrder = 0;
}
