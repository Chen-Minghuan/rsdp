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
     * 行内供应商源商品编码（导入行上下文回显，用于定位失败行；
     * 供应商命名空间内编码，非平台主展示编码，设计文档 v3.3 §8）。
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
