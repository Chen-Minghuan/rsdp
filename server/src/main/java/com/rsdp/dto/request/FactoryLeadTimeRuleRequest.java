package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 工厂交期规则创建/更新请求。
 */
@Data
public class FactoryLeadTimeRuleRequest {

    private Long ruleId;

    private String factoryCode;

    private String categoryCode;
    private String materialGradeCode;
    private String processType;

    @NotNull(message = "基础交期天数不能为空")
    private Integer baseDays;

    private Integer batchSizeThreshold;
    private Integer batchExtraDays;
    private Integer materialSwitchExtraDays;
    private Integer priority;
    private String status;
    private String notes;
}
