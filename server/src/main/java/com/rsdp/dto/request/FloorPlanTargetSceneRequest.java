package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Target Scene 切换请求（V20，设计文档 V1.2.3 §8.2）。
 *
 * <p>不重新上传 DWG，从原始 CAD 文件引用重跑 Parser，产生新的 Analysis Revision；
 * 旧 Revision 不删除、不覆盖。异步执行，HTTP 202。</p>
 */
@Data
public class FloorPlanTargetSceneRequest {

    /** 基线 Analysis Revision（必须等于当前 latest，否则 409）。 */
    @NotNull(message = "baseAnalysisRevision 不能为空")
    private Integer baseAnalysisRevision;

    /** 目标 Scene ID（必须属于 base Revision 已返回的候选列表，否则 400）。 */
    @NotBlank(message = "targetId 不能为空")
    @Size(max = 128, message = "targetId 长度不能超过 128")
    private String targetId;
}
