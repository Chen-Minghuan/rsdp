package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网留资提交请求（POST /api/v1/public/leads）。
 */
@Data
public class LeadCreateRequest {

    /** 客户姓名。 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String name;

    /** 联系电话。 */
    @NotBlank(message = "联系电话不能为空")
    @Size(max = 32, message = "联系电话长度不能超过 32")
    private String phone;

    /** 来源：ai_match / site_form / design_booking。 */
    @NotBlank(message = "来源不能为空")
    @Size(max = 32, message = "来源长度不能超过 32")
    private String source;

    /** 意向描述（自由文本，可选）。 */
    @Size(max = 2000, message = "意向描述长度不能超过 2000")
    private String intent;

    /** 预算区间（可选，如 1-3万）。 */
    @Size(max = 32, message = "预算长度不能超过 32")
    private String budget;
}
