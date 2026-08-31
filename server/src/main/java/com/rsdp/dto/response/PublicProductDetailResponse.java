package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户端官网商品详情响应（免登录）。
 *
 * <p>脱敏红线：只含 RSPU 展示字段、零售参考价、图片与变体展示信息；
 * 绝不含出厂价、工厂代码、RSKU 等供应链字段。</p>
 */
@Data
public class PublicProductDetailResponse {

    private String rspuId;
    private String rspuCode;
    private String productName;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String colorPrimaryName;
    private String colorSecondary;
    /** 材质标签字典码列表。 */
    private List<String> materialTags;
    /** 面料标签字典码列表。 */
    private List<String> fabricTags;
    /** 长文本描述原文（材质解析/配置说明等）。 */
    private String description;
    /** 零售参考价（不加密，官网展示口径）。 */
    private BigDecimal retailPrice;
    /** 参考价格带：low/mid/high。 */
    private String referencePriceBand;
    private String productLevel;
    private Integer warrantyYears;
    /** 六维标签（维度键 → 枚举码）。 */
    private Map<String, String> sixDimTags;
    /** 关键规格键值对。 */
    private Map<String, Object> keySpecs;
    private LocalDateTime createdAt;
    /** 全部产品图（主图在前）。 */
    private List<ImageItem> images;
    /** 全部在变体（组合选择）。 */
    private List<VariantItem> variants;

    /**
     * 商品图片项。
     */
    @Data
    public static class ImageItem {
        private String imageId;
        /** 访问地址（/api/v1/images/{imageId}）。 */
        private String url;
        /** 是否主图。 */
        private Boolean primary;
        /** 挂接的变体 ID（变体专属图，可能为空）。 */
        private String variantId;
    }

    /**
     * 变体展示项（仅展示字段，不含报价）。
     */
    @Data
    public static class VariantItem {
        private String variantId;
        /** 显示名（如 "585*580*750"、"1.8m"）。 */
        private String displayName;
        private String variantCode;
        private String sizeCode;
        /** 尺寸原文（工厂方言，码归一化前的事实层）。 */
        private String sizeText;
        /** 结构化尺寸（如 {"w":585,"d":580,"h":750,"unit":"mm"}，可能含 seat_count）。 */
        private Map<String, Object> dimensions;
        private String colorCode;
        private String colorText;
        private String materialCode;
        private String materialText;
    }
}
