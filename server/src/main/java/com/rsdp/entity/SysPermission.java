package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统权限实体。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId
    private Long permissionId;

    private String permissionCode;

    private String permissionName;

    private LocalDateTime createdAt;
}
