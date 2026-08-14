package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.common.PageResult;
import com.rsdp.dto.FloorPlanBBox;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.response.FloorPlanAnalysisListItemResponse;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.FloorPlanRoomResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.Dimensions;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.ImageUploadValidator;
import com.rsdp.util.PdfRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

/**
 * 户型图分析服务（管理端，方案 v3.0 §4.2/§4.4/§4.5）。
 *
 * <p>职责：上传建单（接口 1）、分析状态与空间列表查询（接口 2，以 task 状态同步校正
 * analysis 状态）、人工校正整体替换（接口 3）、软删（接口 5），以及尺寸三级提取与
 * 空间明细落库（{@link #buildRooms}，由 {@link AsyncTaskProcessor#processFloorPlanAnalysis}
 * 在异步任务中调用，v3.0 §4.4「尺寸三级优先级在 FloorPlanService 内实现」）。</p>
 *
 * <p>归属校验与 async_task 同口径：平台运营人员（ADMIN/EDITOR）可见全部，
 * 其他用户仅能访问自己创建的分析（v3.0 §3 数据归属）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanService {

    /** 分析状态机：pending → analyzing → awaiting_confirm → confirmed；失败为 failed。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ANALYZING = "analyzing";
    public static final String STATUS_AWAITING_CONFIRM = "awaiting_confirm";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_FAILED = "failed";

    /** 异步任务类型（async_task.task_type）。 */
    public static final String TASK_TYPE = "floor_plan_analysis";

    private static final String SOURCE_ADMIN = "admin";
    private static final String SOURCE_PUBLIC = "public";

    /** 官网匿名落库的审计操作人（created_by 仍为 null，仅审计日志可辨识度）。 */
    private static final String PUBLIC_OPERATOR = "anonymous";

    private static final String DIM_SOURCE_OCR = "ocr_text";
    private static final String DIM_SOURCE_SCALE_CALC = "scale_calc";
    private static final String DIM_SOURCE_AI_ESTIMATE = "ai_estimate";
    private static final String DIM_SOURCE_MANUAL = "manual";

    private static final String CONFIDENCE_HIGH = "high";
    private static final String CONFIDENCE_MID = "mid";
    private static final String CONFIDENCE_LOW = "low";

    /** AI 估算提示词（尺寸三级提取第③级：无标注无比例尺时按常见户型经验估算）。 */
    private static final String ESTIMATE_SYSTEM_PROMPT =
        "你是住宅户型尺寸估算专家。按中国常见户型经验估算指定空间的开间和进深（单位毫米）。"
            + "只输出 JSON：{\"widthMm\": 4200, \"depthMm\": 3800}，不要任何其他文字。";

    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanRoomMapper roomMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ImageUploadValidator imageUploadValidator;
    private final StorageService storageService;
    private final VisionService visionService;
    private final AsyncTaskProcessor asyncTaskProcessor;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.floor-plan.max-file-size-mb:10}")
    private long maxFileSizeMb;

    /** PDF 首页渲染 DPI（v3.0 §8 P2，默认 200 与 PDF 导入链路既有默认一致）。 */
    @Value("${rsdp.floor-plan.pdf-render-dpi:200}")
    private float pdfRenderDpi;

    /**
     * 接口 1：上传户型图，落图 → 建 analysis 记录（source=admin）→ 创建异步任务 → 事务提交后触发分析。
     *
     * <p>支持 jpg/png 图片与 PDF（v3.0 §8 P2）：PDF 仅渲染第 1 页为 PNG
     * （{@link PdfRenderer#renderFirstPageAsPng}，DPI 走 {@code rsdp.floor-plan.pdf-render-dpi}，
     * 默认 200）后进入既有识别管线，image_assets 存渲染后的 PNG（format=png），
     * PDF 原文件不留存；PDF 非法/加密/空页返回 400 中文可读提示。</p>
     *
     * @param file 户型图（jpg/png 图片或 PDF，≤10MB，走 {@link ImageUploadValidator#validateImageOrPdf}）
     * @param hint 用户补充说明（如"这是三室两厅"），可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile file, String hint) {
        ImageUploadValidator.UploadKind uploadKind =
            imageUploadValidator.validateImageOrPdf(file, maxFileSizeMb * 1024L * 1024L);

        String analysisId = IdGenerator.floorPlanAnalysisId();
        String taskId = IdGenerator.taskId();
        String imageId = IdGenerator.imageId();
        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();

        // 1. 存户型图（imageType=floor_plan，不关联 RSPU）；PDF 先渲染首页为 PNG，原件不留存
        byte[] imageBytes = readBytes(file);
        String extension;
        if (uploadKind == ImageUploadValidator.UploadKind.PDF) {
            imageBytes = PdfRenderer.renderFirstPageAsPng(imageBytes, pdfRenderDpi);
            extension = "png";
        } else {
            extension = getExtension(file.getOriginalFilename());
        }
        String objectKey = "images/" + imageId + "." + extension;
        String storagePath;
        try {
            storagePath = uploadKind == ImageUploadValidator.UploadKind.PDF
                ? storageService.store(new ByteArrayInputStream(imageBytes), objectKey, imageBytes.length, null)
                : storageService.store(file, objectKey);
        } catch (IOException e) {
            log.error("户型图存储失败，imageId={}", imageId, e);
            throw new BusinessException("户型图存储失败");
        }
        registerStorageRollbackCleanup(storagePath);

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setImageType("floor_plan");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(false);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize((long) imageBytes.length);
        imageAsset.setFormat(extension);
        // 像素宽/高：scale_calc 尺寸提取（第②级）的换算参照，读取失败留空不阻断上传
        int[] pixelSize = readImagePixelSize(imageBytes);
        if (pixelSize != null) {
            imageAsset.setWidth(pixelSize[0]);
            imageAsset.setHeight(pixelSize[1]);
        }
        imageAsset.setUploadedBy(operator);
        imageAsset.setCreatedAt(now);
        imageAssetsMapper.insert(imageAsset);
        auditLogService.logCreate("image_assets", imageId, imageAsset, operator);

        // 2. 建分析批次记录
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_PENDING);
        analysis.setTaskId(taskId);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setCreatedBy(operator);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, operator);

        // 3. 建异步任务（input_data 携带分析上下文，供 processFloorPlanAnalysis 使用）
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType(TASK_TYPE);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("analysisId", analysisId);
        inputData.put("imageId", imageId);
        inputData.put("objectKey", storagePath);
        if (StringUtils.hasText(hint)) {
            inputData.put("hint", hint.trim());
        }
        task.setInputData(toJson(inputData));
        task.setCreatedBy(operator);
        task.setCreatedAt(now);
        asyncTaskMapper.insert(task);

        // 4. 事务提交后触发异步分析（与产品录入 triggerAsyncProcess 同模式）
        triggerAsyncAnalysis(taskId, analysisId, storagePath, hint);

        log.info("户型图分析任务已创建，analysisId={}，taskId={}", analysisId, taskId);
        return Map.of("analysisId", analysisId, "taskId", taskId);
    }

    /**
     * 分析历史列表（P1）：分页 + 可选 status 过滤，按创建时间倒序。
     *
     * <p>归属隔离与 {@link #getAnalysis} 同口径：平台运营（ADMIN/EDITOR）可见全部
     * （含官网匿名 created_by=null 的记录），其他角色仅 created_by=本人。
     * roomCount 按页内 analysisId 批量统计，避免 N+1。</p>
     *
     * @param page   页码（从 1 开始）
     * @param size   每页条数（1~100，非法值按 20 处理）
     * @param status 状态过滤（可空）
     * @return 分页列表
     */
    public PageResult<FloorPlanAnalysisListItemResponse> listAnalyses(long page, long size, String status) {
        long safePage = Math.max(1, page);
        long safeSize = size < 1 ? 20 : Math.min(size, 100);

        QueryWrapper<FloorPlanAnalysis> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim());
        }
        if (!SecurityOperatorContext.isPlatformStaff()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUsername());
        }
        wrapper.orderByDesc("created_at");

        Page<FloorPlanAnalysis> result = analysisMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        List<FloorPlanAnalysis> records = result.getRecords();

        Map<String, Long> roomCountMap = batchRoomCounts(
            records.stream().map(FloorPlanAnalysis::getAnalysisId).toList());

        List<FloorPlanAnalysisListItemResponse> rows = new ArrayList<>();
        for (FloorPlanAnalysis analysis : records) {
            FloorPlanAnalysisListItemResponse item = new FloorPlanAnalysisListItemResponse();
            item.setAnalysisId(analysis.getAnalysisId());
            item.setStatus(analysis.getStatus());
            item.setSource(analysis.getSource());
            item.setRoomCount(roomCountMap.getOrDefault(analysis.getAnalysisId(), 0L));
            item.setCreatedBy(analysis.getCreatedBy());
            item.setCreatedAt(analysis.getCreatedAt());
            item.setUpdatedAt(analysis.getUpdatedAt());
            item.setErrorMessage(analysis.getErrorMessage());
            rows.add(item);
        }
        return PageResult.of(result.getTotal(), safePage, safeSize, rows);
    }

    /**
     * 接口 2：查询分析状态与空间列表。
     *
     * <p>v3.0 §4.5 补充要求：analysis 表不做独立收割——若 JVM 崩溃，async_task 会被
     * AsyncTaskReaper 收割标 failed，而 analysis 行可能永远停在 analyzing。本方法在返回前
     * 以 task 状态为准同步校正 analysis 状态（task=failed → analysis=failed + error_message 透传）。</p>
     *
     * @param analysisId 分析批次 ID
     * @return 分析详情（含未软删空间列表，按 sort_order 升序）
     */
    public FloorPlanAnalysisResponse getAnalysis(String analysisId) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        syncStatusFromTask(analysis);

        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .orderByAsc("sort_order"));
        return toResponse(analysis, rooms);
    }

    /**
     * 接口 3：人工校正，整体替换语义——提交的列表为最终生效数据
     * （带 roomId 就地更新、不带 roomId 新增、未提交的已识别空间软删），状态 → confirmed。
     *
     * <p>人工校正后尺寸来源一律为 manual、置信度 high（v3.0 §4.4）。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    校正后的空间列表 + 可选比例尺
     * @return 校正后的分析详情
     */
    @Transactional
    public FloorPlanAnalysisResponse confirmRooms(String analysisId, FloorPlanConfirmRequest request) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        // 状态机：仅识别完成待确认（或已确认后再次修改）可提交校正
        if (!STATUS_AWAITING_CONFIRM.equals(analysis.getStatus())
            && !STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("当前状态不允许人工校正（仅识别完成待确认后可提交），当前状态: " + analysis.getStatus());
        }

        List<FloorPlanRoom> existingRooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId));
        Map<String, FloorPlanRoom> existingMap = existingRooms.stream()
            .collect(Collectors.toMap(FloorPlanRoom::getRoomId, Function.identity()));

        List<FloorPlanConfirmRequest.RoomItem> items = request.getRooms() != null
            ? request.getRooms() : List.of();
        Set<String> submittedIds = new HashSet<>();
        for (FloorPlanConfirmRequest.RoomItem item : items) {
            if (StringUtils.hasText(item.getRoomId())) {
                if (!existingMap.containsKey(item.getRoomId())) {
                    throw new BusinessException("空间不存在或不属于该分析: " + item.getRoomId());
                }
                submittedIds.add(item.getRoomId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 未提交的已识别空间视为误识别，软删（@TableLogic 语义：就地置 deleted_at）
        for (FloorPlanRoom room : existingRooms) {
            if (!submittedIds.contains(room.getRoomId())) {
                room.setDeletedAt(now);
                roomMapper.updateById(room);
            }
        }

        int sort = 0;
        for (FloorPlanConfirmRequest.RoomItem item : items) {
            String roomType = normalizeRoomType(item.getRoomType());
            BigDecimal areaM2 = computeAreaM2(item.getWidthMm(), item.getDepthMm());
            String bboxJson = toJson(item.getBbox());
            if (StringUtils.hasText(item.getRoomId())) {
                FloorPlanRoom room = existingMap.get(item.getRoomId());
                room.setRoomType(roomType);
                room.setWidthMm(item.getWidthMm());
                room.setDepthMm(item.getDepthMm());
                room.setAreaM2(areaM2);
                room.setBbox(bboxJson);
                room.setDimensionSource(DIM_SOURCE_MANUAL);
                room.setDimensionConfidence(CONFIDENCE_HIGH);
                room.setSortOrder(sort);
                room.setUpdatedAt(now);
                roomMapper.updateById(room);
            } else {
                FloorPlanRoom room = new FloorPlanRoom();
                room.setRoomId(IdGenerator.floorPlanRoomId());
                room.setAnalysisId(analysisId);
                room.setRoomType(roomType);
                room.setWidthMm(item.getWidthMm());
                room.setDepthMm(item.getDepthMm());
                room.setAreaM2(areaM2);
                room.setBbox(bboxJson);
                room.setDimensionSource(DIM_SOURCE_MANUAL);
                room.setDimensionConfidence(CONFIDENCE_HIGH);
                room.setSortOrder(sort);
                room.setCreatedAt(now);
                room.setUpdatedAt(now);
                roomMapper.insert(room);
            }
            sort++;
        }

        analysis.setStatus(STATUS_CONFIRMED);
        analysis.setConfirmedRooms(toJson(items));
        if (request.getScaleRatio() != null) {
            analysis.setScaleRatio(request.getScaleRatio());
        }
        analysis.setUpdatedAt(now);
        analysisMapper.updateById(analysis);
        auditLogService.logUpdate("floor_plan_analysis", analysisId, null, analysis,
            SecurityOperatorContext.currentUsername());

        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .orderByAsc("sort_order"));
        return toResponse(analysis, rooms);
    }

    /**
     * 接口 5：软删分析批次（@TableLogic 模式），级联软删其下空间明细。
     *
     * @param analysisId 分析批次 ID
     */
    @Transactional
    public void deleteAnalysis(String analysisId) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);

        roomMapper.delete(new QueryWrapper<FloorPlanRoom>().eq("analysis_id", analysisId));
        analysisMapper.deleteById(analysisId);
        auditLogService.logDelete("floor_plan_analysis", analysisId, analysis,
            SecurityOperatorContext.currentUsername());
        log.info("户型图分析已软删，analysisId={}", analysisId);
    }

    /**
     * 失败重试（P1）：仅 failed 状态可重试。重置 analysis 状态为 pending 并清 errorMessage，
     * 软删上一轮可能残留的空间明细（避免识别中途失败后重复落明细），新建异步任务并触发
     * {@link AsyncTaskProcessor#processFloorPlanAnalysis}（与 {@link #analyze} 同模式）。
     *
     * <p>状态判定前先以 task 状态同步校正（{@link #syncStatusFromTask}）：JVM 崩溃导致
     * analysis 停在 analyzing 而 task 已被收割为 failed 的场景也可重试。</p>
     *
     * @param analysisId 分析批次 ID
     * @return 新任务 taskId
     */
    @Transactional
    public Map<String, String> retry(String analysisId) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        syncStatusFromTask(analysis);
        if (!STATUS_FAILED.equals(analysis.getStatus())) {
            throw new BusinessException("仅识别失败的分析可重试，当前状态: " + analysis.getStatus());
        }

        ImageAssets imageAsset = imageAssetsMapper.selectById(analysis.getImageId());
        if (imageAsset == null || !StringUtils.hasText(imageAsset.getStoragePath())) {
            throw new BusinessException("户型原图不存在，无法重试: " + analysisId);
        }
        String objectKey = imageAsset.getStoragePath();
        String hint = extractHintFromTask(analysis.getTaskId());

        LocalDateTime now = LocalDateTime.now();
        // 软删上一轮残留的空间明细（识别中途失败的场景），避免重试后重复
        roomMapper.delete(new QueryWrapper<FloorPlanRoom>().eq("analysis_id", analysisId));

        String newTaskId = IdGenerator.taskId();
        analysis.setStatus(STATUS_PENDING);
        analysis.setErrorMessage(null);
        analysis.setTaskId(newTaskId);
        analysis.setUpdatedAt(now);
        analysisMapper.updateById(analysis);
        auditLogService.logUpdate("floor_plan_analysis", analysisId, null, analysis,
            SecurityOperatorContext.currentUsername());

        AsyncTask task = new AsyncTask();
        task.setTaskId(newTaskId);
        task.setTaskType(TASK_TYPE);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("analysisId", analysisId);
        inputData.put("imageId", analysis.getImageId());
        inputData.put("objectKey", objectKey);
        if (StringUtils.hasText(hint)) {
            inputData.put("hint", hint);
        }
        task.setInputData(toJson(inputData));
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(now);
        asyncTaskMapper.insert(task);

        triggerAsyncAnalysis(newTaskId, analysisId, objectKey, hint);
        log.info("户型图分析已重置并重新发起，analysisId={}，newTaskId={}", analysisId, newTaskId);
        return Map.of("taskId", newTaskId);
    }

    /**
     * 官网匿名分析落库（v3.0 §4.6 策略 B）：同步识别完成后由
     * {@link PublicAiMatchService#analyze} 调用，将识别结果作为数据资产沉淀。
     *
     * <p>落图（image_type=floor_plan，匿名上传 uploadedBy=null）→ 建 analysis
     * （source=public、created_by=null、同步识别已完成故状态直接 awaiting_confirm、
     * raw_result 留档 AI 原始结果、confirmed_rooms 为空）→ 空间明细落 floor_plan_room
     * （尺寸标注解析成功 dimension_source=ocr_text/high，否则 source 留空、confidence=low；
     * 官网同步链路不做 AI 估算，避免拖慢响应）。</p>
     *
     * @param imageBytes       户型原图字节
     * @param originalFilename 原始文件名（取扩展名）
     * @param detected         AI 空间识别结果
     * @return analysisId
     */
    @Transactional
    public String savePublicAnalysis(byte[] imageBytes, String originalFilename,
                                     FloorPlanDetectResult detected) {
        String analysisId = IdGenerator.floorPlanAnalysisId();
        String imageId = IdGenerator.imageId();
        LocalDateTime now = LocalDateTime.now();

        String extension = getExtension(originalFilename);
        String objectKey = "images/" + imageId + "." + extension;
        String storagePath;
        try {
            storagePath = storageService.store(
                new ByteArrayInputStream(imageBytes), objectKey, imageBytes.length, null);
        } catch (IOException e) {
            throw new BusinessException("户型图存储失败");
        }
        registerStorageRollbackCleanup(storagePath);

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setImageType("floor_plan");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(false);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize((long) imageBytes.length);
        imageAsset.setFormat(extension);
        imageAsset.setUploadedBy(null);
        imageAsset.setCreatedAt(now);
        imageAssetsMapper.insert(imageAsset);
        auditLogService.logCreate("image_assets", imageId, imageAsset, PUBLIC_OPERATOR);

        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_AWAITING_CONFIRM);
        analysis.setSource(SOURCE_PUBLIC);
        analysis.setRawResult(toJson(detected));
        analysis.setCreatedBy(null);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, PUBLIC_OPERATOR);

        int sortOrder = 0;
        for (FloorPlanDetectResult.Room detectedRoom : detected.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
            room.setRoomType(normalizeRoomType(detectedRoom.getRoomType()));
            room.setDimensionText(detectedRoom.getDimensionText());
            if (detectedRoom.getX() != null && detectedRoom.getY() != null
                && detectedRoom.getW() != null && detectedRoom.getH() != null) {
                room.setBbox(toJson(new FloorPlanBBox(detectedRoom.getX(), detectedRoom.getY(),
                    detectedRoom.getW(), detectedRoom.getH())));
            }
            int[] dims = Dimensions.parseDimensionMm(detectedRoom.getDimensionText());
            if (dims != null) {
                room.setDimensionSource(DIM_SOURCE_OCR);
                room.setDimensionConfidence(CONFIDENCE_HIGH);
                room.setWidthMm(dims[0]);
                room.setDepthMm(dims[1]);
                room.setAreaM2(computeAreaM2(dims[0], dims[1]));
            } else {
                room.setDimensionConfidence(CONFIDENCE_LOW);
            }
            room.setSortOrder(sortOrder++);
            room.setCreatedAt(now);
            room.setUpdatedAt(now);
            roomMapper.insert(room);
        }

        log.info("官网匿名户型分析已落库，analysisId={}，空间数={}", analysisId, detected.getRooms().size());
        return analysisId;
    }

    /**
     * 尺寸三级提取并落空间明细（由 {@link AsyncTaskProcessor#processFloorPlanAnalysis} 调用，
     * v3.0 §4.4「尺寸三级优先级在 FloorPlanService 内实现」）：
     *
     * <ol>
     *   <li>图上尺寸标注 OCR：dimensionText 经 {@link Dimensions} 解析，source=ocr_text / high；</li>
     *   <li>比例尺换算 scale_calc（P1）：scaleText 经
     *       {@link Dimensions#parseScaleConversion} 解析出可靠 像素↔毫米 关系时，
     *       结合原图像素宽/高（image_assets.width/height）与空间 bbox 归一化尺寸推算，
     *       source=scale_calc / mid；解析不出可靠关系（如仅有 "1:100"）直接跳过；</li>
     *   <li>AI 估算：无标注无可用比例尺时按常见户型经验轻量估算，source=ai_estimate / low；
     *       估算也失败则尺寸留空、confidence=low，由人工校正兜底。</li>
     * </ol>
     *
     * @param analysisId 分析批次 ID
     * @param detected   AI 空间识别结果
     */
    public void buildRooms(String analysisId, FloorPlanDetectResult detected) {
        LocalDateTime now = LocalDateTime.now();
        // 第②级换算上下文按批次解析一次（scaleText 为全图级标注）：解析不出可靠关系为 null
        ScaleContext scaleContext = resolveScaleConversion(analysisId, detected.getScaleText());
        int sortOrder = 0;
        for (FloorPlanDetectResult.Room detectedRoom : detected.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
            room.setRoomType(normalizeRoomType(detectedRoom.getRoomType()));
            room.setDimensionText(detectedRoom.getDimensionText());
            if (detectedRoom.getX() != null && detectedRoom.getY() != null
                && detectedRoom.getW() != null && detectedRoom.getH() != null) {
                room.setBbox(toJson(new FloorPlanBBox(detectedRoom.getX(), detectedRoom.getY(),
                    detectedRoom.getW(), detectedRoom.getH())));
            }

            int[] dims = Dimensions.parseDimensionMm(detectedRoom.getDimensionText());
            if (dims != null) {
                // 第①级：图上尺寸标注 OCR
                room.setDimensionSource(DIM_SOURCE_OCR);
                room.setDimensionConfidence(CONFIDENCE_HIGH);
            } else {
                // 第②级：比例尺换算（需可靠 像素↔毫米 关系 + 空间 bbox）
                dims = scaleCalcDimensions(detectedRoom, scaleContext);
                if (dims != null) {
                    room.setDimensionSource(DIM_SOURCE_SCALE_CALC);
                    room.setDimensionConfidence(CONFIDENCE_MID);
                } else {
                    // 第③级：AI 估算
                    dims = estimateRoomDimensions(room.getRoomType(), detectedRoom.getLabel());
                    if (dims != null) {
                        room.setDimensionSource(DIM_SOURCE_AI_ESTIMATE);
                    }
                    room.setDimensionConfidence(CONFIDENCE_LOW);
                }
            }
            if (dims != null) {
                room.setWidthMm(dims[0]);
                room.setDepthMm(dims[1]);
                room.setAreaM2(computeAreaM2(dims[0], dims[1]));
            }

            room.setSortOrder(sortOrder++);
            room.setCreatedAt(now);
            room.setUpdatedAt(now);
            roomMapper.insert(room);
        }
    }

    /**
     * 解析第②级比例尺换算上下文（批次级）：scaleText 有标注时才查原图像素宽/高
     * （image_assets.width/height，上传时落库）。bbox 为归一化坐标，任何写法都需要
     * 原图像素尺寸才能把 bbox 换算成空间像素尺寸，故换算关系或像素尺寸任一缺失
     * 返回 null（落到第③级）。
     */
    private ScaleContext resolveScaleConversion(String analysisId, String scaleText) {
        if (!StringUtils.hasText(scaleText)) {
            return null;
        }
        Integer imageWidthPx = null;
        Integer imageHeightPx = null;
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis != null && StringUtils.hasText(analysis.getImageId())) {
            ImageAssets imageAsset = imageAssetsMapper.selectById(analysis.getImageId());
            if (imageAsset != null) {
                imageWidthPx = imageAsset.getWidth();
                imageHeightPx = imageAsset.getHeight();
            }
        }
        Dimensions.MmPerPixel conversion =
            Dimensions.parseScaleConversion(scaleText, imageWidthPx, imageHeightPx);
        if (conversion == null || imageWidthPx == null || imageWidthPx <= 0
            || imageHeightPx == null || imageHeightPx <= 0) {
            return null;
        }
        log.info("比例尺换算关系已确立，analysisId={}，scaleText={}，mmPerPixel={}/{}",
            analysisId, scaleText, conversion.x(), conversion.y());
        return new ScaleContext(conversion.x(), conversion.y(), imageWidthPx, imageHeightPx);
    }

    /**
     * 第②级比例尺换算：空间 bbox 归一化尺寸 × 原图像素尺寸 × 每像素毫米数；
     * 换算上下文缺失或 bbox 缺失/结果非正数返回 null（落到第③级）。
     */
    private int[] scaleCalcDimensions(FloorPlanDetectResult.Room detectedRoom, ScaleContext scale) {
        if (scale == null || detectedRoom.getW() == null || detectedRoom.getH() == null
            || detectedRoom.getW() <= 0 || detectedRoom.getH() <= 0) {
            return null;
        }
        int widthMm = (int) Math.round(detectedRoom.getW() * scale.imageWidthPx() * scale.mmPerPixelX());
        int depthMm = (int) Math.round(detectedRoom.getH() * scale.imageHeightPx() * scale.mmPerPixelY());
        if (widthMm <= 0 || depthMm <= 0) {
            return null;
        }
        return new int[] {widthMm, depthMm};
    }

    /**
     * scale_calc 换算上下文：每像素毫米数（横向/纵向）+ 原图像素宽/高。
     *
     * @param mmPerPixelX   横向每像素毫米数
     * @param mmPerPixelY   纵向每像素毫米数
     * @param imageWidthPx  原图像素宽
     * @param imageHeightPx 原图像素高
     */
    private record ScaleContext(double mmPerPixelX, double mmPerPixelY,
                                int imageWidthPx, int imageHeightPx) {
    }

    /**
     * 以 task 状态为准同步校正 analysis 状态（v3.0 §4.5）：
     * 仅对非终态（pending/analyzing）生效，task=failed → analysis=failed + error_message 透传。
     */
    private void syncStatusFromTask(FloorPlanAnalysis analysis) {
        if (!STATUS_PENDING.equals(analysis.getStatus()) && !STATUS_ANALYZING.equals(analysis.getStatus())) {
            return;
        }
        if (!StringUtils.hasText(analysis.getTaskId())) {
            return;
        }
        AsyncTask task = asyncTaskMapper.selectById(analysis.getTaskId());
        if (task != null && STATUS_FAILED.equals(task.getStatus())) {
            analysis.setStatus(STATUS_FAILED);
            analysis.setErrorMessage(task.getErrorMessage());
            analysis.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(analysis);
            log.info("户型图分析状态按任务状态校正为 failed，analysisId={}", analysis.getAnalysisId());
        }
    }

    /**
     * 按 analysisId 批量统计未软删空间数（列表页避免 N+1）。
     *
     * @param analysisIds 页内分析批次 ID 列表
     * @return analysisId → 空间数
     */
    private Map<String, Long> batchRoomCounts(List<String> analysisIds) {
        if (analysisIds.isEmpty()) {
            return Map.of();
        }
        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .select("analysis_id")
            .in("analysis_id", analysisIds));
        return rooms.stream().collect(Collectors.groupingBy(
            FloorPlanRoom::getAnalysisId, Collectors.counting()));
    }

    /**
     * 从上一轮任务的 input_data 中提取用户补充说明（hint），供重试沿用；无则返回 null。
     */
    private String extractHintFromTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        try {
            AsyncTask oldTask = asyncTaskMapper.selectById(taskId);
            if (oldTask == null || !StringUtils.hasText(oldTask.getInputData())) {
                return null;
            }
            JsonNode node = objectMapper.readTree(oldTask.getInputData()).get("hint");
            return node != null && node.isTextual() && StringUtils.hasText(node.asText())
                ? node.asText() : null;
        } catch (Exception e) {
            log.warn("解析上一轮任务 hint 失败，按无 hint 重试，taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 尺寸三级提取第③级：AI 按空间类型估算开间/进深（轻量文本调用），失败返回 null。
     */
    private int[] estimateRoomDimensions(String roomType, String label) {
        try {
            String roomDesc = StringUtils.hasText(label) ? label : roomType;
            String json = visionService.chatText(ESTIMATE_SYSTEM_PROMPT,
                "请估算空间「" + roomDesc + "」的开间和进深。");
            JsonNode node = objectMapper.readTree(json);
            JsonNode width = node.get("widthMm");
            JsonNode depth = node.get("depthMm");
            if (width != null && width.isNumber() && depth != null && depth.isNumber()
                && width.asInt() > 0 && depth.asInt() > 0) {
                return new int[] {width.asInt(), depth.asInt()};
            }
            log.warn("AI 尺寸估算输出格式不符，按无尺寸处理，roomType={}，json={}", roomType, json);
        } catch (Exception e) {
            log.warn("AI 尺寸估算失败，按无尺寸处理，roomType={}", roomType, e);
        }
        return null;
    }

    /**
     * 归属校验：平台运营人员（ADMIN/EDITOR）可访问任意分析，其他用户仅能访问自己创建的
     * （与 async_task 同口径，v3.0 §3 数据归属）。
     */
    private void assertCanAccess(FloorPlanAnalysis analysis) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            String currentUser = SecurityOperatorContext.currentUsername();
            String creator = analysis.getCreatedBy();
            if (creator == null || !creator.equals(currentUser)) {
                throw new ResourceNotFoundException("户型图分析不存在: " + analysis.getAnalysisId());
            }
        }
    }

    /**
     * 事务提交后触发异步分析（与 ProductService.triggerAsyncProcess 同模式）；
     * 无活动事务时（如单元测试）直接调用。
     */
    private void triggerAsyncAnalysis(String taskId, String analysisId, String objectKey, String hint) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncTaskProcessor.processFloorPlanAnalysis(taskId, analysisId, objectKey, hint);
                }
            });
        } else {
            asyncTaskProcessor.processFloorPlanAnalysis(taskId, analysisId, objectKey, hint);
        }
    }

    /**
     * 注册事务回滚清理：事务回滚时删除已写入存储的户型图，避免孤儿文件。
     */
    private void registerStorageRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    storageService.delete(objectKey);
                } catch (IOException e) {
                    log.warn("事务回滚后清理户型图失败: {}", objectKey, e);
                }
            }
        });
    }

    private FloorPlanAnalysisResponse toResponse(FloorPlanAnalysis analysis, List<FloorPlanRoom> rooms) {
        FloorPlanAnalysisResponse response = new FloorPlanAnalysisResponse();
        response.setAnalysisId(analysis.getAnalysisId());
        response.setImageId(analysis.getImageId());
        response.setImageUrl(analysis.getImageId() != null ? "/api/v1/images/" + analysis.getImageId() : null);
        response.setStatus(analysis.getStatus());
        response.setTaskId(analysis.getTaskId());
        response.setScaleRatio(analysis.getScaleRatio());
        response.setSource(analysis.getSource());
        response.setErrorMessage(analysis.getErrorMessage());
        response.setCreatedBy(analysis.getCreatedBy());
        response.setCreatedAt(analysis.getCreatedAt());
        response.setUpdatedAt(analysis.getUpdatedAt());
        List<FloorPlanRoomResponse> roomResponses = new ArrayList<>();
        for (FloorPlanRoom room : rooms) {
            roomResponses.add(toRoomResponse(room));
        }
        response.setRooms(roomResponses);
        return response;
    }

    private FloorPlanRoomResponse toRoomResponse(FloorPlanRoom room) {
        FloorPlanRoomResponse response = new FloorPlanRoomResponse();
        response.setRoomId(room.getRoomId());
        response.setRoomType(room.getRoomType());
        response.setBbox(parseBBox(room.getBbox()));
        response.setWidthMm(room.getWidthMm());
        response.setDepthMm(room.getDepthMm());
        response.setAreaM2(room.getAreaM2());
        response.setDimensionSource(room.getDimensionSource());
        response.setDimensionConfidence(room.getDimensionConfidence());
        response.setDimensionText(room.getDimensionText());
        response.setSortOrder(room.getSortOrder());
        return response;
    }

    private FloorPlanBBox parseBBox(String bboxJson) {
        if (!StringUtils.hasText(bboxJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(bboxJson, FloorPlanBBox.class);
        } catch (Exception e) {
            log.warn("解析空间 bbox 失败，按无框处理，bbox={}", bboxJson, e);
            return null;
        }
    }

    /**
     * 空间类型归一：AI 枚举（living_room 等）映射为 room_type 字典码（LIVING_ROOM 等），
     * 其余大写化，空值兜底 OTHER。
     */
    private String normalizeRoomType(String roomType) {
        if (!StringUtils.hasText(roomType)) {
            return "OTHER";
        }
        return switch (roomType.trim().toLowerCase()) {
            case "living_room" -> "LIVING_ROOM";
            case "dining_room" -> "DINING_ROOM";
            case "study" -> "STUDY_ROOM";
            default -> roomType.trim().toUpperCase();
        };
    }

    /**
     * 计算面积（平方米，两位小数）；尺寸缺失或非正数返回 null。
     *
     * @param widthMm 开间 mm
     * @param depthMm 进深 mm
     * @return 面积（㎡）
     */
    public static BigDecimal computeAreaM2(Integer widthMm, Integer depthMm) {
        if (widthMm == null || depthMm == null || widthMm <= 0 || depthMm <= 0) {
            return null;
        }
        return BigDecimal.valueOf((long) widthMm * depthMm)
            .divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP);
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

    /**
     * 读取上传文件字节；读取失败抛 400 中文提示。
     *
     * @param file 上传文件
     * @return 文件字节
     */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.error("读取户型图上传文件失败", e);
            throw new BusinessException("读取上传文件失败");
        }
    }

    /**
     * 读取图片像素宽/高（scale_calc 的换算参照，落 image_assets.width/height）；
     * 读取失败返回 null（不阻断上传，仅放弃第②级比例尺换算）。
     *
     * @param imageBytes 图片字节（PDF 上传时为渲染后的 PNG 字节）
     * @return [widthPx, heightPx]，失败返回 null
     */
    private int[] readImagePixelSize(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new int[] {image.getWidth(), image.getHeight()};
            }
        } catch (Exception e) {
            log.warn("读取户型图像素尺寸失败，按无像素尺寸处理", e);
        }
        return null;
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
