package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 保存（创建/更新）设计师画像请求。
 */
@Data
public class DesignerProfileSaveRequest {

    /** 真实姓名。 */
    @Size(max = 64, message = "真实姓名不能超过 64 个字符")
    private String realName;

    /** 头像 URL。 */
    @Size(max = 1000, message = "头像 URL 不能超过 1000 个字符")
    private String avatarUrl;

    /** 专长标签。 */
    private List<String> specialties;

    /** 偏好风格编码。 */
    private List<String> preferredStyles;

    /** 偏好品类编码。 */
    private List<String> preferredCategories;

    /** 价格敏感度。 */
    @Size(max = 16, message = "价格敏感度不能超过 16 个字符")
    private String priceSensitivity;

    /** 所在地区。 */
    @Size(max = 64, message = "所在地区不能超过 64 个字符")
    private String location;

    /** 公司名称。 */
    @Size(max = 128, message = "公司名称不能超过 128 个字符")
    private String companyName;

    /** 联系电话。 */
    @Size(max = 32, message = "联系电话不能超过 32 个字符")
    private String contactPhone;

    /** 个人简介。 */
    @Size(max = 2000, message = "个人简介不能超过 2000 个字符")
    private String bio;

    /** 默认预算下限。 */
    private BigDecimal defaultBudgetMin;

    /** 默认预算上限。 */
    private BigDecimal defaultBudgetMax;

    /** 是否公开画像。 */
    private Boolean isPublic;
}
