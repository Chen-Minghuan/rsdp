package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请页订单视图（免登录公开访问，不含出厂价/工厂等敏感信息）。
 */
@Data
public class OrderInviteViewResponse {

    private String orderNo;
    private String status;
    private String receiverArea;
    /** 到手总价 */
    private BigDecimal finalTotalPrice;
    private Integer itemCount;
    /** 预计交期（天） */
    private Integer expectedLeadTime;
    /** 链接过期时间 */
    private LocalDateTime expireAt;
    /** 是否已确认（确认后链接不可再次确认） */
    private Boolean confirmed;
    private LocalDateTime confirmedAt;
    private List<OrderInviteItemResponse> items;
}
