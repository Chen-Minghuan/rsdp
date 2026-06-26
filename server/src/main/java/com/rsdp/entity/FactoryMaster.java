package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工厂档案实体。
 */
@Data
@TableName("factory_master")
public class FactoryMaster {

    @TableId
    private String factoryCode;
    private String factoryName;
    private String factoryLevel;
    private String homeCommercialTag;
    private String certification;
    private String engineeringCases;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private LocalDate firstAuditDate;
    private LocalDate nextVisitDate;
    private String notes;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
