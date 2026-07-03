package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户角色关联实体。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId
    private Long id;

    private String userId;

    private Long roleId;

    private LocalDateTime createdAt;
}
