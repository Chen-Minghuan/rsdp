package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 锚点搭配推荐响应。
 */
@Data
public class AnchorMatchingResponse {

    /** 锚点 RSPU ID。 */
    private String existingRspuId;

    /** 目标品类代码。 */
    private String targetCategoryCode;

    /** AI 推荐理由。 */
    private String reasoning;

    /** 推荐产品项。 */
    private List<SchemeItemResponse> items;
}
