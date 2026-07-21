package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 企业实体（用户中心-企业团队）。
 *
 * <p>企业账号 = {@code sys_user.company_id} 非空；企业不是角色。</p>
 */
@Data
@TableName("company")
public class Company {

    @TableId
    private String companyId;

    private String companyName;

    /** 企业 Logo（image_assets.image_id，无外键，图片可独立清理） */
    private String logoImageId;

    /** 企业级折扣率 [0,1]，订单计价优先于全局 order.price_rate */
    private BigDecimal priceRatio;

    /** 企业管理员 user_id */
    private String ownerId;

    private String status;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
