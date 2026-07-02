package com.rsdp.dto.response;

import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import lombok.Data;

import java.util.List;

/**
 * 产品详情响应。
 */
@Data
public class ProductDetailResponse {

    private RspuMaster rspu;
    private List<ImageAssets> images;
    private List<AiRecognition> recognitions;

    /**
     * 官方搭配：本产品搭配了哪些其他产品。
     */
    private List<RspuRelationResponse> officialMatches;

    /**
     * 适配来源：哪些其他产品把本产品作为搭配。
     */
    private List<RspuRelationResponse> matchedBy;
}
