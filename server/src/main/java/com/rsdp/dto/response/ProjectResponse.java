package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设计项目响应。
 */
@Data
public class ProjectResponse {

    private String projectId;
    private String projectName;
    private String projectType;
    private String companyName;
    private String ownerId;
    private String status;
    private String remark;
    /** 画布分享开关 */
    private Boolean shareEnabled;
    /** 分享过期时间（null=永久有效） */
    private LocalDateTime shareExpireAt;
    /** 项目下方案数量 */
    private Integer schemeCount;
    /** 项目下方案成本总价合计（Σscheme.total_price，成本口径）：仅平台员工可见，其他角色为 null；前端应使用 totalSalePrice。 */
    private BigDecimal totalPrice;
    /** 项目下方案销售价合计（Σ各方案售价合计，全角色可见；未定价项跳过求和）。 */
    private BigDecimal totalSalePrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
