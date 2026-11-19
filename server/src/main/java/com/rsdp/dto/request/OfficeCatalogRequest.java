package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Office 后端从会话组装的历史导入记录查询条件。 */
public final class OfficeCatalogRequest {

    private OfficeCatalogRequest() {}

    public record Actor(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotBlank @Size(max = 64) String actorId,
        @Size(max = 128) String actorName,
        @NotBlank @Size(max = 16) String factoryCode
    ) {}
}
