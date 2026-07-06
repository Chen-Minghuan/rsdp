package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户工厂关联实体（用于厂商业务员数据权限）。
 */
@Data
@TableName("sys_user_factory")
public class SysUserFactory {

    @TableId
    private Long id;

    private String userId;

    private String factoryCode;

    private LocalDateTime createdAt;
}
