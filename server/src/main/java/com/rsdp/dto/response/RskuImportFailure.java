package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RSKU 报价导入失败明细。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RskuImportFailure {

    /**
     * Excel 行号（从 2 开始，含表头）。
     */
    private Integer rowIndex;

    /**
     * RSPU ID。
     */
    private String rspuId;

    /**
     * 工厂代码。
     */
    private String factoryCode;

    /**
     * 变体 ID。
     */
    private String variantId;

    /**
     * 失败原因。
     */
    private String reason;
}
