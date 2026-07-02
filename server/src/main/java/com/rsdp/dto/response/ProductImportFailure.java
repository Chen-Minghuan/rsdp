package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 产品（RSPU）导入失败明细。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImportFailure {

    /**
     * Excel 行号（从 2 开始，含表头）。
     */
    private Integer rowIndex;

    /**
     * 外部编码。
     */
    private String externalCode;

    /**
     * RSPU ID。
     */
    private String rspuId;

    /**
     * 失败原因。
     */
    private String reason;
}
