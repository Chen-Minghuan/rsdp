package com.rsdp.dto.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 设计项目详情响应（含项目下方案列表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectDetailResponse extends ProjectResponse {

    private List<SchemeSummaryResponse> schemes;
}
