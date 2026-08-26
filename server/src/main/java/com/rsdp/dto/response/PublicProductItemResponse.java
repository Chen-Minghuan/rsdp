package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端官网商品列表项（GET /api/v1/public/products）。
 *
 * <p>红线：只含 RSPU 展示字段与零售参考价，绝不包含 RSKU 工厂报价（AES 加密列不出库）。</p>
 */
@Data
public class PublicProductItemResponse {

    private String rspuId;

    /** 业务编码（如 SF-WJ-002-L）。 */
    private String rspuCode;

    private String productName;

    private String categoryCode;

    private String categoryPath;

    /** 主风格标签。 */
    private String positioningLabel;

    /** 主色中文名。 */
    private String colorPrimaryName;

    /** 材质标签。 */
    private List<String> materialTags;

    /** 零售参考价（不加密字段；非工厂报价）。 */
    private BigDecimal retailPrice;

    /** 主图访问地址（/api/v1/images/{imageId}）。 */
    private String primaryImageUrl;

    /** 变体数量（颜色/尺寸组合数）。 */
    private Integer variantCount;

    private LocalDateTime createdAt;
}
