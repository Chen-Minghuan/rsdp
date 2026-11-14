package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 整批放行（INTEG-001）：owner 厂授权目标厂在其公共库开关关闭时仍可见 owner 厂全部产品（含新品）。
 */
@Data
@TableName("factory_catalog_grant")
public class FactoryCatalogGrant {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 放行方（产品归属厂） */
    private String ownerFactoryCode;
    /** 被放行方（浏览厂） */
    private String grantedFactoryCode;
    private String createdBy;
    private LocalDateTime createdAt;
}
