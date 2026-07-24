package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.EncryptTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设计订单实体。
 */
@Data
@TableName("design_order")
public class DesignOrder {

    @TableId
    private String orderId;
    private String orderNo;
    private String projectId;
    private String schemeId;
    private String receiverName;
    private String receiverPhone;
    private String receiverArea;
    private String receiverAddress;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal originalTotalPrice;

    private BigDecimal priceRate;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal finalTotalPrice;

    private Integer itemCount;
    private String status;
    private Integer expectedLeadTime;
    private String remark;
    private String inviteTokenHash;
    private LocalDateTime inviteExpireAt;
    private LocalDateTime inviteConfirmedAt;
    /** 合同文件（image_assets.image_id，image_type=contract） */
    private String contractFileId;
    private String createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
