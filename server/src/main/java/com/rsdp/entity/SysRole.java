package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色实体。
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId
    private Long roleId;

    private String roleCode;

    private String roleName;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
