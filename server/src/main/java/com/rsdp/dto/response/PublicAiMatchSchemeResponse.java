package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 官网 AI 户型搭配方案响应（免登录公开接口）。
 *
 * <p>红线：绝不包含 RSKU 工厂报价字段（factoryCode/factoryName/factoryPrice），
 * 价格仅为零售参考价 retail_price。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicAiMatchSchemeResponse {

    /** AI 搭配推荐理由。 */
    private String reasoning;

    /** 方案零售参考价合计（仅对 retailPrice 非空的条目求和）。 */
    private BigDecimal totalRetailPrice;

    /** 搭配产品列表。 */
    private List<Item> items = new ArrayList<>();

    /**
     * 搭配产品项（公开安全字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 产品 ID。 */
        private String rspuId;

        /** 商品名称。 */
        private String productName;

        /** 类目路径。 */
        private String categoryPath;

        /** 风格定位标签。 */
        private String positioningLabel;

        /** 零售参考价（不加密），可空。 */
        private BigDecimal retailPrice;

        /** 主图访问地址。 */
        private String primaryImageUrl;
    }
}
