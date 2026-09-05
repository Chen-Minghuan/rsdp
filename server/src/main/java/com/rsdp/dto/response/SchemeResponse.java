package com.rsdp.dto.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 搭配方案详情响应。
 */
@Data
public class SchemeResponse {

    private String schemeId;
    private String schemeName;
    private String roomType;
    private BigDecimal budgetLimit;
    private BigDecimal totalPrice;
    private Integer factoryCount;
    private Integer maxLeadTimeDays;
    private Integer itemCount;
    private String status;
    private String projectId;
    private Boolean isTemplate;
    private List<String> templateTags;

    /** 画布布局 JSON（搭配画布，V41）：{"<schemeItemId>":{x,y,scale,z}}，可空=未保存画布布局。 */
    @JsonRawValue
    private String canvasLayout;

    /** 方案分享开关（V42） */
    private Boolean shareEnabled;

    /** 方案分享过期时间（V42，null=永久有效） */
    private LocalDateTime shareExpireAt;

    private String createdBy;
    private LocalDateTime createdAt;
    private List<SchemeItemResponse> items;
}
