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

    private String status;

    private Integer tokenVersion;

    private Boolean viewFullCatalog;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
