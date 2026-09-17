package com.rsdp.agent.patch;

import lombok.Data;

import java.util.List;

/**
 * 需求 Patch（LLM 输出的 operations 集合，由 RequirementPatchReducer 应用）。
 */
@Data
public class RequirementPatch {

    /** 操作列表（按顺序应用）。 */
    private List<PatchOperation> operations;
}
