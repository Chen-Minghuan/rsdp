package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Scene 激活就绪度（V20，GET 详情 activationReadiness，实时计算）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FloorPlanActivationReadiness {

    /** 是否满足 Scene Activation Validator 全部阻断项。 */
    private boolean ready;

    /** 未满足的阻断项（中文可读；ready=true 时为空数组）。 */
    private List<String> issues;
}
