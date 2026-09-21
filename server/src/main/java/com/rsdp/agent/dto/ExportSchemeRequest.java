package com.rsdp.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话确认清单导出为方案请求（P2 对话外下单出口）。
 */
@Data
public class ExportSchemeRequest {

    /** 基于哪份报价快照导出（可空 = 现场按 confirmed 项重新解析）。 */
    private String quoteId;

    /** 方案名称（可空 = 自动生成「Agent方案-{会话}-{时间戳}」）。 */
    @Size(max = 128, message = "方案名称不能超过 128 个字符")
    private String schemeName;

    /** 幂等键（防重复导出）。 */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;
}
