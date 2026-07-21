package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户邀请记录实体（永久邀请码注册归因）。
 */
@Data
@TableName("invite_record")
public class InviteRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请人 user_id */
    private String inviterId;

    /** 被邀请人 user_id */
    private String inviteeId;

    /** 注册时使用的邀请码 */
    private String inviteCode;

    private LocalDateTime createdAt;
}
