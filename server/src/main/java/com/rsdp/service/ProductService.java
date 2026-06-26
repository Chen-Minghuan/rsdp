package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.OcrResult;
import com.rsdp.util.OcrPostProcessor;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final VisionService visionService;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.upload.path}")
    private String uploadPath;

    @Value("${rsdp.ai.model}")
    private String aiModel;

    @Transactional
    public Map<String, Object> createEntry(MultipartFile image) throws IOException {
        long totalStart = System.currentTimeMillis();

        // 1. 生成 ID
        String rspuId = "RSPU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String taskId = "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String imageId = "IMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 2. 保存图片到本地
        Path dir = Paths.get(System.getProperty("user.dir"), uploadPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String ext = getExtension(image.getOriginalFilename());
        String fileName = imageId + "." + ext;
        Path filePath = dir.resolve(fileName);
        image.transferTo(filePath.toFile());

        // 3. 写入 RSPU 草稿（必须先于 image_assets，因为外键约束）
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode("FS");
        rspu.setCategoryPath("[\"家具\",\"座椅\",\"休闲椅\",\"单椅\"]");
        rspu.setPositioningLabel("待识别");
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);

        // 4. 写入图片资源记录
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setRspuId(rspuId);
        imageAsset.setImageType("white_bg");
        imageAsset.setStoragePath(filePath.toString().replace("\\", "/"));
        imageAsset.setPrimary(true);
        imageAsset.setAiProcessed(false);
        imageAsset.setUploadedBy("admin");
        imageAsset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(imageAsset);

        // 5. 调用 AI 视觉模型识别
        AiLabels labels = null;
        String recognitionId = "REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String status = "success";
        String errorMessage = null;

        try {
            long aiStart = System.currentTimeMillis();
            labels = visionService.recognizeImage(filePath);
            int processingTime = (int) (System.currentTimeMillis() - aiStart);

            // 解析并清洗 OCR 信息
            OcrResult ocr = labels.getOcr();
            OcrPostProcessor.clean(ocr);

            // 更新 RSPU
            rspu.setPositioningLabel(labels.getStyle());
            rspu.setSixDimTags(objectMapper.writeValueAsString(labels.getSixDimTags()));
            rspu.setColorPrimaryName(labels.getColorPrimaryName());
            rspu.setColorPrimaryHsv(objectMapper.writeValueAsString(labels.getColorPrimaryHsv()));
            rspu.setMaterialTags(objectMapper.writeValueAsString(labels.getMaterialTags()));
            rspu.setSceneTags(objectMapper.writeValueAsString(labels.getSceneTags()));
            rspu.setAestheticsConfidence(labels.getConfidence());
            rspu.setSourceAgentVersion(aiModel);
            rspu.setKeySpecs(objectMapper.writeValueAsString(buildKeySpecs(ocr)));
            rspu.setStatus("active");
            rspu.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(rspu);

            // 写入风格关联（主风格 + 未来可扩展多风格）
            saveRspuStyle(rspuId, labels.getStyle(), true);

            // 根据 OCR 创建款式变体（支持尺寸 × 颜色 × 材质组合）
            createVariantsFromOcr(rspuId, ocr, labels);

            // 更新图片为已识别
            imageAsset.setAiProcessed(true);
            imageAssetsMapper.updateById(imageAsset);

            // 写入 AI 识别记录
            AiRecognition rec = new AiRecognition();
            rec.setRecognitionId(recognitionId);
            rec.setImageId(imageId);
            rec.setRspuId(rspuId);
            rec.setTaskId(taskId);
            rec.setModelName(aiModel);
            rec.setRecognitionType("label");
            rec.setEndpoint("/chat/completions");
            rec.setOutputData(objectMapper.writeValueAsString(labels));
            rec.setParsedStyle(labels.getStyle());
            rec.setParsedSixDim(objectMapper.writeValueAsString(labels.getSixDimTags()));
            rec.setParsedColorHsv(objectMapper.writeValueAsString(labels.getColorPrimaryHsv()));
            rec.setParsedSceneTags(objectMapper.writeValueAsString(labels.getSceneTags()));
            rec.setParsedOcr(objectMapper.writeValueAsString(ocr));
            rec.setConfidence(labels.getConfidence());
            rec.setProcessingTimeMs(processingTime);
            rec.setStatus(status);
            rec.setCreatedAt(LocalDateTime.now());
            aiRecognitionMapper.insert(rec);

        } catch (Exception e) {
            log.error("AI 识别失败", e);
            status = "failed";
            errorMessage = e.getMessage();

            // 识别失败也记录
            AiRecognition rec = new AiRecognition();
            rec.setRecognitionId(recognitionId);
            rec.setImageId(imageId);
            rec.setRspuId(rspuId);
            rec.setTaskId(taskId);
            rec.setModelName(aiModel);
            rec.setRecognitionType("label");
            rec.setEndpoint("/chat/completions");
            rec.setStatus(status);
            rec.setErrorMessage(errorMessage);
            rec.setCreatedAt(LocalDateTime.now());
            aiRecognitionMapper.insert(rec);

            // RSPU 保持 active，但 review_status 为存疑
            rspu.setStatus("active");
            rspu.setReviewStatus("存疑");
            rspu.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(rspu);
        }

        // 6. 写入异步任务
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("done");
        task.setProgress(100);
        task.setResultData(objectMapper.writeValueAsString(Map.of(
            "rspuId", rspuId,
            "imageId", imageId,
            "imagePath", filePath.toString().replace("\\", "/"),
            "aiLabels", labels != null ? labels : Map.of("error", errorMessage)
        )));
        task.setCreatedAt(LocalDateTime.now());
        task.setCompletedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        int totalTime = (int) (System.currentTimeMillis() - totalStart);
        log.info("产品录入完成，总耗时 {}ms，rspuId={}", totalTime, rspuId);

        return Map.of(
            "taskId", taskId,
            "rspuId", rspuId,
            "imageId", imageId,
            "imagePath", filePath.toString().replace("\\", "/"),
            "aiLabels", labels != null ? labels : Map.of("error", errorMessage)
        );
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "jpg";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 把 OCR 结果中适合写入 RSPU 的规格信息汇总成 key_specs。
     */
    private Map<String, Object> buildKeySpecs(OcrResult ocr) {
        Map<String, Object> keySpecs = new java.util.HashMap<>();
        if (ocr == null) {
            return keySpecs;
        }
        if (org.springframework.util.StringUtils.hasText(ocr.getModelNumber())) {
            keySpecs.put("modelNumber", ocr.getModelNumber());
        }
        if (org.springframework.util.StringUtils.hasText(ocr.getMaterialDescription())) {
            keySpecs.put("materialDescription", ocr.getMaterialDescription());
        }
        if (org.springframework.util.StringUtils.hasText(ocr.getBrand())) {
            keySpecs.put("brand", ocr.getBrand());
        }
        if (org.springframework.util.StringUtils.hasText(ocr.getRawText())) {
            keySpecs.put("ocrRawText", ocr.getRawText());
        }
        if (ocr.getOtherInfo() != null && !ocr.getOtherInfo().isEmpty()) {
            keySpecs.put("otherInfo", ocr.getOtherInfo());
        }
        return keySpecs;
    }

    /**
     * 保存 RSPU 风格关联。
     */
    private void saveRspuStyle(String rspuId, String styleName, boolean primary) {
        String styleCode = OcrPostProcessor.toStyleCode(styleName);
        if (!org.springframework.util.StringUtils.hasText(styleCode)) {
            log.warn("无法将风格 '{}' 映射为字典 code，rspuId={}", styleName, rspuId);
            return;
        }
        RspuStyle style = new RspuStyle();
        style.setRspuId(rspuId);
        style.setDictType("style");
        style.setStyleCode(styleCode);
        style.setPrimary(primary);
        style.setCreatedAt(LocalDateTime.now());
        rspuStyleMapper.insert(style);
        log.info("已保存 RSPU 风格关联，rspuId={}，styleCode={}", rspuId, styleCode);
    }

    /**
     * 根据 OCR 信息创建 RSPU 变体，支持尺寸 × 颜色 × 材质组合。
     */
    private void createVariantsFromOcr(String rspuId, OcrResult ocr, AiLabels labels) throws IOException {
        // 尺寸列表
        List<Dimensions> dimensionsList = (ocr != null)
            ? OcrPostProcessor.parseDimensions(ocr.getDimensionText())
            : java.util.List.of();
        if (dimensionsList.isEmpty() && ocr != null && isValidDimensions(ocr.getDimensions())) {
            dimensionsList = java.util.List.of(ocr.getDimensions());
        }
        if (dimensionsList.isEmpty()) {
            dimensionsList = java.util.List.of(new Dimensions());
        }

        // 颜色列表
        List<String> colors = OcrPostProcessor.parseColors(
            ocr != null ? ocr.getColorText() : null,
            labels != null ? labels.getColorPrimaryName() : null
        );
        if (colors.isEmpty()) {
            colors = java.util.List.of("默认");
        }

        // 材质组合：合并 OCR 材质描述 + 视觉识别材质标签，不拆分变体
        // 预留后续人工复核时修改材质组合的能力
        List<String> materialMix = buildMaterialMix(
            ocr != null ? ocr.getMaterialDescription() : null,
            labels != null ? labels.getMaterialTags() : null
        );

        String productName = (ocr != null && org.springframework.util.StringUtils.hasText(ocr.getProductName()))
            ? ocr.getProductName()
            : "默认变体";

        // 笛卡尔积生成变体：尺寸 × 颜色（材质作为组合存入，不拆分）
        int index = 1;
        for (Dimensions dimensions : dimensionsList) {
            for (String color : colors) {
                createSingleVariant(rspuId, productName, color, materialMix, dimensions, index++);
            }
        }

        log.info("已为 RSPU 创建 {} 个变体，rspuId={}", index - 1, rspuId);
    }

    /**
     * 判断尺寸是否有效。
     */
    private boolean isValidDimensions(Dimensions dimensions) {
        if (dimensions == null || !org.springframework.util.StringUtils.hasText(dimensions.getUnit())) {
            return false;
        }
        return dimensions.getW() != null || dimensions.getD() != null || dimensions.getH() != null;
    }

    /**
     * 创建单个 RSPU 变体。
     */
    private void createSingleVariant(String rspuId, String productName, String color,
                                     List<String> materialMix, Dimensions dimensions, int index) throws IOException {
        String variantId = rspuId + "-V" + String.format("%03d", index);
        RspuVariant variant = new RspuVariant();
        variant.setVariantId(variantId);
        variant.setRspuId(rspuId);
        variant.setDisplayName(buildVariantDisplayName(productName, color, materialMix, index));
        variant.setVariantCode("V" + String.format("%03d", index));
        variant.setDimensions(objectMapper.writeValueAsString(dimensions));
        variant.setMaterialMix(objectMapper.writeValueAsString(
            materialMix != null && !materialMix.isEmpty() ? materialMix : java.util.List.of("")
        ));
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        rspuVariantMapper.insert(variant);
    }

    /**
     * 构建材质组合列表：OCR 材质描述 + 视觉识别材质标签去重合并。
     */
    private List<String> buildMaterialMix(String materialDescription, List<String> materialTags) {
        List<String> materialMix = new java.util.ArrayList<>();
        if (org.springframework.util.StringUtils.hasText(materialDescription)) {
            materialMix.add(materialDescription.trim());
        }
        if (materialTags != null) {
            for (String material : materialTags) {
                if (org.springframework.util.StringUtils.hasText(material)
                    && !materialMix.contains(material.trim())) {
                    materialMix.add(material.trim());
                }
            }
        }
        if (materialMix.isEmpty()) {
            materialMix.add("");
        }
        return materialMix;
    }

    /**
     * 构建变体显示名称。
     */
    private String buildVariantDisplayName(String productName, String color,
                                           List<String> materialMix, int index) {
        StringBuilder sb = new StringBuilder(productName);
        if (!"默认".equals(color)) {
            sb.append(" ").append(color);
        }
        if (materialMix != null && !materialMix.isEmpty()) {
            String materialStr = materialMix.stream()
                .filter(org.springframework.util.StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining("+"));
            if (org.springframework.util.StringUtils.hasText(materialStr)) {
                sb.append(" ").append(materialStr);
            }
        }
        sb.append(" V").append(String.format("%03d", index));
        return sb.toString();
    }
}
