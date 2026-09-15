package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品列表查询请求。
 */
@Data
public class ProductListRequest {

    private Long page = 1L;

    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 500, message = "每页数量不能超过 500")
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

    /** 六维标签筛选（值为带品类前缀的字典码，如 SF-一字型；E 维为自由材质文本不枚举，不参与筛选）。 */
    private String dimA;
    private String dimB;
    private String dimC;
    private String dimD;
    private String dimF;

    /**
     * 商城状态页签：onSale=出售中(status=active)、warehouse=仓库中(status!=active)、
     * soldOut=已售罄（当前无业务概念，恒为空）、recycled=回收站（已软删除记录）。
     */
    private String statusTab;

    /** 主图资产筛选：true=仅有主图，false=仅无主图，缺省不过滤（服务 AI 搭配数据补齐）。 */
    private Boolean hasPrimaryImage;

    /**
     * 价格下限（含）。口径按角色区分：平台员工按价格投影表最低出厂价
     * （与 minFactoryPrice 同源），其他角色按零售参考价 retail_price；
     * 价格为 null 的产品在任何价格区间筛选下都不返回。
     */
    private BigDecimal priceMin;

    /** 价格上限（含），口径同 {@link #priceMin}。 */
    private BigDecimal priceMax;

    /**
     * 排序：newest（默认，created_at DESC）/ price_asc / price_desc。
     * 价格排序口径同 {@link #priceMin}，价格为 null 的排在最后。
     */
    private String sort;
}
