package com.rsdp.agent.dto;

import lombok.Data;

/**
 * 方案导出响应（跳转既有方案详情页走报价单/下单流程）。
 */
@Data
public class AgentSchemeExportResponse {

    private String schemeId;

    private String schemeName;

    /** 方案项数量。 */
    private Integer itemCount;

    /** 方案详情页路径（前端跳转用）。 */
    private String detailUrl;
}
