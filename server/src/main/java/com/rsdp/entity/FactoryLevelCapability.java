package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂能力等级实体。
 * 记录工厂可承接的所有等级，其中主评级标记为 is_primary = true。
 */
@Data
@TableName("factory_level_capability")
public class FactoryLevelCapability {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String factoryCode;
    private String levelCode;
    private Boolean isPrimary;
    private LocalDateTime createdAt;
}
