package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工厂响应。
 */
@Data
public class FactoryResponse {

    private String factoryCode;
    private String factoryName;

    /**
     * 主评级。
     */
    private String factoryLevel;

    /**
     * 可承接的所有等级（包含主等级）。
     */
    private List<String> capableLevels;

    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private LocalDate firstAuditDate;
    private LocalDate nextVisitDate;
    private String notes;

    /**
     * 资质认证（JSON 字符串）。
     */
    private String certification;

    /**
     * 工程案例（JSON 字符串）。
     */
    private String engineeringCases;

    /**
     * 工厂面积（平方米）。
     */
    private BigDecimal factoryArea;

    /**
     * 员工人数。
     */
    private Integer employeeCount;

    /**
     * 月产能（件）。
     */
    private Integer monthlyCapacity;

    /**
     * 成立年份。
     */
    private Integer foundedYear;

    /**
     * 设备清单（JSON 字符串）。
     */
    private String equipmentList;

    /**
     * 框架木材类型。
     */
    private String frameWood;

    /**
     * 海绵供应商。
     */
    private String spongeSupplier;

    /**
     * 面料皮革来源。
     */
    private String leatherFabricSource;

    /**
     * 五金配件供应商。
     */
    private String hardwareSupplier;

    /**
     * QC 项目（JSON 字符串）。
     */
    private String qcItems;

    /**
     * QC 人数。
     */
    private Integer qcStaffCount;

    /**
     * 发货地。
     */
    private String shippingFrom;

    /**
     * 物流方式（JSON 字符串）。
     */
    private String logisticsMethods;

    /**
     * 默认包装（JSON 字符串）。
     */
    private String defaultPackaging;

    /**
     * 验厂人员签名。
     */
    private String auditorSignature;

    /**
     * 工厂图片（JSON 字符串）。
     */
    private String factoryImages;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
