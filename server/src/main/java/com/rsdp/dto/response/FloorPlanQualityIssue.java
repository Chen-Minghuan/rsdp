package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 户型图分析质量问题项（CAD 解析质量门报告，随分析详情下发；前端契约，字段名不可改）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FloorPlanQualityIssue {

    /** 级别：warn / error。 */
    private String level;

    /** 问题码（如 UNIT_ASSUMED / DIM_MISMATCH）。 */
    private String code;

    /** 中文可读问题描述。 */
    private String message;
}
