package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂交期规则响应。
 */
@Data
public class FactoryLeadTimeRuleResponse {

    private Long ruleId;
    private String factoryCode;
    private String factoryName;
    private String categoryCode;
    private String categoryName;
    private String materialGradeCode;
    private String materialGradeName;
    private String processType;
    private String processTypeName;
    private Integer baseDays;
    private Integer batchSizeThreshold;
    private Integer batchExtraDays;
    private Integer materialSwitchExtraDays;
    private Integer priority;
    private String status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
