package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量创建 AI 推荐候选请求。
 */
@Data
public class SchemeCandidateBatchCreateRequest {

    /** 推荐请求 ID。 */
    @NotBlank(message = "推荐请求 ID 不能为空")
    private String recommendRequestId;

    /** 候选列表。 */
    @NotEmpty(message = "候选列表不能为空")
    @Valid
    private List<SchemeCandidateCreateRequest> candidates;
}
