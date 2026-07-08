package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
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

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String certification;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String engineeringCases;

    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private LocalDate firstAuditDate;
    private LocalDate nextVisitDate;
    private String notes;

    // 规模信息
    private BigDecimal factoryArea;
    private Integer employeeCount;
    private Integer monthlyCapacity;
    private Integer foundedYear;

    // 设备清单
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String equipmentList;

    // 原料来源
    private String frameWood;
    private String spongeSupplier;
    private String leatherFabricSource;
    private String hardwareSupplier;

    // 品质控制
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qcItems;
    private Integer qcStaffCount;

    // 物流信息
    private String shippingFrom;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String logisticsMethods;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String defaultPackaging;

    // 验厂信息
    private String auditorSignature;

    // 工厂图片
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String factoryImages;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
