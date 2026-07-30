package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 单页的产品检测结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProductRegion {

    /**
     * 页码索引，从 0 开始。
     */
    private int pageIndex;

    /**
     * 页面类型：product / cover / toc / separator / blank / unknown。
     */
    private String pageType;

    /**
     * 页面中检测到的产品区域列表。
     */
    private List<PageProduct> products = new ArrayList<>();

    /**
     * 判断本页是否为产品页。
     *
     * @return 是否包含产品
     */
    public boolean isProductPage() {
        return "product".equalsIgnoreCase(pageType) && products != null && !products.isEmpty();
    }

    /**
     * 单页内的单个产品信息。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageProduct {

        /**
         * 产品相对位置框。
         */
        private ProductBoundingBox bbox;

        /**
         * AI 预估的品类码，如 SF / TB / FC。
         */
        private String estimatedCategory;

        /**
         * 产品图旁边的说明文字（品名/型号/尺寸/价格等），页面级检测时一并提取。
         * 裁剪产品图时不含这些文字，此处作为该产品 OCR 的补充来源。
         */
        private OcrResult nearbyText;
    }
}
