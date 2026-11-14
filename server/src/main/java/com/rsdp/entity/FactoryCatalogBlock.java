package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 黑名单（INTEG-001）：浏览厂屏蔽目标厂全部产品，优先级高于公共库开关与整批放行；
 * 不遮蔽浏览厂自家已报价的产品。
 */
@Data
@TableName("factory_catalog_block")
public class FactoryCatalogBlock {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 设置黑名单的浏览厂 */
    private String factoryCode;
    /** 被屏蔽的产品归属厂 */
    private String blockedFactoryCode;
    private String createdBy;
    private LocalDateTime createdAt;
}
