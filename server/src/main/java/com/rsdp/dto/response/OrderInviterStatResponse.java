package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单统计-邀请维度响应。
 */
@Data
public class OrderInviterStatResponse {

    private String inviterId;
    private String inviterUsername;
    private String inviterNickname;
    /** 邀请成功人数（统计窗口内有订单的被邀请人去重） */
    private Long inviteSuccessCount;
    /** 订单数 */
    private Long orderCount;
    /** 支付金额（到手价合计） */
    private BigDecimal totalAmount;
    /** 被邀请人明细 */
    private List<InviteeStat> invitees;

    /**
     * 被邀请人统计明细。
     */
    @Data
    public static class InviteeStat {
        private String userId;
        private String username;
        private String nickname;
        private Long orderCount;
        private BigDecimal totalAmount;
    }
}
