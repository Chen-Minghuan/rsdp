package com.rsdp.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工厂更新请求（部分字段更新）。
 */
@Data
public class FactoryUpdateRequest {

    private String factoryName;
    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private String notes;

    /**
     * 资质认证（JSON 字符串，如 ["ISO9001","FSC"]）。
     */
    private String certification;

    /**
     * 工程案例（JSON 字符串，如 [{"name":"案例1"}]）。
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
     * 设备清单（JSON 字符串，如 ["CNC","CUTTING"]）。
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
     * QC 项目（JSON 字符串，如 ["INCOMING","PROCESS","FINAL"]）。
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
     * 物流方式（JSON 字符串，如 ["SPECIAL","SF"]）。
     */
    private String logisticsMethods;

    /**
     * 默认包装（JSON 字符串，如 ["CARTON","WOODEN"]）。
     */
    private String defaultPackaging;

    /**
     * 验厂人员签名。
     */
    private String auditorSignature;

    /**
     * 工厂图片（JSON 字符串，如 ["path/1.jpg","path/2.jpg"]）。
     */
    private String factoryImages;
}
