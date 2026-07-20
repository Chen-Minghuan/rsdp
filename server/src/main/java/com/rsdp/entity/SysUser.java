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

    private String status;

    private Integer tokenVersion;

    private Boolean viewFullCatalog;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
