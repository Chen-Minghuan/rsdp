package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 六维标签单维度修正请求（PATCH /api/v1/products/{rspuId}/six-dim-tags）。
 *
 * <p>只改 {@code dimKey} 指定的一个维度，替代整对象读-改-写 PUT，
 * 缩小并发修正不同维度时的 lost update 窗口。</p>
 */
@Data
public class SixDimTagPatchRequest {

    /** 维度键（A/B/C/D/E/F，大小写不敏感，见 SixDimSchemaService 维度定义）。 */
    @NotBlank(message = "维度键不能为空")
    private String dimKey;

    /** 维度值（字典码或自由文本）；null 或空白表示清除该维度。 */
    private String value;
}
