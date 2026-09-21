package com.rsdp.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 生成会话报价请求。
 */
@Data
public class GenerateQuoteRequest {

    /** 参与报价的确认项 ID 子集；空 = 会话内全部 confirmed 项。 */
    private List<String> confirmedItemIds;

    /** 幂等键（防重复生成；agent_quote.idempotency_key 唯一）。 */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;
}
