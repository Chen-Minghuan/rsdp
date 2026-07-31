package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 产品列表查询请求。
 */
@Data
public class ProductListRequest {

    private Long page = 1L;

    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Long size = 10L;
    private String categoryCode;
    private String positioningLabel;
    private String sceneCode;
    private String materialTag;
    private String status;
    private String reviewStatus;
    private String productLevel;
    private String keyword;
    private String viewMode;
    private String factoryCode;

    /** SPU 业务编码模糊搜索（rspu_code）。 */
    private String rspuCode;

    /** 供应商编码模糊搜索（存在该工厂 RSKU 报价的产品）。 */
    private String supplierCode;

    /** 创建时间起（yyyy-MM-dd，含当日）。 */
    private String createdFrom;

    /** 创建时间止（yyyy-MM-dd，含当日）。 */
    private String createdTo;

    /**
     * 商城状态页签：onSale=出售中(status=active)、warehouse=仓库中(status!=active)、
     * soldOut=已售罄（当前无业务概念，恒为空）、recycled=回收站（已软删除记录）。
     */
    private String statusTab;
}
