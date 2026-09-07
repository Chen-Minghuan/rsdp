package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 空间搭配方案响应。
 */
@Data
public class RoomSchemeResponse {

    private String roomType;
    private BigDecimal budgetLimit;
    /**
     * 方案总价（成本口径：所选 RSKU 出厂价求和；仅平台运营/本厂管理员可见，
     * 其他角色掩码为 null）。前端新代码应使用 {@link #totalSalePrice}（销售价口径）。
     */
    private BigDecimal totalPrice;
    /** 方案参考售价合计（销售价口径：所选产品最低标准售价求和，全角色可见；未定价产品跳过求和）。 */
    private BigDecimal totalSalePrice;
    /** 是否存在未定价产品（参考售价解析不出、未计入 totalSalePrice）。 */
    private boolean hasUnpricedItems;
    private int itemCount;
    private String reasoning;
    private List<SchemeItemResponse> items;
}
