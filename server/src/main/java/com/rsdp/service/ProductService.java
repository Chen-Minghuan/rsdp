package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.CategoryPaths;
import com.rsdp.util.ImageUploadValidator;
import com.rsdp.dto.request.FactoryProductEntryRequest;
import com.rsdp.dto.request.ManualProductEntryRequest;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rsdp.util.IdGenerator;

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
    private final AuditLogService auditLogService;
    private final DictService dictService;
    private final ObjectMapper objectMapper;
    private final RspuVariantService rspuVariantService;
    private final RskuService rskuService;
    private final UserFactoryService userFactoryService;
    private final RspuCodeService rspuCodeService;
    private final RskuCodeService rskuCodeService;

    @Value("${spring.servlet.multipart.max-file-size:20MB}")
    private String maxFileSize;

    /**
     * 新品录入入口。
     *
     * <p>同步完成：图片校验、本地落盘、RSPU 草稿、图片记录、异步任务记录。
     * AI 识别在后台异步执行，调用方通过返回的 {@code taskId} 轮询任务状态。
     *
     * <p>支持一次上传多张图片：第一张图作为主图（{@code white_bg}）参与 AI 识别，
     * 其余图作为非主图（{@code detail}）仅做存档展示。
     *
     * @param images       产品图片列表，第一张为主图
     * @param categoryCode 品类码，如 FS/DT/CB；为空时默认 FS
     * @return 包含 taskId、rspuId、imageIds 的映射
     * @throws IOException 文件保存失败
     */
    @Transactional
    public Map<String, Object> createEntry(List<MultipartFile> images, String categoryCode) throws IOException {
        long start = System.currentTimeMillis();

        if (images == null || images.isEmpty()) {
            throw new BusinessException("请至少上传一张图片");
        }

        long maxSize = parseMaxFileSize(maxFileSize);
        for (MultipartFile image : images) {
            imageUploadValidator.validate(image, maxSize);
        }

        String rspuId = IdGenerator.rspuId();
        String taskId = IdGenerator.taskId();

        String effectiveCategoryCode = (categoryCode == null || categoryCode.isBlank()) ? "FS" : categoryCode.trim().toUpperCase();
        validateCategoryCode(effectiveCategoryCode);

        // 创建 RSPU 草稿
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(effectiveCategoryCode);
        rspu.setCategoryPath(CategoryPaths.resolve(effectiveCategoryCode));
        rspu.setPositioningLabel("待识别");
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);
        auditLogService.logCreate("rspu_master", rspuId, rspu, SecurityOperatorContext.currentUsername());

        List<String> imageIds = new ArrayList<>();
        List<String> storedObjectKeys = new ArrayList<>();
        List<ImageAssets> imageAssets = new ArrayList<>();
        String primaryImageId = null;
        String primaryObjectKey = null;

        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            String imageId = IdGenerator.imageId();
            String objectKey = "images/" + imageId + "." + getExtension(image.getOriginalFilename());
            String storagePath = storageService.store(image, objectKey);
            storedObjectKeys.add(storagePath);

            boolean isPrimary = i == 0;
            ImageAssets imageAsset = new ImageAssets();
            imageAsset.setImageId(imageId);
            imageAsset.setRspuId(rspuId);
            imageAsset.setImageType(isPrimary ? "white_bg" : "detail");
            imageAsset.setStoragePath(storagePath);
            imageAsset.setPrimary(isPrimary);
            imageAsset.setAiProcessed(false);
            imageAsset.setFileSize(image.getSize());
            imageAsset.setFormat(getExtension(image.getOriginalFilename()));
            imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
            imageAsset.setCreatedAt(LocalDateTime.now());
            imageAssets.add(imageAsset);
            auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());

            imageIds.add(imageId);
            if (isPrimary) {
                primaryImageId = imageId;
                primaryObjectKey = storagePath;
            }
        }

        if (!imageAssets.isEmpty()) {
            imageAssetsMapper.insertBatch(imageAssets);
        }

        registerStorageRollbackCleanup(storedObjectKeys);

        // 创建异步任务（仅针对主图做 AI 识别）
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setProgress(0);
        MultipartFile primaryImage = images.get(0);
        task.setInputData(objectMapper.writeValueAsString(Map.of(
            "rspuId", rspuId,
            "imageId", primaryImageId,
            "objectKey", primaryObjectKey,
            "originalFilename", primaryImage.getOriginalFilename()
        )));
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        // 触发后台 AI 识别：若处于事务中，则在事务提交后触发；否则立即触发
        triggerAsyncProcess(taskId, rspuId, primaryImageId, primaryObjectKey);

        log.info("产品录入任务已创建，共 {} 张图片，总耗时 {}ms，taskId={}",
            images.size(), System.currentTimeMillis() - start, taskId);

        return Map.of(
            "taskId", taskId,
            "rspuId", rspuId,
            "imageIds", imageIds,
            "message", "任务已创建，正在后台识别中"
        );
    }

    /**
     * 工厂单条录入新产品。
     *
     * <p>在一个事务中完成 RSPU、默认变体、图片资源（可选）和第一条 RSKU 的创建。
     * 不调用 AI，供工厂管理员手动维护产品使用。</p>
     *
     * @param request 工厂录入请求
     * @param images  产品图片，可选
     * @return 创建结果，包含 rspuId、variantId、rskuId
     * @throws IOException 图片存储失败
     */
    @Transactional
    public Map<String, Object> createFactoryEntry(FactoryProductEntryRequest request, List<MultipartFile> images) throws IOException {
        validateFactoryEntryOwnership(request.getFactoryCode());
        validateCategoryCode(request.getCategoryCode());

        RspuMaster rspu = insertRspuForEntry(
            request.getCategoryCode(), request.getPositioningLabel(), request.getColorPrimaryName(),
            request.getMaterialTags(), request.getFabricTags(), request.getSceneTags(), request.getSixDimTags(),
            request.getProductLevel(), request.getWarrantyYears(), request.getKeySpecs(),
            request.getProductName());
        assignRspuCode(rspu, request.getSizeCode());
        String variantId = createDefaultVariantForEntry(
            rspu.getRspuId(), rspu.getProductLevel(), request.getVariantDisplayName(),
            request.getSizeCode(), request.getDimensions(), request.getColorCode(),
            request.getVariantMaterialCode(), request.getMaterialMix());
        List<String> imageIds = storeEntryImages(rspu.getRspuId(), variantId, images);

        // 创建第一条 RSKU
        RskuCreateRequest rskuRequest = new RskuCreateRequest();
        rskuRequest.setRspuId(rspu.getRspuId());
        rskuRequest.setVariantId(variantId);
        rskuRequest.setFactoryCode(request.getFactoryCode());
        rskuRequest.setFactorySku(request.getFactorySku());
        rskuRequest.setFactoryPrice(request.getFactoryPrice());
        rskuRequest.setMaterialCode(request.getVariantMaterialCode());
        rskuRequest.setMaterialDescription(request.getMaterialDescription());
        rskuRequest.setLeadTimeDays(request.getLeadTimeDays());
        rskuRequest.setMoq(request.getMoq());
        rskuRequest.setWarrantyYears(request.getWarrantyYearsRsku());
        rskuRequest.setShippingFrom(request.getShippingFrom());
        rskuRequest.setDiffNotes(request.getDiffNotes());
        rskuRequest.setQuoteConfidence(request.getQuoteConfidence());
        rskuRequest.setProductLevel(rspu.getProductLevel());
        rskuRequest.setAutoExtendCapability(request.getAutoExtendCapability());
        String rskuId = rskuService.createRsku(rskuRequest);

        return Map.of(
            "rspuId", rspu.getRspuId(),
            "variantId", variantId,
            "rskuId", rskuId,
            "imageIds", imageIds,
            "message", "工厂产品录入成功"
        );
    }

    /**
     * 传统手工录入新产品（不调用 AI、不关联工厂报价）。
     *
     * <p>在一个事务中完成 RSPU、默认变体、图片资源（可选）的创建。
     * 供平台运营人员按传统表单方式维护产品使用；工厂报价可后续在产品详情页补充。</p>
     *
     * @param request 手工录入请求
     * @param images  产品图片，可选
     * @return 创建结果，包含 rspuId、variantId
     * @throws IOException 图片存储失败
     */
    @Transactional
    public Map<String, Object> createManualEntry(ManualProductEntryRequest request, List<MultipartFile> images) throws IOException {
        validateCategoryCode(request.getCategoryCode());

        RspuMaster rspu = insertRspuForEntry(
            request.getCategoryCode(), request.getPositioningLabel(), request.getColorPrimaryName(),
            request.getMaterialTags(), request.getFabricTags(), request.getSceneTags(), null,
            request.getProductLevel(), request.getWarrantyYears(), null,
            request.getProductName());
        assignRspuCode(rspu, request.getSizeCode());
        String variantId = createDefaultVariantForEntry(
            rspu.getRspuId(), rspu.getProductLevel(), request.getVariantDisplayName(),
            request.getSizeCode(), request.getDimensions(), request.getColorCode(),
            request.getVariantMaterialCode(), request.getMaterialMix());
        List<String> imageIds = storeEntryImages(rspu.getRspuId(), variantId, images);

        return Map.of(
            "rspuId", rspu.getRspuId(),
            "variantId", variantId,
            "imageIds", imageIds,
            "message", "手工录入产品成功"
        );
    }

    /**
     * 创建并落库 RSPU（active + 待复核），写审计日志。
     */
    private RspuMaster insertRspuForEntry(String categoryCode, String positioningLabel, String colorPrimaryName,
                                          List<String> materialTags, List<String> fabricTags, List<String> sceneTags,
                                          Object sixDimTags,
                                          String productLevel, Integer warrantyYears, Object keySpecs,
                                          String productName) {
        String rspuId = IdGenerator.rspuId();

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(categoryCode.trim().toUpperCase());
        rspu.setCategoryPath(CategoryPaths.resolve(rspu.getCategoryCode()));
        rspu.setPositioningLabel(positioningLabel.trim().toUpperCase());
        rspu.setProductName(StringUtils.hasText(productName) ? productName.trim() : null);
        rspu.setColorPrimaryName(colorPrimaryName);
        rspu.setMaterialTags(toJson(materialTags));
        rspu.setFabricTags(toJson(fabricTags));
        rspu.setSceneTags(toJson(sceneTags));
        rspu.setSixDimTags(toJson(sixDimTags));
        rspu.setProductLevel(productLevel.trim().toUpperCase());
        rspu.setWarrantyYears(warrantyYears);
        rspu.setKeySpecs(toJson(keySpecs));
        rspu.setStatus("active");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);
        auditLogService.logCreate("rspu_master", rspuId, rspu, SecurityOperatorContext.currentUsername());
        return rspu;
    }

    /**
     * 生成并写入 RSPU 业务编码（rspu_code）。
     */
    private void assignRspuCode(RspuMaster rspu, String rawSizeCode) {
        String sizeCode = StringUtils.hasText(rawSizeCode)
            ? rawSizeCode.trim().toUpperCase()
            : null;
        rspuCodeService.assignCode(rspu.getRspuId(), rspu.getCategoryCode(), rspu.getPositioningLabel(), sizeCode);
    }

    /**
     * 为新 RSPU 创建默认变体。
     */
    private String createDefaultVariantForEntry(String rspuId, String productLevel, String displayName,
                                                String sizeCode, String dimensions, String colorCode,
                                                String materialCode, List<String> materialMix) {
        RspuVariantCreateRequest variantRequest = new RspuVariantCreateRequest();
        variantRequest.setDisplayName(displayName);
        variantRequest.setSizeCode(sizeCode);
        variantRequest.setDimensions(dimensions);
        variantRequest.setColorCode(colorCode);
        variantRequest.setMaterialCode(materialCode);
        variantRequest.setMaterialMix(materialMix);
        variantRequest.setProductLevel(productLevel);
        return rspuVariantService.createVariant(rspuId, variantRequest).getVariantId();
    }

    /**
     * 保存录入图片（可选）：第一张为主图，逐张写入 image_assets 并记审计。
     */
    private List<String> storeEntryImages(String rspuId, String variantId, List<MultipartFile> images) throws IOException {
        List<String> imageIds = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return imageIds;
        }
        List<String> storedObjectKeys = new ArrayList<>();
        long maxSize = parseMaxFileSize(maxFileSize);
        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            imageUploadValidator.validate(image, maxSize);
            String imageId = IdGenerator.imageId();
            String objectKey = "images/" + imageId + "." + getExtension(image.getOriginalFilename());
            String storagePath = storageService.store(image, objectKey);
            storedObjectKeys.add(storagePath);

            boolean isPrimary = i == 0;
            ImageAssets imageAsset = new ImageAssets();
            imageAsset.setImageId(imageId);
            imageAsset.setRspuId(rspuId);
            imageAsset.setVariantId(variantId);
            imageAsset.setImageType(isPrimary ? "white_bg" : "detail");
            imageAsset.setStoragePath(storagePath);
            imageAsset.setPrimary(isPrimary);
            imageAsset.setAiProcessed(false);
            imageAsset.setFileSize(image.getSize());
            imageAsset.setFormat(getExtension(image.getOriginalFilename()));
            imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
            imageAsset.setCreatedAt(LocalDateTime.now());
            imageAssetsMapper.insert(imageAsset);
            auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());
            imageIds.add(imageId);
        }
        registerStorageRollbackCleanup(storedObjectKeys);
        return imageIds;
    }

    /**
     * 从图片流创建单产品录入。
     *
     * <p>与 {@link #createEntry(List, String)} 行为一致，但输入为已校验过的图片字节流，
     * 用于 PDF/PPT 等文档导入后裁剪出的单产品图。</p>
     *
     * @param imageStream  产品主图输入流
     * @param filename     原始文件名，用于生成对象键和记录格式
     * @param size         图片字节数
     * @param categoryCode 品类码
     * @param pageOcr      页面级检测提取的产品旁说明文字（可为空），随任务传递，
     *                     异步识别时作为裁剪图 OCR 的补充合并进识别结果
     * @return 包含 taskId、rspuId、imageIds 的映射
     * @throws IOException 文件保存失败
     */
    @Transactional
    public Map<String, Object> createEntryFromStream(InputStream imageStream, String filename, long size,
                                                     String categoryCode, OcrResult pageOcr) throws IOException {
        long start = System.currentTimeMillis();

        if (imageStream == null) {
            throw new BusinessException("图片流不能为空");
        }

        String rspuId = IdGenerator.rspuId();
        String taskId = IdGenerator.taskId();
        String imageId = IdGenerator.imageId();

        String effectiveCategoryCode = (categoryCode == null || categoryCode.isBlank()) ? "FS" : categoryCode.trim().toUpperCase();
        validateCategoryCode(effectiveCategoryCode);

        // 创建 RSPU 草稿
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(effectiveCategoryCode);
        rspu.setCategoryPath(CategoryPaths.resolve(effectiveCategoryCode));
        rspu.setPositioningLabel("待识别");
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);
        auditLogService.logCreate("rspu_master", rspuId, rspu, SecurityOperatorContext.currentUsername());

        String extension = getExtension(filename);
        String objectKey = "images/" + imageId + "." + extension;
        String storagePath = storageService.store(imageStream, objectKey, size, "image/" + extension);
        registerStorageRollbackCleanup(List.of(storagePath));

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setRspuId(rspuId);
        imageAsset.setImageType("white_bg");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(true);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize(size);
        imageAsset.setFormat(extension);
        imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
        imageAsset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(imageAsset);
        auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());

        // 创建异步任务
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setProgress(0);
        // 创建异步任务（pageOcr 为文档导入时页面级提取的产品旁说明文字，供异步识别合并）
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("rspuId", rspuId);
        inputData.put("imageId", imageId);
        inputData.put("objectKey", storagePath);
        inputData.put("originalFilename", filename);
        if (pageOcr != null) {
            inputData.put("pageOcr", pageOcr);
        }
        task.setInputData(objectMapper.writeValueAsString(inputData));
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        triggerAsyncProcess(taskId, rspuId, imageId, storagePath);

        log.info("产品录入任务已从流创建，耗时 {}ms，taskId={}", System.currentTimeMillis() - start, taskId);

        return Map.of(
            "taskId", taskId,
            "rspuId", rspuId,
            "imageIds", List.of(imageId),
            "message", "任务已创建，正在后台识别中"
        );
    }

    private void validateFactoryEntryOwnership(String factoryCode) {
        List<String> userFactories = userFactoryService.getFactoryCodesByUsername(
            SecurityOperatorContext.currentUsername()
        );
        if (!userFactories.contains(factoryCode)) {
            throw new BusinessException("无权为该工厂录入产品: " + factoryCode);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
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

    /**
     * 注册事务回滚清理：若当前事务最终回滚，则删除已写入存储的文件，避免孤儿文件。
     *
     * @param objectKeys 已存储文件的对象键列表
     */
    private void registerStorageRollbackCleanup(List<String> objectKeys) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || objectKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                for (String objectKey : objectKeys) {
                    try {
                        storageService.delete(objectKey);
                    } catch (IOException e) {
                        log.warn("事务回滚后清理文件失败: {}", objectKey, e);
                    }
                }
            }
        });
    }

    private void validateCategoryCode(String categoryCode) {
        boolean exists = dictService.listByType("category").stream()
            .anyMatch(d -> categoryCode.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException("品类不存在: " + categoryCode);
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
