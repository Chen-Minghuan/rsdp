package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 相似产品检索结果。
 */
@Data
public class SimilarProductResponse {

    /**
     * RSPU ID。
     */
    private String rspuId;

    /**
     * 类别编码。
     */
    private String categoryCode;

    /**
     * 风格/定位标签。
     */
    private String positioningLabel;

    /**
     * 主图 URL。
     */
    private String mainImageUrl;

    /**
     * 向量相似度（0-1，越大越相似）。
     */
    private Double vectorScore;

    /**
     * 重排后的综合得分（0-1）。
     */
    private Double finalScore;

    /**
     * 加分/匹配原因。
     */
    private List<String> matchReasons;

    /**
     * AI 视觉识别置信度（high/mid/low）。
     */
    private String aestheticsConfidence;
}
