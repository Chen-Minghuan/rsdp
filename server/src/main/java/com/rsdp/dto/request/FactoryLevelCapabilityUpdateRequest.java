package com.rsdp.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 工厂兼做等级更新请求。
 */
@Data
public class FactoryLevelCapabilityUpdateRequest {

    /**
     * 工厂可承接的所有等级，主等级必须包含在内。
     */
    @NotEmpty(message = "能力等级列表不能为空")
    private List<String> capableLevels;
}
