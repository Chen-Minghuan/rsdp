package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId
    private String userId;

    private String username;

    private String passwordHash;

    private String nickname;

    /** 企业名称（企业团队轻量版，项目创建默认取此值） */
    private String companyName;

    /** 团队分组名称（企业团队轻量版） */
    private String groupName;

    /** 所属企业（company.company_id），非空即企业账号 */
    private String companyId;

    /** 所属企业分组（member_group.group_id） */
    private String groupId;

    /** 永久邀请码（8 位），注册时通过 ?inviteCode= 绑定 */
    private String inviteCode;

    /** 邀请人 user_id（注册归因，不携带权限） */
    private String invitedBy;

    /** 认证设计师标记（VIEWER 一键升级时置位并补 DESIGNER 角色） */
    private Boolean certifiedDesigner;

    private String status;

    private Integer tokenVersion;

    private Boolean viewFullCatalog;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
