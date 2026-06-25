package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
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
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final RspuMapper rspuMapper;
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
        imageAsset.setIsPrimary(true);
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

            // 更新 RSPU
            rspu.setPositioningLabel(labels.getStyle());
            rspu.setSixDimTags(objectMapper.writeValueAsString(labels.getSixDimTags()));
            rspu.setColorPrimaryName(labels.getColorPrimaryName());
            rspu.setColorPrimaryHsv(objectMapper.writeValueAsString(labels.getColorPrimaryHsv()));
            rspu.setMaterialTags(objectMapper.writeValueAsString(labels.getMaterialTags()));
            rspu.setSceneTags(objectMapper.writeValueAsString(labels.getSceneTags()));
            rspu.setAestheticsConfidence(labels.getConfidence());
            rspu.setSourceAgentVersion(aiModel);
            rspu.setStatus("active");
            rspu.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(rspu);

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
}
