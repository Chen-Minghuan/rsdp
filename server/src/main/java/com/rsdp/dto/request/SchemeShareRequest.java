package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 方案分享开关请求（V42，字段语义与项目分享 ProjectShareRequest 保持一致）。
 */
@Data
public class SchemeShareRequest {

    @NotNull(message = "分享开关不能为空")
    private Boolean shareEnabled;

    /** 有效期天数（1-365；为空=永久有效；关闭分享时忽略） */
    @Min(value = 1, message = "有效期至少 1 天")
    @Max(value = 365, message = "有效期最长 365 天")
    private Integer expireDays;
}
