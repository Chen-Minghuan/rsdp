package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 订单状态迁移请求。
 */
@Data
public class OrderStatusRequest {

    /** 目标状态：PENDING/CONFIRMED/PRODUCING/COMPLETED/CANCELLED */
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
