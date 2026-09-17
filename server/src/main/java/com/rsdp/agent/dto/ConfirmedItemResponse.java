package com.rsdp.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 已确认主体产品响应（与前端 ConfirmedItem 契约逐字段对应）。
 */
@Data
public class ConfirmedItemResponse {

    private String itemId;

    private String rspuId;

    /** 产品名（冗余自 spec.productName，便于列表展示）。 */
    private String productName;

    /** 确认规格（= 推荐条目的产品快照）。 */
    private Map<String, Object> spec;

    private Integer quantity;

    /** confirmed/cancelled。 */
    private String status;

    private LocalDateTime createdAt;
}
