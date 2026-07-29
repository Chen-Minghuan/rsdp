package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 商城商品列表状态页签统计。
 */
@Data
@AllArgsConstructor
public class ProductStatusCountsResponse {

    /** 出售中（status = active 且未删除）。 */
    private Long onSale;

    /** 仓库中（status != active 且未删除）。 */
    private Long inWarehouse;

    /** 已售罄（当前无业务概念，恒为 0）。 */
    private Long soldOut;

    /** 回收站（已软删除）。 */
    private Long recycled;
}
