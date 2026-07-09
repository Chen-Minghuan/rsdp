package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂交期规则实体。
 *
 * <p>按工厂+品类+材质等级+工艺类型定义基础交期，支持动态计算。</p>
 */
@Data
@TableName("factory_lead_time_rule")
public class FactoryLeadTimeRule {

    @TableId
    private Long ruleId;

    private String factoryCode;
    private String categoryCode;
    private String materialGradeCode;
    private String processType;
    private Integer baseDays;
    private Integer batchSizeThreshold;
    private Integer batchExtraDays;
    private Integer materialSwitchExtraDays;
    private Integer priority;
    private String status;
    private String notes;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
