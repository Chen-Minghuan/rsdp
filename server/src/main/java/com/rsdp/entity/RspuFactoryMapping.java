package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU-工厂多对多关联实体。
 *
 * <p>记录某款式可由哪些工厂生产，支持主供工厂、发货仓库、MOQ、基础交期等。</p>
 */
@Data
@TableName("rspu_factory_mapping")
public class RspuFactoryMapping {

    @TableId(type = IdType.AUTO)
    private Long mappingId;

    private String rspuId;
    private String factoryCode;
    private Boolean isPrimary;
    private String shippingWarehouseId;
    private Integer moq;
    private Integer baseLeadTimeDays;
    private String status;
    private String notes;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
