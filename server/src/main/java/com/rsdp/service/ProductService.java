package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.CategoryPaths;
import com.rsdp.util.ContentHashes;
import com.rsdp.util.ImageUploadValidator;
import com.rsdp.dto.request.FactoryProductEntryRequest;
import com.rsdp.dto.request.ManualProductEntryRequest;
import com.rsdp.dto.request.RegionEntryRequest;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rsdp.util.IdGenerator;

/**
 * 产品录入服务，负责接收图片、创建 RSPU 草稿和异步任务，并触发后台 AI 识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final RspuMapper rspuMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
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
    private final ProductSubjectCropService subjectCropService;
    private final VisionService visionService;
    private final DataScopeHelper dataScopeHelper;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

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
        return createEntry(images, categoryCode, false);
    }

    /**
     * 新品录入（带图片内容查重）。
     *
     * @param images       产品图片列表，第一张为主图
     * @param categoryCode 品类码，如 FS/DT/CB；为空时默认 FS
     * @param force        跳过图片内容查重（用户确认"仍然导入"时传 true）
     * @return 包含 taskId、rspuId、imageIds 的映射
     * @throws IOException 文件保存失败
     */
    @Transactional
    public Map<String, Object> createEntry(List<MultipartFile> images, String categoryCode, boolean force) throws IOException {
        long start = System.currentTimeMillis();

        if (images == null || images.isEmpty()) {
            throw new BusinessException("请至少上传一张图片");
        }

        long maxSize = parseMaxFileSize(maxFileSize);
        for (MultipartFile image : images) {
            imageUploadValidator.validate(image, maxSize);
        }

        // 图片内容哈希：录入查重 + 落库（V31）
        List<String> contentHashes = new ArrayList<>();
        for (MultipartFile image : images) {
            contentHashes.add(ContentHashes.sha256Hex(image.getBytes()));
        }

        String rspuId = IdGenerator.rspuId();
        String taskId = IdGenerator.taskId();

        String effectiveCategoryCode = (categoryCode == null || categoryCode.isBlank()) ? "FS" : categoryCode.trim().toUpperCase();
        validateCategoryCode(effectiveCategoryCode);

        // 图片内容查重：同一文件已入库时拒绝（force 跳过），防重复导入产生重复产品
        if (!force) {
            for (int i = 0; i < images.size(); i++) {
                String hash = contentHashes.get(i);
                if (hash == null) {
                    continue;
                }
                ImageAssets duplicate = imageAssetsMapper.selectByContentHash(hash);
                if (duplicate != null) {
                    throw new BusinessException(buildDuplicateEntryMessage(images.get(i).getOriginalFilename(), duplicate));
                }
            }
        }

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
            imageAsset.setContentHash(contentHashes.get(i));
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

        // 创建异步任务（仅针对主图做 AI 识别）；用户未选品类时打标记，异步阶段先 AI 判定品类再识别
        boolean categoryAutoDetect = (categoryCode == null || categoryCode.isBlank());
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
            "originalFilename", primaryImage.getOriginalFilename(),
            "categoryAutoDetect", categoryAutoDetect
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
     * 事务内不调用 AI；主图 AI 智能裁剪在事务提交后异步执行（不阻塞录入响应，失败回退原图），
     * 供工厂管理员手动维护产品使用。</p>
     *
     * @param request 工厂录入请求
     * @param images  产品图片，可选
     * @return 创建结果，包含 rspuId、variantId、rskuId、rspuCode（发号失败为 null）
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
        String rspuCode = assignRspuCode(rspu, request.getSizeCode());
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

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("rspuId", rspu.getRspuId());
        result.put("variantId", variantId);
        result.put("rskuId", rskuId);
        result.put("imageIds", imageIds);
        result.put("rspuCode", rspuCode);
        result.put("message", "工厂产品录入成功");
        return result;
    }

    /**
     * 传统手工录入新产品（不关联工厂报价；主图 AI 智能裁剪在事务提交后异步执行，不阻塞录入响应）。
     *
     * <p>在一个事务中完成 RSPU、默认变体、图片资源（可选）的创建。
     * 供平台运营人员按传统表单方式维护产品使用；工厂报价可后续在产品详情页补充。</p>
     *
     * @param request 手工录入请求
     * @param images  产品图片，可选
     * @return 创建结果，包含 rspuId、variantId、rspuCode（发号失败为 null）
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
        String rspuCode = assignRspuCode(rspu, request.getSizeCode());
        String variantId = createDefaultVariantForEntry(
            rspu.getRspuId(), rspu.getProductLevel(), request.getVariantDisplayName(),
            request.getSizeCode(), request.getDimensions(), request.getColorCode(),
            request.getVariantMaterialCode(), request.getMaterialMix());
        List<String> imageIds = storeEntryImages(rspu.getRspuId(), variantId, images);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("rspuId", rspu.getRspuId());
        result.put("variantId", variantId);
        result.put("imageIds", imageIds);
        result.put("rspuCode", rspuCode);
        result.put("message", "手工录入产品成功");
        return result;
    }

    /**
     * 创建并落库 RSPU（active + 待复核），写审计日志，并补写风格/场景关联表。
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
        insertStyleSceneAssociations(rspu, sceneTags);
        return rspu;
    }

    /**
     * 录入场景补写 rspu_style / rspu_scene 关联表（2.6）。
     *
     * <p>产品列表的风格/场景筛选走 {@code EXISTS rspu_style/rspu_scene} 子查询，
     * 只写 rspu_master 的 JSONB 列会导致手工/工厂录入的产品在风格/场景筛选下查不到。</p>
     *
     * <p>口径与既有写入方（Excel 导入 saveStyles/saveScenes、Excel AI 导入 saveStylesAndScenes、
     * AI 识别回填 refreshStyleAssociations/refreshSceneAssociations）对齐：</p>
     * <ul>
     *   <li>rspu_style 只写 style 字典码且 {@code is_primary=true}；positioningLabel 为办公家具
     *       职级码（grade 字典，如 EX/MG）时不写 rspu_style——既有链路均只写 style 字典码，
     *       职级码仅保留在 rspu_master.positioning_label 与 rspu_code 中，不发明新规则。</li>
     *   <li>rspu_scene 逐值归一（字典码忽略大小写 → 字典名），重复码去重；未命中 scene 字典的
     *       值跳过并记 log.warn——rspu_scene 有到 category_dict 的复合外键，插脏值会 FK 违例
     *       导致整单回滚，故坏值降级跳过而不阻断建档。</li>
     *   <li>审计沿用 2.3「关联表变更每 RSPU 一条汇总」口径，参照 AI 回填链路以
     *       「旧集合为空 → 新集合」记一条 logUpdate；未写入任何关联时不重复记
     *       （rspu_master 的 logCreate 快照已含 positioningLabel/sceneTags 原始值）。</li>
     * </ul>
     *
     * @param rspu      已落库的 RSPU
     * @param sceneTags 前端传入的场景字典码列表（未经字典校验，需归一）
     */
    private void insertStyleSceneAssociations(RspuMaster rspu, List<String> sceneTags) {
        String rspuId = rspu.getRspuId();
        String operator = SecurityOperatorContext.currentUsername();

        String styleCode = matchDictCode(rspu.getPositioningLabel(), dictService.listByType("style"));
        if (styleCode != null) {
            RspuStyle style = new RspuStyle();
            style.setRspuId(rspuId);
            style.setDictType("style");
            style.setStyleCode(styleCode);
            style.setIsPrimary(true);
            style.setCreatedAt(LocalDateTime.now());
            rspuStyleMapper.insert(style);
            auditLogService.logUpdate("rspu_style", rspuId,
                Map.of("styleCodes", List.of()), Map.of("styleCodes", List.of(styleCode)), operator);
        } else if (StringUtils.hasText(rspu.getPositioningLabel())) {
            boolean isGradeCode = dictService.listByType("grade").stream()
                .anyMatch(d -> rspu.getPositioningLabel().equalsIgnoreCase(d.getDictCode()));
            if (isGradeCode) {
                // 办公家具职级码不写 rspu_style（与既有三条链路口径一致），属预期内跳过
                log.info("录入定位标签为职级码，跳过 rspu_style 写入，rspuId={}, label={}",
                    rspuId, rspu.getPositioningLabel());
            } else {
                log.warn("录入定位标签未命中 style 字典，跳过 rspu_style 写入，rspuId={}, label={}",
                    rspuId, rspu.getPositioningLabel());
            }
        }

        if (sceneTags == null || sceneTags.isEmpty()) {
            return;
        }
        List<CategoryDict> sceneDict = dictService.listByType("scene");
        Set<String> seen = new HashSet<>();
        List<String> insertedCodes = new ArrayList<>();
        for (String raw : sceneTags) {
            String code = matchDictCode(raw, sceneDict);
            if (code == null) {
                log.warn("录入场景标签未命中 scene 字典，跳过 rspu_scene 写入，rspuId={}, value={}", rspuId, raw);
                continue;
            }
            if (!seen.add(code)) {
                continue;
            }
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(code);
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
            insertedCodes.add(code);
        }
        if (!insertedCodes.isEmpty()) {
            auditLogService.logUpdate("rspu_scene", rspuId,
                Map.of("sceneCodes", List.of()), Map.of("sceneCodes", insertedCodes), operator);
        }
    }

    /**
     * 字典码归一：先按字典码忽略大小写精确匹配，再按字典名精确匹配；未命中返回 null。
     *
     * @param input 原始输入
     * @param dicts 字典列表
     * @return 归一后的字典码；未命中返回 null
     */
    private String matchDictCode(String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        for (CategoryDict d : dicts) {
            if (trimmed.equalsIgnoreCase(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        for (CategoryDict d : dicts) {
            if (StringUtils.hasText(d.getDictName()) && trimmed.equals(d.getDictName())) {
                return d.getDictCode();
            }
        }
        return null;
    }

    /**
     * 容错生成并写入 RSPU 业务编码（rspu_code）。
     *
     * <p>手工/工厂录入链路不调 AI，尺寸码可选：发号失败（如未传尺寸码）仅留空不中断录入。</p>
     *
     * @return 发放的编码；null 表示发号失败，rspu_code 留空
     */
    private String assignRspuCode(RspuMaster rspu, String rawSizeCode) {
        String sizeCode = StringUtils.hasText(rawSizeCode)
            ? rawSizeCode.trim().toUpperCase()
            : null;
        return rspuCodeService.tryAssignCode(rspu.getRspuId(), rspu.getCategoryCode(), rspu.getPositioningLabel(), sizeCode);
    }

    /**
     * 为新 RSPU 创建默认变体（录入场景专用：RSPU 由当前用户在同一事务内刚创建，跳过数据权限校验）。
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
        return rspuVariantService.createVariantForEntry(rspuId, variantRequest).getVariantId();
    }

    /**
     * 保存录入图片（可选）：第一张为主图，逐张写入 image_assets 并记审计。
     *
     * <p>主图 AI 智能裁剪不在本事务内同步执行（避免长事务占库连接 + AI 慢/挂导致录入超时），
     * 而是收集主图信息后注册 afterCommit 回调，事务提交后异步投递到 taskExecutor 执行；
     * 事务回滚时裁剪不会执行，也不会产生孤儿裁剪文件。异步裁剪失败只记日志，主图保持原图。</p>
     *
     * <p>图片内容查重（2.5）：每张图写入 {@code content_hash}（SHA-256）；同内容图片已在库
     * （未软删）时抛错拦截——手工/工厂录入每次都新建 RSPU，同图即重复建档，与 createEntry
     * 同语义但不提供 force 跳过参数；同一请求内多图相同（用户重复选同一张图）仅保留首次
     * 出现的图、静默跳过重登记，不拦截整单。</p>
     */
    private List<String> storeEntryImages(String rspuId, String variantId, List<MultipartFile> images) throws IOException {
        List<String> imageIds = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return imageIds;
        }
        List<String> storedObjectKeys = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        // 主图裁剪所需信息：字节 + imageId + 对象键，事务提交后异步裁剪使用
        byte[] primaryBytes = null;
        String primaryImageId = null;
        String primaryObjectKey = null;
        long maxSize = parseMaxFileSize(maxFileSize);
        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            imageUploadValidator.validate(image, maxSize);
            // 本地磁盘存储的 store(MultipartFile) 内部走 transferTo 会移走 Tomcat 上传临时文件，
            // 之后再读内容会 NoSuchFileException；先一次性读入字节，存储与主图裁剪共用
            byte[] imageBytes = image.getBytes();
            String contentHash = ContentHashes.sha256Hex(imageBytes);
            // 同一请求内重复选择同一张图：保留首次出现的图，跳过重登记（首图永不会被跳过，主图口径不变）
            if (contentHash != null && !seenHashes.add(contentHash)) {
                log.info("同一请求内重复图片，跳过重登记，rspuId={}, filename={}", rspuId, image.getOriginalFilename());
                continue;
            }
            // 全库查重（对齐 createEntry 语义，脱敏口径同 1.5）：拦截在存储之前，不产生孤儿文件
            if (contentHash != null) {
                ImageAssets duplicate = imageAssetsMapper.selectByContentHash(contentHash);
                if (duplicate != null) {
                    throw new BusinessException(
                        buildEntryDuplicateMessageWithoutForce(image.getOriginalFilename(), duplicate));
                }
            }
            String imageId = IdGenerator.imageId();
            String objectKey = "images/" + imageId + "." + getExtension(image.getOriginalFilename());
            String storagePath = storageService.store(
                new ByteArrayInputStream(imageBytes), objectKey, imageBytes.length, image.getContentType());
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
            imageAsset.setFileSize((long) imageBytes.length);
            imageAsset.setFormat(getExtension(image.getOriginalFilename()));
            // 内容哈希落库（2.5）：供录入查重与跨链路查重命中
            imageAsset.setContentHash(contentHash);
            imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
            imageAsset.setCreatedAt(LocalDateTime.now());
            imageAssetsMapper.insert(imageAsset);
            auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());
            imageIds.add(imageId);

            // 主图智能裁剪信息收集：事务提交后异步执行，AI 识别产品主体并替换主图，
            // 失败时回退原图；响应不等待裁剪，主图可能在裁剪完成前短暂显示原图
            if (isPrimary) {
                primaryBytes = imageBytes;
                primaryImageId = imageId;
                primaryObjectKey = storagePath;
            }
        }
        registerStorageRollbackCleanup(storedObjectKeys);
        if (primaryImageId != null) {
            registerPostCommitSubjectCrop(primaryBytes, rspuId, variantId, primaryImageId, primaryObjectKey);
        }
        return imageIds;
    }

    /**
     * 注册主图裁剪任务：事务提交后（afterCommit）异步执行；无活动事务时直接异步投递。
     */
    private void registerPostCommitSubjectCrop(byte[] imageBytes, String rspuId, String variantId,
                                               String imageId, String objectKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    subjectCropService.cropAndReplacePrimaryAsync(imageBytes, rspuId, variantId, imageId, objectKey);
                }
            });
        } else {
            subjectCropService.cropAndReplacePrimaryAsync(imageBytes, rspuId, variantId, imageId, objectKey);
        }
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
        // 读入字节：一次读取同时用于内容哈希（V31）与存储，避免流二次消费
        byte[] imageBytes = imageStream.readAllBytes();

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
        String storagePath = storageService.store(new ByteArrayInputStream(imageBytes), objectKey,
            imageBytes.length, "image/" + extension);
        registerStorageRollbackCleanup(List.of(storagePath));

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setRspuId(rspuId);
        imageAsset.setImageType("white_bg");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(true);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize((long) imageBytes.length);
        imageAsset.setFormat(extension);
        imageAsset.setContentHash(ContentHashes.sha256Hex(imageBytes));
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
        // 文档导入标记：图片已经过页面级主体裁剪，异步管线跳过二次主体检测
        inputData.put("source", "document_import");
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

    /**
     * 一图多产品区域检测：AI 在单张图片中检测每个产品的位置框、预估品类与产品旁说明文字。
     *
     * @param imageBytes 图片字节
     * @return 检测到的产品区域列表（可能为空）
     */
    public List<com.rsdp.dto.DocumentProductRegion.PageProduct> detectRegionsInImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("图片内容为空");
        }
        List<com.rsdp.dto.DocumentProductRegion> pages = visionService.detectPageRegions(
            List.of(new ByteArrayInputStream(imageBytes)), null);
        if (pages == null || pages.isEmpty() || pages.get(0).getProducts() == null) {
            return List.of();
        }
        return pages.get(0).getProducts();
    }

    /**
     * 按选中的产品区域拆分建档：每个区域裁剪后独立走完整录入流程
     * （各自 RSPU + 异步 AI 识别；区域已裁剪，异步管线跳过二次主体检测）。
     *
     * @param imageBytes 原图字节
     * @param regions    选中的产品区域
     * @return 每个区域的录入结果（taskId/rspuId/imageIds，与传入顺序一致）
     * @throws IOException 图片解码或裁剪失败
     */
    public List<Map<String, Object>> createEntriesFromRegions(byte[] imageBytes,
                                                              List<RegionEntryRequest.RegionSelection> regions) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("图片内容为空");
        }
        if (regions == null || regions.isEmpty()) {
            throw new BusinessException("请至少选择一个产品区域");
        }
        java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) {
            throw new BusinessException("图片解码失败");
        }

        // 先裁剪全部区域：任一区域裁剪失败时不产生任何建档，避免半成品
        List<byte[]> croppedImages = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            com.rsdp.dto.ProductBoundingBox bbox = regions.get(i).bbox();
            if (bbox == null || !bbox.isValid()) {
                throw new BusinessException("第 " + (i + 1) + " 个产品区域框无效（须在 0~1 相对坐标内且面积大于 0）");
            }
            try {
                croppedImages.add(com.rsdp.util.ImageCropper.cropToJpeg(source, bbox, 0.9f));
            } catch (Exception e) {
                throw new BusinessException("第 " + (i + 1) + " 个产品区域裁剪失败: " + e.getMessage());
            }
        }

        // 逐区域独立事务建档（TransactionTemplate 代理 @Transactional 的 createEntryFromStream，
        // 避免 this 自调用导致事务失效）：单区域失败回滚自身并清理已存文件，不影响已建档区域
        TransactionTemplate regionTx = new TransactionTemplate(transactionManager);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            RegionEntryRequest.RegionSelection region = regions.get(i);
            byte[] cropped = croppedImages.get(i);

            // 区域检测提取的品名/尺寸文字作为 pageOcr 随任务传递，异步识别时合并进 OCR
            com.rsdp.dto.OcrResult pageOcr = null;
            if (StringUtils.hasText(region.productName()) || StringUtils.hasText(region.dimensionText())) {
                pageOcr = new com.rsdp.dto.OcrResult();
                pageOcr.setProductName(region.productName());
                pageOcr.setDimensionText(region.dimensionText());
            }

            String filename = "region-" + (i + 1) + ".jpg";
            int regionNo = i + 1;
            com.rsdp.dto.OcrResult finalPageOcr = pageOcr;
            Map<String, Object> entry = regionTx.execute(status -> {
                try {
                    return createEntryFromStream(new ByteArrayInputStream(cropped), filename, cropped.length,
                        region.categoryCode(), finalPageOcr);
                } catch (IOException e) {
                    throw new BusinessException("第 " + regionNo + " 个产品区域建档失败: " + e.getMessage());
                }
            });
            results.add(entry);
            log.info("区域拆分建档：第 {} 个区域（品类 {}）→ rspuId={}", i + 1, region.categoryCode(), entry.get("rspuId"));
        }
        return results;
    }

    /**
     * 重新触发产品 AI 识别（识别中/存疑产品的手动重试入口）。
     *
     * <p>识别任务失败或被收割器标记失败后，RSPU 停留在 processing（识别中）或
     * active + 存疑，本方法按 {@link #createEntry(List, String)} 的范式重新发起识别：
     * RSPU 置回 processing + 待复核（清掉存疑备注）、新建 product_entry 异步任务、
     * 事务提交后投递执行。识别成功/失败后由异步链路正常翻转状态。</p>
     *
     * @param rspuId RSPU ID
     * @return 包含 taskId 的映射
     */
    @Transactional
    public Map<String, Object> reRecognize(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            throw new BusinessException("产品不存在: " + rspuId);
        }
        // 数据归属：平台员工均可，工厂仅可重识别本厂已报价产品
        dataScopeHelper.assertCanAccessRspu(rspuId);

        ImageAssets primaryImage = imageAssetsMapper.selectOne(new QueryWrapper<ImageAssets>()
            .eq("rspu_id", rspuId)
            .eq("is_primary", true)
            .orderByDesc("created_at")
            .last("limit 1"));
        if (primaryImage == null) {
            throw new BusinessException("产品没有主图，无法重新识别");
        }

        // 已有在途识别任务时拒绝重复触发（input_data 为 JSON，量小，内存匹配 rspuId）
        List<AsyncTask> inflightTasks = asyncTaskMapper.selectList(new QueryWrapper<AsyncTask>()
            .eq("task_type", "product_entry")
            .in("status", "pending", "processing"));
        boolean hasInflightTask = inflightTasks != null && inflightTasks.stream()
            .anyMatch(task -> rspuId.equals(extractInputRspuId(task.getInputData())));
        if (hasInflightTask) {
            throw new BusinessException("该产品已有识别任务在执行中，请稍后再试");
        }

        // RSPU 置回识别中：清掉存疑状态与备注，等异步识别结果翻转
        RspuMaster oldSnapshot = new RspuMaster();
        org.springframework.beans.BeanUtils.copyProperties(rspu, oldSnapshot);
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        rspu.setReviewComment(null);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logReview("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());

        String taskId = IdGenerator.taskId();
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setProgress(0);
        try {
            task.setInputData(objectMapper.writeValueAsString(Map.of(
                "rspuId", rspuId,
                "imageId", primaryImage.getImageId(),
                "objectKey", primaryImage.getStoragePath(),
                "originalFilename", primaryImage.getStoragePath(),
                "source", "re_recognize"
            )));
        } catch (Exception e) {
            log.warn("序列化任务输入失败", e);
        }
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        triggerAsyncProcess(taskId, rspuId, primaryImage.getImageId(), primaryImage.getStoragePath());

        log.info("重新识别任务已创建，rspuId={}，taskId={}", rspuId, taskId);
        return Map.of(
            "taskId", taskId,
            "message", "重新识别任务已创建，正在后台识别中"
        );
    }

    /**
     * 从任务 input_data JSON 中提取 rspuId。
     *
     * @param inputData 任务输入 JSON
     * @return RSPU ID；缺失或解析失败返回 null
     */
    private String extractInputRspuId(String inputData) {
        if (!StringUtils.hasText(inputData)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(inputData).get("rspuId");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            log.warn("解析任务 input_data 失败: {}", e.getMessage());
            return null;
        }
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

    /**
     * 构造图片查重命中的报错文案。
     *
     * <p>跨数据归属脱敏口径：非平台员工（DESIGNER/FACTORY_ADMIN 等）仅返回不含已有产品
     * 品名/编码的中性表述，避免泄露他厂产品信息；平台员工（ADMIN/EDITOR）保留带定位信息
     * （品名 + 业务编码/RSPU ID）的文案，便于排查重复。</p>
     *
     * @param filename  本次上传的文件名（用户自己的文件，可保留）
     * @param duplicate 哈希命中的图片资产
     * @return 报错文案
     */
    private String buildDuplicateEntryMessage(String filename, ImageAssets duplicate) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            return "图片「" + filename + "」已录入过系统，如确属新品请使用「仍然导入」";
        }
        return "图片「" + filename + "」已录入过，对应产品：" + describeDuplicateProduct(duplicate)
            + "。如确认是不同产品，请使用「仍然导入」";
    }

    /**
     * 手工/工厂录入的图片查重命中报错文案（2.5）。
     *
     * <p>与 {@link #buildDuplicateEntryMessage} 同脱敏口径，但手工/工厂录入接口不提供
     * force 跳过参数，文案不含「仍然导入」指引，改为说明当前不支持强制跳过。</p>
     *
     * @param filename  本次上传的文件名（用户自己的文件，可保留）
     * @param duplicate 哈希命中的图片资产
     * @return 报错文案
     */
    private String buildEntryDuplicateMessageWithoutForce(String filename, ImageAssets duplicate) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            return "图片「" + filename + "」已录入过系统，请勿重复录入；如确属新品请联系平台管理员处理";
        }
        return "图片「" + filename + "」已录入过，对应产品：" + describeDuplicateProduct(duplicate)
            + "。手工/工厂录入暂不支持强制跳过查重，请确认是否为重复录入";
    }

    /**
     * 描述图片查重命中的已有产品（品名 + 业务编码/RSPU ID），用于重复导入提示。
     *
     * @param duplicate 哈希命中的图片资产
     * @return 产品描述文本
     */
    private String describeDuplicateProduct(ImageAssets duplicate) {
        if (duplicate.getRspuId() == null) {
            return "（图片 " + duplicate.getImageId() + "）";
        }
        RspuMaster rspu = rspuMapper.selectById(duplicate.getRspuId());
        if (rspu == null) {
            return "RSPU " + duplicate.getRspuId();
        }
        String name = StringUtils.hasText(rspu.getProductName()) ? rspu.getProductName() : rspu.getRspuId();
        String code = StringUtils.hasText(rspu.getRspuCode()) ? "（" + rspu.getRspuCode() + "）" : "";
        return "「" + name + "」" + code;
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
