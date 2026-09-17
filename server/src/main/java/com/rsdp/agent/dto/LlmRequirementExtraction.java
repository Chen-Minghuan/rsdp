package com.rsdp.agent.dto;

import com.rsdp.agent.patch.PatchOperation;
import lombok.Data;

import java.util.List;

/**
 * LLM 需求抽取结果（内部 DTO，RequirementPatchNode 解析模型输出用）。
 */
@Data
public class LlmRequirementExtraction {

    /** NEW_REQUIREMENT/REFINE/FEEDBACK_MODIFY/CONFIRM_REQUIREMENT/CONFIRM_ITEM/CHITCHAT。 */
    private String intent;

    /** 需求变更操作（可为空）。 */
    private List<PatchOperation> operations;
}
