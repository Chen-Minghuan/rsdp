package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 变体归并请求（同一 RSPU 下语义重复变体收口）。
 */
@Data
public class VariantMergeRequest {

    /** 源变体 ID（归并后软删）。 */
    @NotBlank(message = "源变体不能为空")
    private String sourceVariantId;

    /** 目标变体 ID（吸收源变体的报价与图片）。 */
    @NotBlank(message = "目标变体不能为空")
    private String targetVariantId;

    /** RSKU 冲突裁决：源变体 rskuId → keepSource/keepTarget（同厂同供应配置冲突时必填）。 */
    private Map<String, String> rskuConflictResolutions;
}
