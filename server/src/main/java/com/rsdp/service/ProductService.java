package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 产品录入服务，负责接收图片、创建 RSPU 草稿和异步任务，并触发后台 AI 识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final RspuMapper rspuMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AsyncTaskProcessor asyncTaskProcessor;
    private final ImageUploadValidator imageUploadValidator;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${spring.servlet.multipart.max-file-size:20MB}")
    private String maxFileSize;

    /**
     * 新品录入入口。
     *
     * <p>同步完成：图片校验、本地落盘、RSPU 草稿、图片记录、异步任务记录。
     * AI 识别在后台异步执行，调用方通过返回的 {@code taskId} 轮询任务状态。
     *
     * @param image 产品图片
     * @return 包含 taskId、rspuId、imageId 的映射
     * @throws IOException 文件保存失败
     */
    @Transactional
    public Map<String, Object> createEntry(MultipartFile image) throws IOException {
        long start = System.currentTimeMillis();

        imageUploadValidator.validate(image, parseMaxFileSize(maxFileSize));

        String rspuId = "RSPU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String taskId = "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String imageId = "IMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 保存图片到存储后端
        String objectKey = "images/" + imageId + "." + getExtension(image.getOriginalFilename());
        String storagePath = storageService.store(image, objectKey);

        // 创建 RSPU 草稿
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

        // 创建图片资源记录
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setRspuId(rspuId);
        imageAsset.setImageType("white_bg");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(true);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize(image.getSize());
        imageAsset.setFormat(getExtension(image.getOriginalFilename()));
        imageAsset.setUploadedBy("admin");
        imageAsset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(imageAsset);

        // 创建异步任务
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setProgress(0);
        task.setInputData(objectMapper.writeValueAsString(Map.of(
            "rspuId", rspuId,
            "imageId", imageId,
            "objectKey", storagePath,
            "originalFilename", image.getOriginalFilename()
        )));
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        // 触发后台 AI 识别：若处于事务中，则在事务提交后触发；否则立即触发
        triggerAsyncProcess(taskId, rspuId, imageId, storagePath);

        log.info("产品录入任务已创建，总耗时 {}ms，taskId={}",
            System.currentTimeMillis() - start, taskId);

        return Map.of(
            "taskId", taskId,
            "rspuId", rspuId,
            "imageId", imageId,
            "message", "任务已创建，正在后台识别中"
        );
    }

    private void triggerAsyncProcess(String taskId, String rspuId, String imageId, String objectKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);
                }
            });
        } else {
            asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);
        }
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "jpg";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private long parseMaxFileSize(String size) {
        if (size == null || size.isBlank()) {
            return 20 * 1024 * 1024;
        }
        String value = size.trim().toUpperCase();
        long multiplier = 1;
        if (value.endsWith("MB")) {
            multiplier = 1024 * 1024;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("KB")) {
            multiplier = 1024;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("GB")) {
            multiplier = 1024L * 1024 * 1024;
            value = value.substring(0, value.length() - 2);
        }
        try {
            return Long.parseLong(value.trim()) * multiplier;
        } catch (NumberFormatException e) {
            log.warn("无法解析 max-file-size: {}", size);
            return 20 * 1024 * 1024;
        }
    }
}
