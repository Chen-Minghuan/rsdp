package com.rsdp.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 确认主体产品请求（与前端 ConfirmAgentItemRequest 契约对应）。
 */
@Data
public class ConfirmItemRequest {

    /** 来源推荐条目 ID。 */
    @NotBlank(message = "recommendItemId 不能为空")
    private String recommendItemId;

    /** 数量。 */
    @NotNull(message = "quantity 不能为空")
    @Min(value = 1, message = "quantity 至少为 1")
    private Integer quantity;

    /** 幂等键（重复提交返回已存在记录）。 */
    @NotBlank(message = "idempotencyKey 不能为空")
    @Size(max = 128, message = "idempotencyKey 最长 128 字符")
    private String idempotencyKey;
}
