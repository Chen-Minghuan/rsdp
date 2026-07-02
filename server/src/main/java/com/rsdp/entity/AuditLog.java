package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 操作表名，如 rspu_master / rsku_supply / factory_master。
     */
    private String tableName;

    /**
     * 被操作记录 ID。
     */
    private String recordId;

    /**
     * 操作类型：CREATE / UPDATE / DELETE / REVIEW。
     */
    private String action;

    /**
     * 变更前快照（JSON）。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Object oldValue;

    /**
     * 变更后快照（JSON）。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Object newValue;

    /**
     * 操作人。
     */
    private String operator;

    /**
     * 操作人 IP。
     */
    private String ipAddress;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
