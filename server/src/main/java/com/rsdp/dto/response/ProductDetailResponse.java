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
}
