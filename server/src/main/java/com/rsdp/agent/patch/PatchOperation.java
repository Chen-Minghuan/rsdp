package com.rsdp.agent.patch;

import lombok.Data;

/**
 * 单条需求 Patch 操作（LLM 输出）。
 */
@Data
public class PatchOperation {

    /** 目标字段名（须在 RequirementConstraints 白名单内）。 */
    private String field;

    /** 操作类型：set / clear。 */
    private String operation;

    /** 设定值（set 时使用，按字段类型转换）。 */
    private String value;

    /** 用户原话依据（set 必填，防模型臆造需求）。 */
    private String evidence;
}
