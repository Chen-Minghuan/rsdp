package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 六维标签维度定义更新请求（字典管理中心维护入口）。
 */
@Data
public class SixDimSchemaUpdateRequest {

    /** 维度标签（如 轮廓形态）。 */
    @NotBlank(message = "维度标签不能为空")
    @Size(max = 64, message = "维度标签长度不能超过64字符")
    private String label;

    /** 维度说明（取值范围提示）。 */
    @Size(max = 255, message = "维度说明长度不能超过255字符")
    private String description;
}
