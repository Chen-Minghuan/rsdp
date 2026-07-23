package com.rsdp.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品（RSPU）Excel 批量导入行（模板表头为中文）。
 *
 * <p>一行对应一个 RSPU 及其可选默认变体。多风格、多场景、多材质通过逗号分隔表达。</p>
 */
@Data
public class ProductImportRow {

    @ExcelProperty("RSPU ID")
    private String rspuId;

    @ExcelProperty("外部编码")
    private String externalCode;

    @ExcelProperty("品类码")
    private String categoryCode;

    @ExcelProperty("定位标签")
    private String positioningLabel;

    @ExcelProperty("主色")
    private String colorPrimaryName;

    @ExcelProperty("材质标签")
    private String materialTags;

    @ExcelProperty("场景标签")
    private String sceneTags;

    @ExcelProperty("产品等级")
    private String productLevel;

    @ExcelProperty("保修年限")
    private Integer warrantyYears;

    @ExcelProperty("参考价格带")
    private String referencePriceBand;

    @ExcelProperty("六维标签")
    private String sixDimTags;

    @ExcelProperty("关键规格")
    private String keySpecs;

    @ExcelProperty("主图URL")
    private String primaryImageUrl;

    @ExcelProperty("详情图URLs")
    private String detailImageUrls;

    @ExcelProperty("变体显示名称")
    private String variantDisplayName;

    @ExcelProperty("尺寸码")
    private String sizeCode;

    /** 尺寸/规格原文（工厂方言，码未识别时保留） */
    private String sizeText;

    @ExcelProperty("颜色码")
    private String colorCode;

    /** 颜色原文（工厂方言，码未识别时保留） */
    private String colorText;

    @ExcelProperty("材质码")
    private String materialCode;

    /** 材质原文（工厂方言，码未识别时保留） */
    private String materialText;

    @ExcelProperty("变体参考价格带")
    private String variantReferencePriceBand;

    @ExcelProperty("变体产品等级")
    private String variantProductLevel;

    @ExcelProperty("交期天数")
    private Integer leadTimeDays;

    /** 长文本描述原文（材质解析/功能配置/配置说明等列原文） */
    private String description;

    /** 零售参考价（销售价/含税价/零售价/市场价列或 sales 角色价格列） */
    private java.math.BigDecimal retailPrice;
}
