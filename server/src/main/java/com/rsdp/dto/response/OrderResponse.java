package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单响应。
 */
@Data
public class OrderResponse {

    private String orderId;
    private String orderNo;
    private String projectId;
    private String schemeId;
    private String receiverName;
    private String receiverPhone;
    private String receiverArea;
    private String receiverAddress;
    /** 原价总额（出厂价合计） */
    private BigDecimal originalTotalPrice;
    /** 折扣率 */
    private BigDecimal priceRate;
    /** 到手价总额 */
    private BigDecimal finalTotalPrice;
    private Integer itemCount;
    private String status;
    private Integer expectedLeadTime;
    private String remark;
    private LocalDateTime inviteExpireAt;
    private LocalDateTime inviteConfirmedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
