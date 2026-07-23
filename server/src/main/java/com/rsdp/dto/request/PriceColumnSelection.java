package com.rsdp.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认导入时对单个价格列的选择与角色指定。
 *
 * <p>角色语义：factory=出厂价（建变体 + RSKU 工厂报价）；sales=销售价
 * （不建变体/RSKU，价格值写入本行 RSPU 的零售参考价 retail_price）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceColumnSelection {

    /**
     * 价格列原始表头（与预览响应 priceColumns[].header 一致）。
     */
    private String header;

    /**
     * 价格列角色："factory" | "sales"；缺省/非法值按 factory 处理。
     */
    private String role;
}
