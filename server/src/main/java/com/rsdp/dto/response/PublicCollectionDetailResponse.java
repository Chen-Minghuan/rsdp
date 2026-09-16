package com.rsdp.dto.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户端官网公开产品集详情（GET /api/v1/public/collections/{id}）。
 *
 * <p>产品项与 {@code /api/v1/public/products} 同字段口径：
 * 仅 RSPU 展示字段与零售参考价，绝不含 RSKU 工厂报价/工厂信息。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PublicCollectionDetailResponse extends PublicCollectionSummaryResponse {

    /** 集合产品项（仅 status='active' 的在售产品，按集合内排序）。 */
    private List<PublicProductItemResponse> items;
}
