package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.ExtendedCategoryProperties;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.CategoryShadowPrediction;
import com.rsdp.entity.AiCategoryShadowResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.KnowledgeProductType;
import com.rsdp.mapper.AiCategoryShadowResultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扩展品类 Shadow Mode 编排器。
 *
 * <p>所有异常均在旁路内吞并记录；该服务只写 {@code ai_category_shadow_result}，
 * 不更新 RSPU、编码、报价、同款、订单或正式 AI 识别记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryShadowService {

    private final ExtendedCategoryProperties properties;
    private final DictService dictService;
    private final KnowledgeProductTypeService productTypeService;
    private final VisionService visionService;
    private final AiCategoryShadowResultMapper shadowResultMapper;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.ai.model}")
    private String model;

    /**
     * 判断 Shadow Mode 是否已启用，供主链在提交异步任务前快速短路。
     *
     * @return Shadow Mode 开启时为 true
     */
    public boolean isEnabled() {
        return properties.isShadowEnabled();
    }

    /**
     * 异步执行旁路分类并保存结果。开关关闭时立即返回且不发起 AI 调用。
     *
     * @param recognitionId 已提交的正式识别记录 ID
     * @param rspuId        RSPU ID
     * @param imageId       图片 ID
     * @param legacyCategory 正式流程最终采用的品类码
     * @param originalImage 原始上传图片字节
     */
    @Async("taskExecutor")
    public void evaluateAndStore(String recognitionId, String rspuId, String imageId,
                                 String legacyCategory, byte[] originalImage) {
        if (!isEnabled() || originalImage == null || originalImage.length == 0) {
            return;
        }
        try {
            Set<String> allowedCodes = new LinkedHashSet<>();
            for (CategoryDict category : dictService.listByType("category")) {
                if (category.getDictCode() != null) {
                    allowedCodes.add(category.getDictCode().toUpperCase());
                }
            }
            properties.getCodes().stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .map(String::toUpperCase)
                .forEach(allowedCodes::add);

            List<KnowledgeProductType> productTypes = productTypeService.listActive();
            CategoryShadowPrediction prediction = visionService.classifyCategoryShadow(
                new ByteArrayInputStream(originalImage), allowedCodes, productTypes);
            if (prediction == null) {
                return;
            }

            String sixDimJson = null;
            if (properties.isExtendedCode(prediction.categoryCode())) {
                AiLabels labels = visionService.recognizeImage(
                    new ByteArrayInputStream(originalImage), prediction.categoryCode());
                sixDimJson = objectMapper.writeValueAsString(labels.getSixDimTags());
            }

            AiCategoryShadowResult result = new AiCategoryShadowResult();
            result.setRecognitionId(recognitionId);
            result.setRspuId(rspuId);
            result.setImageId(imageId);
            result.setLegacyCategory(legacyCategory);
            result.setExtendedCategory(prediction.categoryCode());
            result.setProductType(prediction.productType());
            result.setSixDimResult(sixDimJson);
            result.setConfidence(prediction.confidence());
            result.setDifferenceReason(prediction.reason());
            result.setModelVersion(model);
            shadowResultMapper.insert(result);
        } catch (Exception e) {
            log.warn("扩展品类 Shadow 旁路失败，不影响正式识别，recognitionId={}", recognitionId, e);
        }
    }
}
