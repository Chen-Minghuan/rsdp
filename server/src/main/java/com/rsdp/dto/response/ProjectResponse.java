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
    /** 项目下方案总价合计 */
    private BigDecimal totalPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
