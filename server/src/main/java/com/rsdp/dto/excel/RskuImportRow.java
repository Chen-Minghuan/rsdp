package com.rsdp.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工厂 RSKU 报价 Excel 导入行（模板表头为中文）。
 */
@Data
public class RskuImportRow {

    @ExcelProperty("RSPU编码")
    private String rspuId;

    @ExcelProperty("工厂编码")
    private String factoryCode;

    @ExcelProperty("变体编码")
    private String variantId;

    @ExcelProperty("出厂价")
    private BigDecimal factoryPrice;

    @ExcelProperty("工厂SKU")
    private String factorySku;

    @ExcelProperty("材质编码")
    private String materialCode;

    @ExcelProperty("材质说明")
    private String materialDescription;

    @ExcelProperty("交期（天）")
    private Integer leadTimeDays;

    @ExcelProperty("最小起订量")
    private Integer moq;

    @ExcelProperty("质保年限")
    private Integer warrantyYears;

    @ExcelProperty("发货地")
    private String shippingFrom;

    @ExcelProperty("差异备注")
    private String diffNotes;

    @ExcelProperty("报价置信度")
    private String quoteConfidence;

    @ExcelProperty("产品等级")
    private String productLevel;
}
