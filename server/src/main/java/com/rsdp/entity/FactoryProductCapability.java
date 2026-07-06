package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂产品能力档案实体。
 *
 * <p>由工厂已有 RSKU 自动同步，用于“全库去重视图”判断该工厂能做哪些品类/风格/材质。</p>
 */
@Data
@TableName("factory_product_capability")
public class FactoryProductCapability {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String factoryCode;
    private String categoryCode;
    private String styleCode;
    private String materialCode;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
