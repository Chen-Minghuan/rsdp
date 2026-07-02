package com.rsdp.dto.request;

import lombok.Data;

/**
 * RSPU 产品间关系更新请求。
 */
@Data
public class RspuRelationUpdateRequest {

    /**
     * 关系类型：official / ai_verified / exclude。
     */
    private String relationType;

    /**
     * 搭配原因或说明。
     */
    private String reason;

    /**
     * 排序，越小越靠前。
     */
    private Integer sortOrder;

    /**
     * 状态：active / inactive。
     */
    private String status;
}
