package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.common.PageResult;
import com.rsdp.dto.FloorPlanBBox;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.request.FloorPlanActivateRequest;
import com.rsdp.dto.request.FloorPlanFurnitureConfirmRequest;
import com.rsdp.dto.request.FloorPlanTargetSceneRequest;
import com.rsdp.dto.response.FloorPlanActivationReadiness;
import com.rsdp.dto.request.ManualFloorPlanCreateRequest;
import com.rsdp.dto.request.ManualFloorPlanLayoutRequest;
import com.rsdp.dto.request.ManualFloorPlanFurnitureSaveRequest;
import com.rsdp.dto.response.FloorPlanAnalysisListItemResponse;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.FloorPlanBatchDeleteResponse;
import com.rsdp.dto.response.FloorPlanDrawingBounds;
import com.rsdp.dto.response.FloorPlanFurnitureEditResponse;
import com.rsdp.dto.response.FloorPlanFurnitureResponse;
import com.rsdp.dto.response.FloorPlanQualityIssue;
import com.rsdp.dto.response.FloorPlanRoomResponse;
import com.rsdp.dto.response.FloorPlanPoint;
import com.rsdp.dto.response.ManualFloorPlanCreateResponse;
import com.rsdp.dto.response.ManualFloorPlanLayoutSaveResponse;
import com.rsdp.dto.response.ManualFloorPlanRectResponse;
import com.rsdp.dto.response.ScaleSuggestionResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanFurniture;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.FloorPlanSceneRevision;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.floorplan.parser.FloorPlanFileType;
import com.rsdp.floorplan.parser.FloorPlanParserRegistry;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanFurnitureMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.Dimensions;
import com.rsdp.util.FloorPlanScaleSuggestion;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.ImageUploadValidator;
import com.rsdp.util.PdfRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.LinkedHashSet;
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
    public static final String STATUS_DRAFT = "draft";
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
    private static final String DIM_SOURCE_CAD = "cad_geometry";

    /** 几何来源（floor_plan_room.geometry_source / 响应 geometrySource，前端契约不可改）。 */
    public static final String GEOMETRY_SOURCE_VISION = "ai_vision";
    public static final String GEOMETRY_SOURCE_CAD = "cad_geometry";
    public static final String GEOMETRY_SOURCE_MANUAL = "manual_geometry";

    private static final String INPUT_MODE_IMAGE = "image";
    private static final String INPUT_MODE_PDF = "pdf";
    private static final String INPUT_MODE_CAD = "cad";
    private static final String INPUT_MODE_MANUAL = "manual";

    /** CAD 通道 room_type 合法字典码集合（CAD 服务已给字典码，越界兜底 OTHER）。 */
    private static final Set<String> VALID_ROOM_TYPES = Set.of(
        "LIVING_ROOM", "DINING_ROOM", "BEDROOM", "KITCHEN", "BATHROOM",
        "BALCONY", "STUDY_ROOM", "HALLWAY", "OTHER");

    private static final Set<String> CAD_EXTENSIONS = Set.of("dwg", "dxf");

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
    private final FloorPlanParserRegistry parserRegistry;
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    /** Scene Revision 服务（V20）：Revision 生命周期/激活校验/工作 Revision 派生/Diff 反馈。 */
    private final FloorPlanSceneRevisionService sceneRevisionService;
    private final FloorPlanSceneRevisionMapper sceneRevisionMapper;
    private final FloorPlanFurnitureMapper furnitureMapper;
    private final ManualLayoutGeometryService manualLayoutGeometryService;

    @Value("${rsdp.floor-plan.max-file-size-mb:10}")
    private long maxFileSizeMb;

    /** CAD 图纸（dwg/dxf）上传大小上限（CAD 户型导入 P3，独立于图片/PDF 上限）。 */
    @Value("${rsdp.floor-plan.max-cad-file-size-mb:20}")
    private long maxCadFileSizeMb;

    /** PDF 首页渲染 DPI（v3.0 §8 P2，默认 300，提升小字/尺寸标注清晰度）。 */
    @Value("${rsdp.floor-plan.pdf-render-dpi:300}")
    private float pdfRenderDpi;

    /**
     * 接口 1：上传户型图，落图 → 建 analysis 记录（source=admin）→ 创建异步任务 → 事务提交后触发分析。
     *
     * <p>支持 jpg/png 图片与 PDF（v3.0 §8 P2）：PDF 仅渲染第 1 页为 PNG
     * （{@link PdfRenderer#renderFirstPageAsPng}，DPI 走 {@code rsdp.floor-plan.pdf-render-dpi}，
     * 默认 200）后进入既有识别管线，image_assets 存渲染后的 PNG（format=png），
     * PDF 原文件不留存；PDF 非法/加密/空页返回 400 中文可读提示。</p>
     *
     * <p>CAD 户型导入 P3 起支持 dwg/dxf（≤{@code rsdp.floor-plan.max-cad-file-size-mb}，
     * 默认 20MB）：原文件留存（format=dwg/dxf，供失败重试沿用），解析走
     * {@link FloorPlanParserRegistry} 路由到 CAD 解析通道（rsdp-cad-parser 微服务），
     * 跳过视觉识别/精修/自动标定。</p>
     *
     * @param file 户型图（jpg/png 图片或 PDF ≤10MB，或 dwg/dxf ≤20MB，
     *             走 {@link ImageUploadValidator#validateImageOrPdfOrCad}）
     * @param hint 用户补充说明（如"这是三室两厅"），可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile file, String hint) {
        return analyze(file, hint, null, null);
    }

    /**
     * 接口 1（重载，V14 项目归属）：支持透传可选 projectId / sourceName。
     *
     * <p>projectId 非空时校验项目存在且当前用户可见
     * （复用 {@link ProjectService#getAccessibleProject}：归属人或 ADMIN），
     * 校验通过写入 floor_plan_analysis.project_id；sourceName 为户型名称/备注（≤128 字），
     * 两者均为可空，官网匿名链路（{@link #savePublicAnalysis}）不经过本方法、project_id 恒为 NULL。</p>
     *
     * @param file       户型图（同 {@link #analyze(MultipartFile, String)}）
     * @param hint       用户补充说明，可空
     * @param projectId  归属项目 ID，可空
     * @param sourceName 户型名称/备注，可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile file, String hint, String projectId, String sourceName) {
        String accessibleProjectId = resolveAccessibleProjectId(projectId);
        String normalizedSourceName = normalizeSourceName(sourceName);
        ImageUploadValidator.UploadKind uploadKind = imageUploadValidator.validateImageOrPdfOrCad(
            file, maxFileSizeMb * 1024L * 1024L, maxCadFileSizeMb * 1024L * 1024L);
        // 按文件类型路由解析器（CAD → rsdp-cad-parser，图片/PDF → 视觉识别）；无解析器 400
        FloorPlanFileType fileType = switch (uploadKind) {
            case IMAGE -> FloorPlanFileType.IMAGE;
            case PDF -> FloorPlanFileType.PDF;
            case CAD -> FloorPlanFileType.CAD;
        };
        parserRegistry.resolve(fileType);

        String analysisId = IdGenerator.floorPlanAnalysisId();
        String taskId = IdGenerator.taskId();
        String imageId = IdGenerator.imageId();
        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();

        // 1. 存户型图（imageType=floor_plan，不关联 RSPU）；PDF 先渲染首页为 PNG，原件不留存；
        //    CAD 原文件留存（解析服务需要原始 dwg/dxf 字节，且失败重试沿用）
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

        // 2. 建分析批次记录（V20：CAD 通道记录原文件对象键，供 target-scene 切换重解析读取）
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_PENDING);
        analysis.setTaskId(taskId);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setInputMode(switch (uploadKind) {
            case IMAGE -> INPUT_MODE_IMAGE;
            case PDF -> INPUT_MODE_PDF;
            case CAD -> INPUT_MODE_CAD;
        });
        analysis.setProjectId(accessibleProjectId);
        analysis.setSourceName(normalizedSourceName);
        if (uploadKind == ImageUploadValidator.UploadKind.CAD) {
            analysis.setCadObjectKey(storagePath);
        }
        analysis.setCreatedBy(operator);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, operator);
        // V20：同步创建 Revision 1（PROCESSING/INITIAL_PARSE），解析完成转 READY，失败转 FAILED
        FloorPlanSceneRevision initialRevision = sceneRevisionService.createProcessingRevision(
            analysis, FloorPlanSceneRevision.TRIGGER_INITIAL_PARSE, null, null, operator);
        analysisMapper.updateById(analysis);

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
        inputData.put("analysisRevision", initialRevision.getAnalysisRevision());
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
     * 接口 1（图片+CAD 双文件通道，CAD 户型导入增强）：image/cad 至少其一。
     *
     * <ul>
     *   <li>仅 image：委托 {@link #analyze(MultipartFile, String)}，现有视觉/CAD 单文件行为零变化；</li>
     *   <li>仅 cad：委托 {@link #analyze(MultipartFile, String)}（按单文件 CAD 处理，保留自动命名，无底图）；</li>
     *   <li>image + cad（双文件模式）：CAD 走 rsdp-cad-parser 出精确数据和同坐标系规范预览，
     *       image 仅作为只读参考图存 image_assets，<b>不跑视觉识别</b>；CAD 已识别的名称和类型保留，
     *       仅未命名区域等待人工命名。</li>
     * </ul>
     *
     * @param image 户型底图（jpg/png 等图片，≤{@code rsdp.floor-plan.max-file-size-mb}），可空
     * @param cad   CAD 图纸（dwg/dxf，≤{@code rsdp.floor-plan.max-cad-file-size-mb}），可空
     * @param hint  用户补充说明，可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile image, MultipartFile cad, String hint) {
        return analyze(image, cad, hint, null, null);
    }

    /**
     * 接口 1（双文件通道重载，V14 项目归属）：在
     * {@link #analyze(MultipartFile, MultipartFile, String)} 基础上支持透传可选
     * projectId / sourceName（校验规则同 {@link #analyze(MultipartFile, String, String, String)}）。
     *
     * @param image      户型底图，可空
     * @param cad        CAD 图纸，可空
     * @param hint       用户补充说明，可空
     * @param projectId  归属项目 ID，可空
     * @param sourceName 户型名称/备注，可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile image, MultipartFile cad, String hint,
                                       String projectId, String sourceName) {
        String accessibleProjectId = resolveAccessibleProjectId(projectId);
        String normalizedSourceName = normalizeSourceName(sourceName);
        boolean hasImage = image != null && !image.isEmpty();
        boolean hasCad = cad != null && !cad.isEmpty();
        if (!hasImage && !hasCad) {
            throw new BusinessException("请上传户型图片或 CAD 图纸文件");
        }
        // 单文件组合：完全沿用现有通道（行为零变化）
        if (!hasCad) {
            return analyze(image, hint, accessibleProjectId, normalizedSourceName);
        }
        if (!hasImage) {
            return analyze(cad, hint, accessibleProjectId, normalizedSourceName);
        }

        // 双文件新模式：双文件各自校验（image 走图片规则，cad 走 CAD 规则）
        ImageUploadValidator.UploadKind cadKind = imageUploadValidator.validateImageOrPdfOrCad(
            cad, maxFileSizeMb * 1024L * 1024L, maxCadFileSizeMb * 1024L * 1024L);
        if (cadKind != ImageUploadValidator.UploadKind.CAD) {
            throw new BusinessException("cad 字段仅支持 CAD 图纸文件（dwg/dxf）");
        }
        imageUploadValidator.validate(image, maxFileSizeMb * 1024L * 1024L);
        // 双文件模式固定走 CAD 解析通道（rsdp-cad-parser）；无解析器 400
        parserRegistry.resolve(FloorPlanFileType.CAD);

        String analysisId = IdGenerator.floorPlanAnalysisId();
        String taskId = IdGenerator.taskId();
        String imageId = IdGenerator.imageId();
        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();

        // 1. 存底图（imageType=floor_plan，正常记录像素宽/高，供前端底图渲染）
        byte[] imageBytes = readBytes(image);
        String imageExtension = getExtension(image.getOriginalFilename());
        String imageObjectKey = "images/" + imageId + "." + imageExtension;
        String imagePath;
        try {
            imagePath = storageService.store(image, imageObjectKey);
        } catch (IOException e) {
            log.error("户型底图存储失败，imageId={}", imageId, e);
            throw new BusinessException("户型图存储失败");
        }
        registerStorageRollbackCleanup(imagePath);

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setImageType("floor_plan");
        imageAsset.setStoragePath(imagePath);
        imageAsset.setPrimary(false);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize((long) imageBytes.length);
        imageAsset.setFormat(imageExtension);
        int[] pixelSize = readImagePixelSize(imageBytes);
        if (pixelSize != null) {
            imageAsset.setWidth(pixelSize[0]);
            imageAsset.setHeight(pixelSize[1]);
        }
        imageAsset.setUploadedBy(operator);
        imageAsset.setCreatedAt(now);
        imageAssetsMapper.insert(imageAsset);
        auditLogService.logCreate("image_assets", imageId, imageAsset, operator);

        // 2. 存 CAD 原文件（解析服务需要原始 dwg/dxf 字节，失败重试经任务 input_data 沿用；
        //    不入 image_assets——analysis 的展示图是底图）
        String cadExtension = getExtension(cad.getOriginalFilename());
        String cadObjectKey = "cad/" + IdGenerator.imageId() + "." + cadExtension;
        String cadPath;
        try {
            cadPath = storageService.store(cad, cadObjectKey);
        } catch (IOException e) {
            log.error("CAD 图纸存储失败，analysisId={}", analysisId, e);
            throw new BusinessException("CAD 图纸存储失败");
        }
        registerStorageRollbackCleanup(cadPath);

        // 3. 建分析批次记录（imageId 指向底图，imageUrl 即预览图地址；
        //    V20：cadObjectKey 记录 CAD 原文件对象键，供 target-scene 切换重解析读取）
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_PENDING);
        analysis.setTaskId(taskId);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setInputMode(INPUT_MODE_CAD);
        analysis.setProjectId(accessibleProjectId);
        analysis.setSourceName(normalizedSourceName);
        analysis.setCadObjectKey(cadPath);
        analysis.setCreatedBy(operator);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, operator);
        // V20：同步创建 Revision 1（PROCESSING/INITIAL_PARSE），解析完成转 READY，失败转 FAILED
        FloorPlanSceneRevision initialRevision = sceneRevisionService.createProcessingRevision(
            analysis, FloorPlanSceneRevision.TRIGGER_INITIAL_PARSE, null, null, operator);
        analysisMapper.updateById(analysis);

        // 4. 建异步任务：input_data 额外携带 cadObjectKey + codeNameMode（失败重试沿用）
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType(TASK_TYPE);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("analysisId", analysisId);
        inputData.put("imageId", imageId);
        inputData.put("objectKey", imagePath);
        inputData.put("cadObjectKey", cadPath);
        inputData.put("codeNameMode", true);
        inputData.put("analysisRevision", initialRevision.getAnalysisRevision());
        if (StringUtils.hasText(hint)) {
            inputData.put("hint", hint.trim());
        }
        task.setInputData(toJson(inputData));
        task.setCreatedBy(operator);
        task.setCreatedAt(now);
        asyncTaskMapper.insert(task);

        // 5. 事务提交后触发异步分析（CAD 通道 + 代号模式）
        triggerAsyncAnalysis(taskId, analysisId, imagePath, hint, cadPath, true);

        log.info("户型图双文件分析任务已创建，analysisId={}，taskId={}", analysisId, taskId);
        return Map.of("analysisId", analysisId, "taskId", taskId);
    }

    /**
     * 创建无图纸手画布局草稿。该路径不创建图片资产和异步 AI 任务，直接进入 draft 状态。
     *
     * @param request 画布、项目与户型名称
     * @return 分析批次 ID、draft 状态与初始 revision
     */
    @Transactional
    public ManualFloorPlanCreateResponse createManualLayout(ManualFloorPlanCreateRequest request) {
        String projectId = resolveAccessibleProjectId(request.projectId());
        String sourceName = normalizeSourceName(request.sourceName());
        ManualFloorPlanLayoutRequest.Layout initialLayout = new ManualFloorPlanLayoutRequest.Layout(
            1,
            new ManualFloorPlanLayoutRequest.Canvas(
                request.canvasWidthMm(), request.canvasHeightMm(), request.gridMm()),
            List.of());

        String analysisId = IdGenerator.floorPlanAnalysisId();
        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setStatus(STATUS_DRAFT);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setInputMode(INPUT_MODE_MANUAL);
        analysis.setLayoutJson(toJson(initialLayout));
        analysis.setLayoutRevision(0L);
        analysis.setProjectId(projectId);
        analysis.setSourceName(sourceName);
        analysis.setCreatedBy(operator);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, operator);
        return new ManualFloorPlanCreateResponse(analysisId, STATUS_DRAFT, 0L);
    }

    /**
     * 以乐观锁保存手画布局完整快照。草稿允许暂时存在重叠或不连通形状，严格几何校验在确认时执行。
     *
     * @param analysisId 分析批次 ID
     * @param request 布局快照与 expectedRevision
     * @return 保存后的新 revision
     */
    @Transactional
    public ManualFloorPlanLayoutSaveResponse saveManualLayout(
        String analysisId, ManualFloorPlanLayoutRequest request) {
        FloorPlanAnalysis analysis = requireManualDraft(analysisId);
        String layoutJson = toJson(request.layout());
        int updated = analysisMapper.updateManualLayout(
            analysisId, request.expectedRevision(), layoutJson);
        if (updated != 1) {
            FloorPlanAnalysis latest = analysisMapper.selectById(analysisId);
            long latestRevision = latest != null && latest.getLayoutRevision() != null
                ? latest.getLayoutRevision() : -1L;
            throw new BusinessException("布局已在其他页面更新，请刷新后继续（当前版本 " + latestRevision + "）");
        }
        long newRevision = request.expectedRevision() + 1;
        auditLogService.logAction("floor_plan_analysis", analysisId, "SAVE_LAYOUT",
            Map.of("layoutRevision", request.expectedRevision()),
            Map.of("layoutRevision", newRevision),
            SecurityOperatorContext.currentUsername());
        return new ManualFloorPlanLayoutSaveResponse(analysisId, newRevision);
    }

    /**
     * 在已确认的手画户型上仅保存规划家具；空间几何与已物化房间保持不变。
     *
     * @param analysisId 分析批次 ID
     * @param request 家具列表与乐观锁版本
     * @return 保存后的新 revision
     */
    @Transactional
    public ManualFloorPlanLayoutSaveResponse saveManualFurnitureLayout(
        String analysisId, ManualFloorPlanFurnitureSaveRequest request) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        assertCanAccess(analysis);
        if (!INPUT_MODE_MANUAL.equals(analysis.getInputMode()) || !STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("仅已确认的无图纸手画布局可单独保存规划家具");
        }
        ManualFloorPlanLayoutRequest.Layout oldLayout;
        try {
            oldLayout = objectMapper.readValue(analysis.getLayoutJson(), ManualFloorPlanLayoutRequest.Layout.class);
        } catch (Exception e) {
            log.warn("读取已确认手画布局失败，analysisId={}", analysisId, e);
            throw new BusinessException("已确认的手画布局无法读取");
        }
        ManualFloorPlanLayoutRequest.Layout nextLayout = new ManualFloorPlanLayoutRequest.Layout(
            Math.max(2, oldLayout.version()), oldLayout.canvas(), oldLayout.rooms(), request.furniture());
        manualLayoutGeometryService.compute(nextLayout);
        int updated = analysisMapper.updateManualFurnitureLayout(
            analysisId, request.expectedRevision(), toJson(nextLayout));
        if (updated != 1) {
            FloorPlanAnalysis latest = analysisMapper.selectById(analysisId);
            long latestRevision = latest != null && latest.getLayoutRevision() != null
                ? latest.getLayoutRevision() : -1L;
            throw new BusinessException("布局已在其他页面更新，请刷新后继续（当前版本 " + latestRevision + "）");
        }
        long newRevision = request.expectedRevision() + 1;
        auditLogService.logAction("floor_plan_analysis", analysisId, "SAVE_FURNITURE_LAYOUT",
            Map.of("layoutRevision", request.expectedRevision()),
            Map.of("layoutRevision", newRevision, "furnitureCount", request.furniture().size()),
            SecurityOperatorContext.currentUsername());
        return new ManualFloorPlanLayoutSaveResponse(analysisId, newRevision);
    }

    /**
     * 确认手画布局：后端权威重算矩形并集，校验连通性、孔洞、跨空间重叠和主布置区，
     * 原子物化为 floor_plan_room，随后复用现有方案生成链路。
     *
     * @param analysisId 分析批次 ID
     * @return 已确认的分析详情
     */
    @Transactional
    public FloorPlanAnalysisResponse confirmManualLayout(String analysisId) {
        FloorPlanAnalysis analysis = requireManualDraft(analysisId);
        ManualFloorPlanLayoutRequest.Layout layout;
        try {
            layout = objectMapper.readValue(
                analysis.getLayoutJson(), ManualFloorPlanLayoutRequest.Layout.class);
        } catch (Exception e) {
            log.warn("读取手画布局草稿失败，analysisId={}", analysisId, e);
            throw new BusinessException("手画布局草稿无法读取，请重新保存后再确认");
        }
        if (layout.rooms() == null || layout.rooms().isEmpty()) {
            throw new BusinessException("至少绘制一个空间后才能确认布局");
        }
        ManualLayoutGeometryService.ComputedLayout computed = manualLayoutGeometryService.compute(layout);

        roomMapper.delete(new QueryWrapper<FloorPlanRoom>().eq("analysis_id", analysisId));
        LocalDateTime now = LocalDateTime.now();
        int sortOrder = 0;
        for (ManualLayoutGeometryService.ComputedRoom computedRoom : computed.rooms()) {
            ManualFloorPlanLayoutRequest.Room source = computedRoom.source();
            String roomType = normalizeRoomType(source.roomType());
            if (!VALID_ROOM_TYPES.contains(roomType)) {
                throw new BusinessException("不支持的空间类型: " + source.roomType());
            }
            String label = StringUtils.hasText(source.label())
                ? source.label().trim() : "未命名空间 " + (sortOrder + 1);
            ManualLayoutGeometryService.Bounds bounds = computedRoom.bounds();
            ManualFloorPlanLayoutRequest.Rect placement = computedRoom.placementZone();

            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
            room.setRoomType(roomType);
            room.setLabel(label);
            room.setPolygon(toJson(computedRoom.polygon().stream()
                .map(point -> List.of((double) point.x(), (double) point.y()))
                .toList()));
            room.setCentroid(toJson(Map.of(
                "x", (double) computedRoom.labelPoint().x(),
                "y", (double) computedRoom.labelPoint().y())));
            room.setBbox(toJson(new FloorPlanBBox(
                (double) bounds.minX() / layout.canvas().widthMm(),
                (double) bounds.minY() / layout.canvas().heightMm(),
                (double) (bounds.maxX() - bounds.minX()) / layout.canvas().widthMm(),
                (double) (bounds.maxY() - bounds.minY()) / layout.canvas().heightMm())));
            room.setWidthMm(placement.w());
            room.setDepthMm(placement.h());
            room.setAreaM2(BigDecimal.valueOf(computedRoom.areaMm2())
                .divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP));
            room.setDimensionSource(DIM_SOURCE_MANUAL);
            room.setDimensionConfidence(CONFIDENCE_HIGH);
            room.setGeometrySource(GEOMETRY_SOURCE_MANUAL);
            room.setAnalysisRevision(1);
            room.setConfirmationStatus("CONFIRMED");
            room.setShapeParts(toJson(source.parts()));
            room.setPlacementZone(toJson(Map.of(
                "primary", placement,
                "zones", computedRoom.placementZones())));
            room.setSortOrder(sortOrder++);
            room.setCreatedAt(now);
            room.setUpdatedAt(now);
            roomMapper.insert(room);
        }

        analysis.setStatus(STATUS_CONFIRMED);
        analysis.setConfirmedRooms(toJson(layout.rooms()));
        analysis.setUpdatedAt(now);
        analysisMapper.updateById(analysis);
        auditLogService.logUpdate("floor_plan_analysis", analysisId, null, analysis,
            SecurityOperatorContext.currentUsername());
        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .orderByAsc("sort_order"));
        return toResponse(analysis, rooms);
    }

    private FloorPlanAnalysis requireManualDraft(String analysisId) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        if (!INPUT_MODE_MANUAL.equals(analysis.getInputMode())) {
            throw new BusinessException("当前记录不是无图纸手画布局");
        }
        if (!STATUS_DRAFT.equals(analysis.getStatus())) {
            throw new BusinessException("当前状态不允许编辑手画布局: " + analysis.getStatus());
        }
        return analysis;
    }

    /**
     * 官网游客 CAD 分析：复用受保护端的 CAD 异步管线，但将分析标记为公开来源且不绑定用户。
     *
     * <p>调用方必须通过独立的短期访问凭证保护后续状态查询和文件读取；本方法本身不生成
     * 凭证。支持仅 CAD，以及 JPG/PNG 参考图 + CAD 双文件模式。</p>
     *
     * @param image      可选 JPG/PNG 参考图
     * @param cad        DWG/DXF 图纸
     * @param sourceName 户型名称，可空
     * @return analysisId + taskId
     */
    @Transactional
    public Map<String, String> analyzePublicCad(MultipartFile image, MultipartFile cad,
                                                 String sourceName) {
        Map<String, String> result = analyze(image, cad, null, null, sourceName);
        FloorPlanAnalysis analysis = analysisMapper.selectById(result.get("analysisId"));
        if (analysis == null) {
            throw new BusinessException("游客户型分析创建失败");
        }
        analysis.setSource(SOURCE_PUBLIC);
        analysis.setCreatedBy(null);
        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);
        return result;
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
        return listAnalyses(page, size, status, null, false);
    }

    /**
     * 分析历史列表（重载，V14 项目归属）：支持按 projectId 过滤；
     * 列表项增强缩略图、项目名称、几何来源与质量提示数（见 {@link #toListItems}）。
     *
     * @param page      页码（从 1 开始）
     * @param size      每页条数（1~100，非法值按 20 处理）
     * @param status    状态过滤（可空）
     * @param projectId 归属项目过滤（可空）
     * @return 分页列表
     */
    public PageResult<FloorPlanAnalysisListItemResponse> listAnalyses(long page, long size,
                                                                      String status, String projectId) {
        return listAnalyses(page, size, status, projectId, false);
    }

    /**
     * 分析历史列表：支持项目精确过滤或仅查询未归属记录，筛选在数据库分页前完成，
     * 保证总数与分页结果一致。
     *
     * @param page       页码（从 1 开始）
     * @param size       每页条数（1~100，非法值按 20 处理）
     * @param status     状态过滤（可空）
     * @param projectId  归属项目过滤（可空）
     * @param unassigned 是否只查询 project_id 为空的记录；为 true 时忽略 projectId
     * @return 分页列表
     */
    public PageResult<FloorPlanAnalysisListItemResponse> listAnalyses(long page, long size,
                                                                      String status, String projectId,
                                                                      boolean unassigned) {
        long safePage = Math.max(1, page);
        long safeSize = size < 1 ? 20 : Math.min(size, 100);

        QueryWrapper<FloorPlanAnalysis> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim());
        }
        if (unassigned) {
            wrapper.isNull("project_id");
        } else if (StringUtils.hasText(projectId)) {
            wrapper.eq("project_id", projectId.trim());
        }
        if (!SecurityOperatorContext.isPlatformStaff()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUsername());
        }
        wrapper.orderByDesc("created_at");

        Page<FloorPlanAnalysis> result = analysisMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        return PageResult.of(result.getTotal(), safePage, safeSize, toListItems(result.getRecords()));
    }

    /**
     * 项目下户型图分析批次列表（V14，GET /api/v1/projects/{projectId}/floor-plans）：
     * 校验项目可见性（复用 {@link ProjectService#getAccessibleProject}）后返回该项目下
     * 未软删的全部批次（created_at DESC），列表项结构与历史列表一致。
     *
     * @param projectId 项目 ID
     * @return 项目下分析批次列表
     */
    public List<FloorPlanAnalysisListItemResponse> listByProject(String projectId) {
        projectService.getAccessibleProject(projectId);
        List<FloorPlanAnalysis> records = analysisMapper.selectList(new QueryWrapper<FloorPlanAnalysis>()
            .eq("project_id", projectId.trim())
            .orderByDesc("created_at"));
        return toListItems(records);
    }

    /**
     * 列表项组装（V14 增强）：空间数与几何来源按页内 analysisId 批量聚合（一次查询避免 N+1），
     * 项目名称按页内 projectId 批量取数；缩略图取 CAD 规范预览图优先、回退户型原图。
     *
     * @param records 分析批次记录
     * @return 列表项
     */
    private List<FloorPlanAnalysisListItemResponse> toListItems(List<FloorPlanAnalysis> records) {
        Map<String, Long> roomCountMap = new HashMap<>();
        Map<String, String> geometrySourceMap = new HashMap<>();
        batchRoomStats(records, roomCountMap, geometrySourceMap);
        Map<String, String> projectNameMap = batchProjectNames(records.stream()
            .map(FloorPlanAnalysis::getProjectId).filter(StringUtils::hasText).distinct().toList());

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
            item.setThumbnailUrl(thumbnailUrlOf(analysis));
            item.setProjectId(analysis.getProjectId());
            // Map.of() 不允许 null 键查询，无归属时直接置 null
            item.setProjectName(StringUtils.hasText(analysis.getProjectId())
                ? projectNameMap.get(analysis.getProjectId()) : null);
            item.setSourceName(analysis.getSourceName());
            String geometrySource = geometrySourceMap.get(analysis.getAnalysisId());
            if (geometrySource == null && INPUT_MODE_MANUAL.equals(analysis.getInputMode())) {
                geometrySource = GEOMETRY_SOURCE_MANUAL;
            }
            item.setGeometrySource(geometrySource);
            item.setQualityIssueCount(qualityIssueCount(analysis.getQualityIssues(), analysis.getRawResult()));
            item.setInputMode(analysis.getInputMode());
            rows.add(item);
        }
        return rows;
    }

    /** 列表缩略图（V14）：CAD 规范预览图优先，空则回退户型原图；均无图为 null。 */
    private String thumbnailUrlOf(FloorPlanAnalysis analysis) {
        String imageId = StringUtils.hasText(analysis.getPreviewImageId())
            ? analysis.getPreviewImageId() : analysis.getImageId();
        return StringUtils.hasText(imageId) ? "/api/v1/images/" + imageId : null;
    }

    /**
     * 质量提示数（V14）：优先读 quality_issues 冗余列；历史 CAD 记录该列为空时，
     * 回退 raw_result.qualityIssues，确保迁移前的分析记录也能正确展示质量提示数。
     */
    private int qualityIssueCount(String qualityIssuesJson, String rawResultJson) {
        try {
            if (StringUtils.hasText(qualityIssuesJson)) {
                JsonNode node = objectMapper.readTree(qualityIssuesJson);
                return node.isArray() ? node.size() : 0;
            }
            if (StringUtils.hasText(rawResultJson)) {
                JsonNode qualityIssues = objectMapper.readTree(rawResultJson).path("qualityIssues");
                return qualityIssues.isArray() ? qualityIssues.size() : 0;
            }
            return 0;
        } catch (Exception e) {
            log.warn("解析户型分析质量提示失败，按 0 处理", e);
            return 0;
        }
    }

    /**
     * 按 projectId 批量取项目名称（列表/详情避免 N+1）；入参为空返回空 Map（不触库）。
     *
     * @param projectIds 项目 ID 列表（已去重、非空元素）
     * @return projectId → projectName
     */
    private Map<String, String> batchProjectNames(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(projectIds).stream()
            .collect(Collectors.toMap(Project::getProjectId, Project::getProjectName, (a, b) -> a));
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
        return getAnalysis(analysisId, null);
    }

    /**
     * 接口 2（V20 Revision 化）：默认装配 revision = working ?? active ?? latest；
     * 指定 analysisRevision 时校验其属于本分析（否则 404）。
     * 房间/家具只来自同一 Revision（Invariant 1/2：禁止跨 Revision 拼装）。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 指定 Revision 序号，可空
     * @return 分析详情
     */
    public FloorPlanAnalysisResponse getAnalysis(String analysisId, Integer analysisRevision) {
        return getAnalysisInternal(analysisId, analysisRevision, true);
    }

    /**
     * 查询官网游客 CAD 分析详情；调用方须在进入本方法前完成匿名访问凭证校验。
     *
     * @param analysisId 分析批次 ID
     * @return 分析详情
     */
    public FloorPlanAnalysisResponse getPublicAnalysis(String analysisId) {
        return getAnalysisInternal(analysisId, null, false);
    }

    /**
     * 查询官网游客 CAD 分析详情（V20，可指定 Revision；与受保护端走同一装配逻辑，保持回归）。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 指定 Revision 序号，可空
     * @return 分析详情
     */
    public FloorPlanAnalysisResponse getPublicAnalysis(String analysisId, Integer analysisRevision) {
        return getAnalysisInternal(analysisId, analysisRevision, false);
    }

    private FloorPlanAnalysisResponse getAnalysisInternal(String analysisId, Integer analysisRevision,
                                                          boolean enforceOwner) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        if (enforceOwner) {
            assertCanAccess(analysis);
        } else if (!SOURCE_PUBLIC.equals(analysis.getSource())) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        syncStatusFromTask(analysis);

        FloorPlanSceneRevision revision = resolveDisplayRevision(analysis, analysisRevision);
        if (revision == null) {
            // 兜底：无 Revision 行的历史数据（V20 回填后不应出现）按旧装配读取
            List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
                .eq("analysis_id", analysisId)
                .orderByAsc("sort_order"));
            FloorPlanAnalysisResponse legacy = toResponse(analysis, rooms);
            if (!GEOMETRY_SOURCE_CAD.equals(legacy.getGeometrySource())) {
                legacy.setScaleSuggestion(buildScaleSuggestion(analysis, rooms));
            }
            return legacy;
        }

        List<FloorPlanRoom> rooms = sceneRevisionService.listRooms(analysisId, revision.getAnalysisRevision());
        List<FloorPlanFurniture> furniture = sceneRevisionService.listFurniture(analysisId,
            revision.getAnalysisRevision());
        FloorPlanAnalysisResponse response = toResponse(analysis, revision, rooms, furniture);
        // CAD 通道尺寸来自真实几何，跳过自动标定建议（CAD 户型导入 P3）
        if (!GEOMETRY_SOURCE_CAD.equals(response.getGeometrySource())
            && !GEOMETRY_SOURCE_MANUAL.equals(response.getGeometrySource())) {
            response.setScaleSuggestion(buildScaleSuggestion(analysis, rooms));
        }
        return response;
    }

    /**
     * 解析展示 Revision（V20）：指定参数时校验属于本分析；默认 working ?? active ?? latest。
     *
     * @param analysis         分析记录
     * @param analysisRevision 指定 Revision，可空
     * @return 展示 Revision；无 Revision 行返回 null（调用方走旧装配兜底）
     */
    private FloorPlanSceneRevision resolveDisplayRevision(FloorPlanAnalysis analysis, Integer analysisRevision) {
        if (analysisRevision != null) {
            FloorPlanSceneRevision revision = sceneRevisionService.getRevision(
                analysis.getAnalysisId(), analysisRevision);
            if (revision == null) {
                throw new ResourceNotFoundException(
                    "Scene Revision 不存在或不属于该分析: " + analysisRevision);
            }
            return revision;
        }
        Integer displayNo = analysis.getWorkingAnalysisRevision() != null
            ? analysis.getWorkingAnalysisRevision()
            : analysis.getActiveAnalysisRevision() != null
                ? analysis.getActiveAnalysisRevision()
                : analysis.getLatestAnalysisRevision();
        return displayNo != null
            ? sceneRevisionService.getRevision(analysis.getAnalysisId(), displayNo) : null;
    }

    /**
     * 自动标定建议（户型图优化二期）：用落库房间的高置信 OCR 尺寸 + bbox 反推全图比例
     * （mm/px）。在查询时基于已落库明细实时计算（不落库、不改表），像素宽/高取自
     * image_assets（上传时落库的天然宽高）；图片或像素尺寸缺失时 status=null。
     */
    private ScaleSuggestionResponse buildScaleSuggestion(FloorPlanAnalysis analysis,
                                                         List<FloorPlanRoom> rooms) {
        Integer imageWidthPx = null;
        Integer imageHeightPx = null;
        if (StringUtils.hasText(analysis.getImageId())) {
            ImageAssets imageAsset = imageAssetsMapper.selectById(analysis.getImageId());
            if (imageAsset != null) {
                imageWidthPx = imageAsset.getWidth();
                imageHeightPx = imageAsset.getHeight();
            }
        }
        List<FloorPlanScaleSuggestion.RoomExtent> extents = rooms.stream()
            .map(room -> {
                FloorPlanBBox bbox = parseBBox(room.getBbox());
                return new FloorPlanScaleSuggestion.RoomExtent(
                    roomTypeLabel(room.getRoomType()), room.getDimensionText(),
                    bbox != null ? bbox.getW() : null,
                    bbox != null ? bbox.getH() : null);
            })
            .toList();
        return FloorPlanScaleSuggestion.suggest(extents, imageWidthPx, imageHeightPx);
    }

    /** room_type 字典码 → 中文标注名（标定建议 basisLabel 展示用）。 */
    private static String roomTypeLabel(String roomType) {
        if (!StringUtils.hasText(roomType)) {
            return null;
        }
        return switch (roomType.trim().toUpperCase()) {
            case "LIVING_ROOM" -> "客厅";
            case "DINING_ROOM" -> "餐厅";
            case "BEDROOM" -> "卧室";
            case "KITCHEN" -> "厨房";
            case "BATHROOM" -> "卫生间";
            case "BALCONY" -> "阳台";
            case "STUDY_ROOM", "STUDY" -> "书房";
            case "HALLWAY" -> "过道";
            default -> "其他";
        };
    }

    /**
     * 接口 3（V20 Revision 化）：人工校正整体提交。
     *
     * <p>请求携带 analysisRevision + baseRoomRevision 时走 Revision-aware 编辑语义
     * （{@link #editRevisionRooms}）：只修改目标工作 Revision 的 Room 对象，
     * <b>不</b>改 active 指针、<b>不</b>置 analysis.status=confirmed（Invariant 6/9）。</p>
     *
     * <p>不携带时为 legacy 兼容包装（{@link #legacyConfirmRooms}）：仅
     * latest_analysis_revision=1 的分析允许（否则 400 REVISION_REQUIRED），内部绑定
     * Revision 1，保持旧可观察语义（整体替换 + status=confirmed + confirmed_rooms 快照 +
     * 审计），同事务隐式激活 Revision 1。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    校正后的空间列表 + 可选比例尺/Revision 版本
     * @return 校正后的分析详情
     */
    @Transactional
    public FloorPlanAnalysisResponse confirmRooms(String analysisId, FloorPlanConfirmRequest request) {
        if (request.getAnalysisRevision() != null || request.getBaseRoomRevision() != null) {
            return editRevisionRooms(analysisId, request);
        }
        return legacyConfirmRooms(analysisId, request);
    }

    /**
     * Revision-aware Room 编辑（V20，设计文档 V1.2.3 §18.7）。
     *
     * <p>流程（§8.4.6 锁顺序 + 乐观版本 + 幂等重放）：锁 analysis → 锁目标 revision →
     * 校验 revision 属于本分析且 == working_analysis_revision、状态 ∈ READY/EDITING/
     * READY_TO_ACTIVATE（否则 409 REVISION_NOT_EDITABLE）→ 幂等重放判定
     * （baseRoomRevision==room_submit_base 且请求哈希一致 → 返回当前状态）→
     * baseRoomRevision==room_revision（否则 409 VERSION_CONFLICT）→ 整体替换该 Revision
     * 房间（已有 ID 更新、缺席软删、无 ID 新增；CAD 几何房仍只允许改 label/roomType/
     * sortOrder）→ room_revision+1 → revision_status=EDITING → readiness 重算
     * （通过 → READY_TO_ACTIVATE）→ 记录 Room Diff（cad_analysis_feedback）。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    空间列表 + analysisRevision + baseRoomRevision
     * @return 校正后的分析详情（含新 roomRevision）
     */
    @Transactional
    public FloorPlanAnalysisResponse editRevisionRooms(String analysisId, FloorPlanConfirmRequest request) {
        if (request.getAnalysisRevision() == null || request.getBaseRoomRevision() == null) {
            throw new BusinessException("analysisRevision 与 baseRoomRevision 必须成对携带");
        }
        // ① 锁 analysis
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        if (!STATUS_AWAITING_CONFIRM.equals(analysis.getStatus())
            && !STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("当前状态不允许人工校正（仅识别完成待确认后可提交），当前状态: " + analysis.getStatus());
        }
        // ② 锁目标 revision 并校验工作指针与可编辑状态（Invariant 10）
        Integer revisionNo = request.getAnalysisRevision();
        FloorPlanSceneRevision revision = sceneRevisionService.lockRevision(analysisId, revisionNo);
        if (revision == null) {
            throw new ResourceNotFoundException("Scene Revision 不存在或不属于该分析: " + revisionNo);
        }
        if (!FloorPlanSceneRevisionService.revisionEquals(analysis.getWorkingAnalysisRevision(), revisionNo)) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "目标 Revision 不是当前工作 Revision（working=" + analysis.getWorkingAnalysisRevision() + "）");
        }
        if (!FloorPlanSceneRevisionService.EDITABLE_STATUSES.contains(revision.getRevisionStatus())) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "Revision 状态 " + revision.getRevisionStatus() + " 不允许编辑");
        }

        String operator = SecurityOperatorContext.currentUsername();
        String submitHash = sceneRevisionService.submitHash(request.getRooms());
        // ③ 幂等重放：同 base + 同内容 → 返回当前状态，不产生重复写
        if (FloorPlanSceneRevisionService.revisionEquals(revision.getRoomSubmitBase(),
            request.getBaseRoomRevision())
            && submitHash != null && submitHash.equals(revision.getRoomSubmitHash())) {
            log.info("rooms 提交幂等重放，analysisId={}，revision={}，base={}",
                analysisId, revisionNo, request.getBaseRoomRevision());
            return assembleRevisionResponse(analysis, revision);
        }
        // ④ 乐观版本校验（Invariant 4：roomRevision 与 analysisRevision 是两个维度）
        if (!FloorPlanSceneRevisionService.revisionEquals(revision.getRoomRevision(),
            request.getBaseRoomRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "baseRoomRevision=" + request.getBaseRoomRevision()
                    + " 与当前 roomRevision=" + revision.getRoomRevision() + " 不一致，请刷新后重试");
        }

        // ⑤ 整体替换该 Revision 房间 + Diff
        replaceRevisionRooms(analysis, revision, request.getRooms(), operator, true);

        // ⑥ 版本推进 + 状态迁移 + 幂等键
        revision.setRoomRevision(revision.getRoomRevision() + 1);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_EDITING);
        revision.setRoomSubmitBase(request.getBaseRoomRevision());
        revision.setRoomSubmitHash(submitHash);
        sceneRevisionMapper.updateByRevision(revision);
        sceneRevisionService.applyReadinessTransition(revision);

        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);
        return assembleRevisionResponse(analysis, sceneRevisionService.getRevision(analysisId, revisionNo));
    }

    /**
     * legacy 兼容包装（V20 §18.7 选项 2）：旧前端/官网/视觉通道不带 Revision 参数时，
     * 仅 latest=1 的分析允许（否则 400 REVISION_REQUIRED），内部绑定 Revision 1，
     * 保持旧可观察语义（整体替换 + status=confirmed + confirmed_rooms 快照 + 审计），
     * 同事务内把 Revision 1 置 ACTIVE（隐式激活：active=1、working=NULL）。
     *
     * @param analysisId 分析批次 ID
     * @param request    校正后的空间列表 + 可选比例尺/项目
     * @return 校正后的分析详情
     */
    private FloorPlanAnalysisResponse legacyConfirmRooms(String analysisId, FloorPlanConfirmRequest request) {
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        // 状态机：仅识别完成待确认（或已确认后再次修改）可提交校正
        if (!STATUS_AWAITING_CONFIRM.equals(analysis.getStatus())
            && !STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("当前状态不允许人工校正（仅识别完成待确认后可提交），当前状态: " + analysis.getStatus());
        }
        if (analysis.getLatestAnalysisRevision() != null && analysis.getLatestAnalysisRevision() != 1) {
            throw new BusinessException(400,
                "REVISION_REQUIRED: 该分析已存在多个 Scene Revision，请显式携带 analysisRevision + baseRoomRevision 提交");
        }

        String operator = SecurityOperatorContext.currentUsername();
        FloorPlanSceneRevision revision = sceneRevisionService.lockRevision(analysisId, 1);
        if (revision == null) {
            // 防御：V20 回填前创建且未完成解析的记录，补建 Revision 1
            revision = new FloorPlanSceneRevision();
            revision.setAnalysisId(analysisId);
            revision.setAnalysisRevision(1);
            revision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_LEGACY_IMPORT);
            revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY);
            revision.setRoomRevision(1);
            revision.setFurnitureRevision(1);
            revision.setCreatedAt(LocalDateTime.now());
            sceneRevisionMapper.insert(revision);
        }
        if (!FloorPlanSceneRevisionService.EDITABLE_STATUSES.contains(revision.getRevisionStatus())
            && !FloorPlanSceneRevision.STATUS_ACTIVE.equals(revision.getRevisionStatus())) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "Revision 状态 " + revision.getRevisionStatus() + " 不允许编辑");
        }

        List<FloorPlanConfirmRequest.RoomItem> items = request.getRooms() != null
            ? request.getRooms() : List.of();
        replaceRevisionRooms(analysis, revision, items, operator, true);

        // V14：传了 projectId 则校验项目可见性后覆盖写入项目归属（补挂/改挂），并记审计日志
        if (StringUtils.hasText(request.getProjectId())) {
            String newProjectId = resolveAccessibleProjectId(request.getProjectId());
            if (!newProjectId.equals(analysis.getProjectId())) {
                String oldProjectId = analysis.getProjectId();
                analysis.setProjectId(newProjectId);
                auditLogService.logAction("floor_plan_analysis", analysisId, "LINK_PROJECT",
                    oldProjectId != null ? Map.of("projectId", oldProjectId) : null,
                    Map.of("projectId", newProjectId),
                    SecurityOperatorContext.currentUsername());
                log.info("户型图分析已挂靠项目，analysisId={}，projectId={} → {}",
                    analysisId, oldProjectId, newProjectId);
            }
        }

        // 旧可观察语义：status=confirmed + confirmed_rooms 快照 + 审计；同事务隐式激活 Revision 1
        LocalDateTime now = LocalDateTime.now();
        revision.setRoomRevision(2);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_ACTIVE);
        revision.setConfirmedAt(now);
        revision.setActivatedAt(now);
        revision.setActivatedBy(operator);
        sceneRevisionMapper.updateByRevision(revision);

        analysis.setStatus(STATUS_CONFIRMED);
        analysis.setConfirmedRooms(toJson(items));
        if (request.getScaleRatio() != null) {
            analysis.setScaleRatio(request.getScaleRatio());
        }
        analysis.setActiveAnalysisRevision(1);
        analysis.setWorkingAnalysisRevision(null);
        analysis.setLatestAnalysisRevision(1);
        analysis.setUpdatedAt(now);
        updateAnalysis(analysis);
        auditLogService.logUpdate("floor_plan_analysis", analysisId, null, analysis,
            SecurityOperatorContext.currentUsername());

        return assembleRevisionResponse(analysis, revision);
    }

    /**
     * 分析记录更新（BUG-2 修复）：MyBatis-Plus updateById 与 update(entity, wrapper)
     * 的实体 SET 都跳过 null 字段——working 指针需要清空时必须经 Wrapper 显式
     * {@code set(..., null)} 才会落库；其余字段仍走实体（含 JSONB TypeHandler）。
     *
     * @param analysis 已就地完成字段修改的分析实体
     */
    private void updateAnalysis(FloorPlanAnalysis analysis) {
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FloorPlanAnalysis> wrapper =
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FloorPlanAnalysis>()
                .eq("analysis_id", analysis.getAnalysisId());
        if (analysis.getWorkingAnalysisRevision() == null) {
            wrapper.set("working_analysis_revision", null);
        }
        analysisMapper.update(analysis, wrapper);
    }

    /**
     * 整体替换某 Revision 的房间集合（V20，§8.4.1：所有读写带 analysis_id + analysis_revision
     * 双条件）：带 roomId 就地更新、不带 roomId 新增、未提交的已识别空间软删。
     * 视觉识别空间按人工提交的矩形尺寸重算并标记 manual/high；CAD 空间只允许校正名称、
     * 类型和顺序，精确多边形、面积、包围盒及 CAD 尺寸元数据保持不变。
     * 提交即视为人工确认：在列房间 confirmation_status=CONFIRMED，并记录 ADD/DELETE/UPDATE Diff。
     *
     * @param analysis      分析记录
     * @param revision      目标 Revision（已锁）
     * @param items         提交的空间列表
     * @param operator      操作人
     * @param recordDiff    是否记录 Diff 反馈
     */
    private void replaceRevisionRooms(FloorPlanAnalysis analysis, FloorPlanSceneRevision revision,
                                      List<FloorPlanConfirmRequest.RoomItem> items,
                                      String operator, boolean recordDiff) {
        String analysisId = analysis.getAnalysisId();
        Integer revisionNo = revision.getAnalysisRevision();
        List<FloorPlanRoom> existingRooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .eq("analysis_revision", revisionNo));
        Map<String, FloorPlanRoom> existingMap = existingRooms.stream()
            .collect(Collectors.toMap(FloorPlanRoom::getRoomId, Function.identity()));

        Set<String> submittedIds = new HashSet<>();
        for (FloorPlanConfirmRequest.RoomItem item : items) {
            if (StringUtils.hasText(item.getRoomId())) {
                if (!existingMap.containsKey(item.getRoomId())) {
                    throw new BusinessException("空间不存在或不属于该分析 Revision: " + item.getRoomId());
                }
                submittedIds.add(item.getRoomId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 未提交的已识别空间视为误识别，软删（MP 逻辑删除：deleteById 生成
        // SET deleted_at=now() WHERE deleted_at IS NULL；setDeletedAt+updateById 不落库）
        for (FloorPlanRoom room : existingRooms) {
            if (!submittedIds.contains(room.getRoomId())) {
                room.setDeletedAt(now);
                roomMapper.deleteById(room.getRoomId());
                if (recordDiff) {
                    sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                        com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_ROOM, room.getRoomId(),
                        com.rsdp.entity.CadAnalysisFeedback.TYPE_DELETE,
                        Map.of("label", room.getLabel() != null ? room.getLabel() : "",
                            "roomType", room.getRoomType()), operator);
                }
            }
        }

        int sort = 0;
        for (FloorPlanConfirmRequest.RoomItem item : items) {
            String roomType = normalizeRoomType(item.getRoomType());
            BigDecimal areaM2 = computeAreaM2(item.getWidthMm(), item.getDepthMm());
            String bboxJson = toJson(item.getBbox());
            if (StringUtils.hasText(item.getRoomId())) {
                FloorPlanRoom room = existingMap.get(item.getRoomId());
                boolean changed = false;
                if (!roomType.equals(room.getRoomType())) {
                    changed = true;
                }
                if (StringUtils.hasText(item.getLabel()) && !item.getLabel().trim().equals(room.getLabel())) {
                    changed = true;
                }
                room.setRoomType(roomType);
                if (StringUtils.hasText(item.getLabel())) {
                    room.setLabel(item.getLabel().trim());
                }
                if (!GEOMETRY_SOURCE_CAD.equals(room.getGeometrySource())) {
                    if (!java.util.Objects.equals(room.getWidthMm(), item.getWidthMm())
                        || !java.util.Objects.equals(room.getDepthMm(), item.getDepthMm())) {
                        changed = true;
                    }
                    room.setWidthMm(item.getWidthMm());
                    room.setDepthMm(item.getDepthMm());
                    room.setAreaM2(areaM2);
                    room.setBbox(bboxJson);
                    room.setDimensionSource(DIM_SOURCE_MANUAL);
                    room.setDimensionConfidence(CONFIDENCE_HIGH);
                }
                room.setSortOrder(sort);
                room.setConfirmationStatus("CONFIRMED");
                room.setUpdatedAt(now);
                roomMapper.updateById(room);
                if (recordDiff && changed) {
                    sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                        com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_ROOM, room.getRoomId(),
                        com.rsdp.entity.CadAnalysisFeedback.TYPE_UPDATE,
                        Map.of("roomType", roomType,
                            "label", room.getLabel() != null ? room.getLabel() : "",
                            "widthMm", room.getWidthMm() != null ? room.getWidthMm() : 0,
                            "depthMm", room.getDepthMm() != null ? room.getDepthMm() : 0), operator);
                }
            } else {
                FloorPlanRoom room = new FloorPlanRoom();
                room.setRoomId(IdGenerator.floorPlanRoomId());
                room.setAnalysisId(analysisId);
                room.setAnalysisRevision(revisionNo);
                room.setRoomType(roomType);
                if (StringUtils.hasText(item.getLabel())) {
                    room.setLabel(item.getLabel().trim());
                }
                room.setWidthMm(item.getWidthMm());
                room.setDepthMm(item.getDepthMm());
                room.setAreaM2(areaM2);
                room.setBbox(bboxJson);
                room.setDimensionSource(DIM_SOURCE_MANUAL);
                room.setDimensionConfidence(CONFIDENCE_HIGH);
                room.setConfirmationStatus("CONFIRMED");
                room.setSortOrder(sort);
                room.setCreatedAt(now);
                room.setUpdatedAt(now);
                roomMapper.insert(room);
                if (recordDiff) {
                    sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                        com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_ROOM, room.getRoomId(),
                        com.rsdp.entity.CadAnalysisFeedback.TYPE_ADD,
                        Map.of("roomType", roomType,
                            "label", room.getLabel() != null ? room.getLabel() : ""), operator);
                }
            }
            sort++;
        }
    }

    /**
     * Revision-aware 家具编辑（V20，设计文档 V1.2.3 §18.6，PUT /{analysisId}/furniture）。
     *
     * <p>写保护/版本校验/幂等与 rooms 同构（furniture_submit_base/hash）。
     * 整体替换该 Revision 家具列表：已有 ID 仅可更新人工字段（category/semanticLabel/
     * roomId/position/rotationDeg/footprintPolygon/effective 尺寸/heightMm/sortOrder；自动快照
     * 字段不可变），未变更 → CONFIRMED、有变更 → MANUAL_EDITED；缺席 ID 软删（DELETE Diff）；
     * 无 ID 新增（source_kind=MANUAL、服务端发号、MANUAL_ADDED；可带 derivedFromIds 记
     * SPLIT/MERGE Diff）。<b>不</b>改 active 指针、<b>不</b>置 confirmed（Invariant 6/9）。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    家具列表 + analysisRevision + baseFurnitureRevision
     * @return {analysisRevision, furnitureRevision, furniture[]}
     */
    @Transactional
    public FloorPlanFurnitureEditResponse editRevisionFurniture(String analysisId,
                                                                FloorPlanFurnitureConfirmRequest request) {
        // ① 锁 analysis
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        if (!STATUS_AWAITING_CONFIRM.equals(analysis.getStatus())
            && !STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("当前状态不允许家具校正（仅识别完成待确认后可提交），当前状态: " + analysis.getStatus());
        }
        // ② 锁目标 revision + 工作指针/状态校验
        Integer revisionNo = request.getAnalysisRevision();
        FloorPlanSceneRevision revision = sceneRevisionService.lockRevision(analysisId, revisionNo);
        if (revision == null) {
            throw new ResourceNotFoundException("Scene Revision 不存在或不属于该分析: " + revisionNo);
        }
        if (!FloorPlanSceneRevisionService.revisionEquals(analysis.getWorkingAnalysisRevision(), revisionNo)) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "目标 Revision 不是当前工作 Revision（working=" + analysis.getWorkingAnalysisRevision() + "）");
        }
        if (!FloorPlanSceneRevisionService.EDITABLE_STATUSES.contains(revision.getRevisionStatus())) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "Revision 状态 " + revision.getRevisionStatus() + " 不允许编辑");
        }

        String operator = SecurityOperatorContext.currentUsername();
        String submitHash = sceneRevisionService.submitHash(request.getFurniture());
        // ③ 幂等重放
        if (FloorPlanSceneRevisionService.revisionEquals(revision.getFurnitureSubmitBase(),
            request.getBaseFurnitureRevision())
            && submitHash != null && submitHash.equals(revision.getFurnitureSubmitHash())) {
            log.info("furniture 提交幂等重放，analysisId={}，revision={}，base={}",
                analysisId, revisionNo, request.getBaseFurnitureRevision());
            return buildFurnitureEditResponse(revision);
        }
        // ④ 乐观版本校验
        if (!FloorPlanSceneRevisionService.revisionEquals(revision.getFurnitureRevision(),
            request.getBaseFurnitureRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "baseFurnitureRevision=" + request.getBaseFurnitureRevision()
                    + " 与当前 furnitureRevision=" + revision.getFurnitureRevision() + " 不一致，请刷新后重试");
        }

        List<FloorPlanFurnitureConfirmRequest.FurnitureItem> items = request.getFurniture() != null
            ? request.getFurniture() : List.of();
        for (FloorPlanFurnitureConfirmRequest.FurnitureItem item : items) {
            validateFurnitureItem(item);
        }

        List<FloorPlanFurniture> existing = sceneRevisionService.listFurniture(analysisId, revisionNo);
        Map<String, FloorPlanFurniture> existingMap = existing.stream()
            .collect(Collectors.toMap(FloorPlanFurniture::getFurnitureId, Function.identity()));

        Set<String> submittedIds = new HashSet<>();
        for (FloorPlanFurnitureConfirmRequest.FurnitureItem item : items) {
            if (StringUtils.hasText(item.getFurnitureId())) {
                if (!existingMap.containsKey(item.getFurnitureId())) {
                    throw new BusinessException("家具不存在或不属于该分析 Revision: " + item.getFurnitureId());
                }
                submittedIds.add(item.getFurnitureId());
            }
            if (StringUtils.hasText(item.getRoomId())) {
                FloorPlanRoom room = roomMapper.selectById(item.getRoomId());
                if (room == null || !analysisId.equals(room.getAnalysisId())
                    || !FloorPlanSceneRevisionService.revisionEquals(room.getAnalysisRevision(), revisionNo)) {
                    throw new BusinessException("归属房间不存在或不属于该分析 Revision: " + item.getRoomId());
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 缺席 ID 软删（MP 逻辑删除：deleteById 生成 SET deleted_at=now()
        // WHERE deleted_at IS NULL；setDeletedAt+updateById 不落库）+ DELETE Diff
        for (FloorPlanFurniture furniture : existing) {
            if (!submittedIds.contains(furniture.getFurnitureId())) {
                furniture.setDeletedAt(now);
                furniture.setUpdatedAt(now);
                furnitureMapper.deleteById(furniture.getFurnitureId());
                sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                    com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_FURNITURE, furniture.getFurnitureId(),
                    com.rsdp.entity.CadAnalysisFeedback.TYPE_DELETE,
                    Map.of("category", furniture.getCategory() != null ? furniture.getCategory() : "",
                        "semanticLabel", furniture.getSemanticLabel() != null ? furniture.getSemanticLabel() : ""),
                    operator);
            }
        }

        // SPLIT 检测：同一来源 ID 被多个新对象引用
        Map<String, List<String>> sourceToTargets = new HashMap<>();
        for (FloorPlanFurnitureConfirmRequest.FurnitureItem item : items) {
            if (item.getDerivedFromIds() != null) {
                for (String sourceId : item.getDerivedFromIds()) {
                    sourceToTargets.computeIfAbsent(sourceId, k -> new ArrayList<>())
                        .add(StringUtils.hasText(item.getFurnitureId()) ? item.getFurnitureId() : "(new)");
                }
            }
        }

        int sort = 0;
        for (FloorPlanFurnitureConfirmRequest.FurnitureItem item : items) {
            if (StringUtils.hasText(item.getFurnitureId())) {
                FloorPlanFurniture furniture = existingMap.get(item.getFurnitureId());
                boolean changed = applyFurnitureItem(furniture, item);
                furniture.setSortOrder(sort);
                if (changed) {
                    furniture.setConfirmationStatus(FloorPlanFurniture.CONFIRM_MANUAL_EDITED);
                    sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                        com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_FURNITURE, furniture.getFurnitureId(),
                        com.rsdp.entity.CadAnalysisFeedback.TYPE_UPDATE,
                        Map.of("category", furniture.getCategory() != null ? furniture.getCategory() : "",
                            "semanticLabel",
                            furniture.getSemanticLabel() != null ? furniture.getSemanticLabel() : ""),
                        operator);
                } else if (FloorPlanFurniture.CONFIRM_AUTO.equals(furniture.getConfirmationStatus())) {
                    furniture.setConfirmationStatus(FloorPlanFurniture.CONFIRM_CONFIRMED);
                }
                if (item.getDerivedFromIds() != null && !item.getDerivedFromIds().isEmpty()) {
                    furniture.setDerivedFromIds(toJson(item.getDerivedFromIds()));
                }
                furniture.setUpdatedAt(now);
                furnitureMapper.updateById(furniture);
            } else {
                FloorPlanFurniture furniture = new FloorPlanFurniture();
                furniture.setFurnitureId(IdGenerator.floorPlanFurnitureId());
                furniture.setAnalysisId(analysisId);
                furniture.setAnalysisRevision(revisionNo);
                furniture.setSourceKind(FloorPlanFurniture.SOURCE_KIND_MANUAL);
                applyFurnitureItem(furniture, item);
                furniture.setSortOrder(sort);
                furniture.setConfirmationStatus(FloorPlanFurniture.CONFIRM_MANUAL_ADDED);
                if (item.getDerivedFromIds() != null && !item.getDerivedFromIds().isEmpty()) {
                    furniture.setDerivedFromIds(toJson(item.getDerivedFromIds()));
                }
                furniture.setCreatedBy(operator);
                furniture.setCreatedAt(now);
                furniture.setUpdatedAt(now);
                furnitureMapper.insert(furniture);
                sceneRevisionService.recordFeedback(analysisId, revisionNo, revision.getParserVersion(),
                    com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_FURNITURE, furniture.getFurnitureId(),
                    com.rsdp.entity.CadAnalysisFeedback.TYPE_ADD,
                    Map.of("category", furniture.getCategory() != null ? furniture.getCategory() : "",
                        "semanticLabel",
                        furniture.getSemanticLabel() != null ? furniture.getSemanticLabel() : ""),
                    operator);
                recordSplitMergeFeedback(analysisId, revisionNo, revision.getParserVersion(),
                    furniture.getFurnitureId(), item.getDerivedFromIds(), sourceToTargets, operator);
            }
            sort++;
        }

        // 已有对象携带 derivedFromIds 的 SPLIT/MERGE Diff
        for (FloorPlanFurnitureConfirmRequest.FurnitureItem item : items) {
            if (StringUtils.hasText(item.getFurnitureId())
                && item.getDerivedFromIds() != null && !item.getDerivedFromIds().isEmpty()) {
                recordSplitMergeFeedback(analysisId, revisionNo, revision.getParserVersion(),
                    item.getFurnitureId(), item.getDerivedFromIds(), sourceToTargets, operator);
            }
        }

        revision.setFurnitureRevision(revision.getFurnitureRevision() + 1);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_EDITING);
        revision.setFurnitureSubmitBase(request.getBaseFurnitureRevision());
        revision.setFurnitureSubmitHash(submitHash);
        sceneRevisionMapper.updateByRevision(revision);
        sceneRevisionService.applyReadinessTransition(revision);

        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);
        return buildFurnitureEditResponse(sceneRevisionService.getRevision(analysisId, revisionNo));
    }

    /**
     * 把请求项的人工可编辑字段应用到家具实体；返回是否有实质变更（自动快照字段不在此列）。
     *
     * <p>变更为「规范化值比较」：rotationDeg 用 compareTo（90.0 与 90.00 相等）、
     * position/footprintPolygon 解析为数值结构后比较（忽略 JSON 文本格式与整数/小数写法）、
     * 空串归一为 null；字段为 null 表示「保持现状」，显式空串表示清除。
     * 避免格式差异把未变更项误判为 MANUAL_EDITED（P2-9）。</p>
     */
    private boolean applyFurnitureItem(FloorPlanFurniture furniture,
                                       FloorPlanFurnitureConfirmRequest.FurnitureItem item) {
        boolean changed = false;
        String category = StringUtils.hasText(item.getCategory()) ? item.getCategory().trim().toUpperCase() : null;
        if (category != null && !category.equals(furniture.getCategory())) {
            furniture.setCategory(category);
            changed = true;
        }
        if (item.getSemanticLabel() != null) {
            String submitted = StringUtils.hasText(item.getSemanticLabel())
                ? item.getSemanticLabel().trim() : null;
            if (!java.util.Objects.equals(submitted, furniture.getSemanticLabel())) {
                furniture.setSemanticLabel(submitted);
                changed = true;
            }
        }
        if (item.getRoomId() != null) {
            String submittedRoomId = StringUtils.hasText(item.getRoomId()) ? item.getRoomId().trim() : null;
            if (!java.util.Objects.equals(submittedRoomId, furniture.getRoomId())) {
                furniture.setRoomId(submittedRoomId);
                changed = true;
            }
        }
        if (item.getPosition() != null) {
            FloorPlanPoint current = parsePoint(furniture.getPosition());
            boolean same = current != null && current.getX() != null && current.getY() != null
                && item.getPosition().getX() != null && item.getPosition().getY() != null
                && Double.compare(current.getX(), item.getPosition().getX()) == 0
                && Double.compare(current.getY(), item.getPosition().getY()) == 0;
            if (!same) {
                furniture.setPosition(toJson(Map.of("x", item.getPosition().getX(),
                    "y", item.getPosition().getY())));
                changed = true;
            }
        }
        if (item.getRotationDeg() != null
            && (furniture.getRotationDeg() == null
                || item.getRotationDeg().compareTo(furniture.getRotationDeg()) != 0)) {
            furniture.setRotationDeg(item.getRotationDeg());
            changed = true;
        }
        if (item.getFootprintPolygon() != null
            && !item.getFootprintPolygon().equals(parsePolygon(furniture.getFootprintPolygon()))) {
            furniture.setFootprintPolygon(toJson(item.getFootprintPolygon()));
            changed = true;
        }
        if (item.getEffectiveWidthMm() != null
            && !item.getEffectiveWidthMm().equals(furniture.getEffectiveWidthMm())) {
            furniture.setEffectiveWidthMm(item.getEffectiveWidthMm());
            changed = true;
        }
        if (item.getEffectiveDepthMm() != null
            && !item.getEffectiveDepthMm().equals(furniture.getEffectiveDepthMm())) {
            furniture.setEffectiveDepthMm(item.getEffectiveDepthMm());
            changed = true;
        }
        if (item.getHeightMm() != null && !item.getHeightMm().equals(furniture.getHeightMm())) {
            furniture.setHeightMm(item.getHeightMm());
            changed = true;
        }
        return changed;
    }

    /** 家具提交项契约校验：枚举合法、坐标有限、轮廓闭合、尺寸非负。 */
    private void validateFurnitureItem(FloorPlanFurnitureConfirmRequest.FurnitureItem item) {
        if (StringUtils.hasText(item.getCategory())) {
            String category = item.getCategory().trim().toUpperCase();
            if (!Set.of("SOFA", "BED", "TABLE", "CHAIR", "CABINET", "OTHER", "UNKNOWN").contains(category)) {
                throw new BusinessException("家具类别非法: " + item.getCategory());
            }
        }
        if (item.getPosition() != null) {
            validateFiniteCoordinate(item.getPosition().getX(), "position.x");
            validateFiniteCoordinate(item.getPosition().getY(), "position.y");
        }
        if (item.getRotationDeg() != null && item.getRotationDeg().scale() > 2) {
            throw new BusinessException("rotationDeg 最多保留 2 位小数");
        }
        if (item.getFootprintPolygon() != null && !item.getFootprintPolygon().isEmpty()) {
            List<List<Double>> polygon = item.getFootprintPolygon();
            for (List<Double> point : polygon) {
                if (point == null || point.size() < 2) {
                    throw new BusinessException("footprintPolygon 顶点坐标不完整");
                }
                validateFiniteCoordinate(point.get(0), "footprintPolygon");
                validateFiniteCoordinate(point.get(1), "footprintPolygon");
            }
            if (polygon.size() >= 3) {
                List<Double> first = polygon.get(0);
                List<Double> last = polygon.get(polygon.size() - 1);
                if (!first.get(0).equals(last.get(0)) || !first.get(1).equals(last.get(1))) {
                    throw new BusinessException("footprintPolygon 必须为闭合多边形（首尾顶点一致）");
                }
            }
        }
        validateNonNegativeInt(item.getEffectiveWidthMm(), "effectiveWidthMm");
        validateNonNegativeInt(item.getEffectiveDepthMm(), "effectiveDepthMm");
        validateNonNegativeInt(item.getHeightMm(), "heightMm");
    }

    private void validateFiniteCoordinate(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw new BusinessException(field + " 必须为有限数值");
        }
    }

    private void validateNonNegativeInt(Integer value, String field) {
        if (value != null && value < 0) {
            throw new BusinessException(field + " 不能为负数");
        }
    }

    /** SPLIT/MERGE Diff：derivedFromIds 多于 1 个来源 → MERGE；来源被多个对象引用 → SPLIT。 */
    private void recordSplitMergeFeedback(String analysisId, Integer revisionNo, String parserVersion,
                                          String targetId, List<String> derivedFromIds,
                                          Map<String, List<String>> sourceToTargets, String operator) {
        if (derivedFromIds == null || derivedFromIds.isEmpty()) {
            return;
        }
        if (derivedFromIds.size() > 1) {
            sceneRevisionService.recordFeedback(analysisId, revisionNo, parserVersion,
                com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_FURNITURE, targetId,
                com.rsdp.entity.CadAnalysisFeedback.TYPE_MERGE,
                Map.of("sourceIds", derivedFromIds, "targetId", targetId), operator);
        }
        for (String sourceId : derivedFromIds) {
            List<String> targets = sourceToTargets.getOrDefault(sourceId, List.of());
            if (targets.size() > 1) {
                sceneRevisionService.recordFeedback(analysisId, revisionNo, parserVersion,
                    com.rsdp.entity.CadAnalysisFeedback.OBJECT_TYPE_FURNITURE, sourceId,
                    com.rsdp.entity.CadAnalysisFeedback.TYPE_SPLIT,
                    Map.of("sourceId", sourceId, "targetIds", targets), operator);
            }
        }
    }

    /** 家具编辑响应装配（幂等重放与正常提交共用；revisionStatus/readiness 按当前保存后状态实时计算）。 */
    private FloorPlanFurnitureEditResponse buildFurnitureEditResponse(FloorPlanSceneRevision revision) {
        FloorPlanFurnitureEditResponse response = new FloorPlanFurnitureEditResponse();
        response.setAnalysisRevision(revision.getAnalysisRevision());
        response.setFurnitureRevision(revision.getFurnitureRevision());
        response.setRevisionStatus(revision.getRevisionStatus());
        response.setActivationReadiness(sceneRevisionService.evaluateReadiness(revision));
        List<FloorPlanFurniture> furniture = sceneRevisionService.listFurniture(
            revision.getAnalysisId(), revision.getAnalysisRevision());
        response.setFurniture(furniture.stream().map(this::toFurnitureResponse).toList());
        return response;
    }

    /**
     * Scene 原子激活（V20，设计文档 V1.2.3 §8.5）：显式、整版、单事务。
     *
     * <p>步骤：①锁 analysis ②锁目标 revision ③校验 baseActive==active_analysis_revision
     * ④目标是 working 且 READY_TO_ACTIVATE 且无运行中解析任务 ⑤expected* 版本一致
     * （任一不符 409）⑥Scene Activation Validator（失败 409 ACTIVATION_VALIDATION_FAILED）
     * ⑦旧 ACTIVE → SUPERSEDED ⑧目标 → ACTIVE ⑨active 指针切换 ⑩working=NULL
     * ⑪analysis.status=confirmed + confirmed_rooms 快照（从目标 Revision 房间生成）
     * + preview/quality 投影同步 ⑫审计。任何失败整体回滚。</p>
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo 目标 Revision 序号
     * @param request    激活请求（baseActive/expected 版本）
     * @return 激活后的分析详情
     */
    @Transactional
    public FloorPlanAnalysisResponse activateRevision(String analysisId, Integer revisionNo,
                                                      FloorPlanActivateRequest request) {
        // ① 锁 analysis
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        // ② 锁目标 revision
        FloorPlanSceneRevision revision = sceneRevisionService.lockRevision(analysisId, revisionNo);
        if (revision == null) {
            throw new ResourceNotFoundException("Scene Revision 不存在或不属于该分析: " + revisionNo);
        }
        // ③ baseActive 校验（null 安全：此前无激活版本时请求须显式传 null）
        if (!FloorPlanSceneRevisionService.revisionEquals(analysis.getActiveAnalysisRevision(),
            request.getBaseActiveAnalysisRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "baseActiveAnalysisRevision=" + request.getBaseActiveAnalysisRevision()
                    + " 与当前 activeAnalysisRevision=" + analysis.getActiveAnalysisRevision() + " 不一致");
        }
        // ④ 工作指针 + 状态 + 运行中任务校验
        if (!FloorPlanSceneRevisionService.revisionEquals(analysis.getWorkingAnalysisRevision(), revisionNo)) {
            throw FloorPlanSceneRevisionService.revisionNotEditable(
                "目标 Revision 不是当前工作 Revision（working=" + analysis.getWorkingAnalysisRevision() + "）");
        }
        if (!FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE.equals(revision.getRevisionStatus())) {
            throw new BusinessException(409, "REVISION_NOT_ACTIVATABLE: Revision 状态 "
                + revision.getRevisionStatus() + " 不允许激活（须 READY_TO_ACTIVATE）");
        }
        if (sceneRevisionService.hasRunningTask(analysis.getTaskId())) {
            throw new BusinessException(409, "ANALYSIS_TASK_RUNNING: 存在仍在执行的解析任务，不允许激活");
        }
        // ⑤ expected 版本校验
        if (!FloorPlanSceneRevisionService.revisionEquals(revision.getRoomRevision(),
            request.getExpectedRoomRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "expectedRoomRevision=" + request.getExpectedRoomRevision()
                    + " 与当前 roomRevision=" + revision.getRoomRevision() + " 不一致");
        }
        if (!FloorPlanSceneRevisionService.revisionEquals(revision.getFurnitureRevision(),
            request.getExpectedFurnitureRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "expectedFurnitureRevision=" + request.getExpectedFurnitureRevision()
                    + " 与当前 furnitureRevision=" + revision.getFurnitureRevision() + " 不一致");
        }
        // ⑥ Scene Activation Validator（事务内再次运行，不信任此前状态。
        //    O-N1：编辑后 readiness 即时复评而非先回落 EDITING——安全性等价，
        //    因为最终放行只以本次事务内的 Validator 结果为准）
        FloorPlanActivationReadiness readiness = sceneRevisionService.evaluateReadiness(revision);
        if (!readiness.isReady()) {
            throw new BusinessException(409,
                "ACTIVATION_VALIDATION_FAILED: " + String.join("；", readiness.getIssues()));
        }

        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();
        // ⑦ 旧 ACTIVE → SUPERSEDED（历史保留可审计，Invariant 8/11）
        Integer oldActive = analysis.getActiveAnalysisRevision();
        if (oldActive != null && !oldActive.equals(revisionNo)) {
            FloorPlanSceneRevision oldRevision = sceneRevisionService.lockRevision(analysisId, oldActive);
            if (oldRevision != null
                && FloorPlanSceneRevision.STATUS_ACTIVE.equals(oldRevision.getRevisionStatus())) {
                oldRevision.setRevisionStatus(FloorPlanSceneRevision.STATUS_SUPERSEDED);
                sceneRevisionMapper.updateByRevision(oldRevision);
            }
        }
        // ⑧ 目标 → ACTIVE
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_ACTIVE);
        revision.setConfirmedAt(now);
        revision.setActivatedAt(now);
        revision.setActivatedBy(operator);
        sceneRevisionMapper.updateByRevision(revision);

        // ⑨⑩⑪ 指针切换 + 整版 confirmed + confirmed_rooms 快照（保持旧字段可用）+ 投影同步
        List<FloorPlanRoom> rooms = sceneRevisionService.listRooms(analysisId, revisionNo);
        analysis.setActiveAnalysisRevision(revisionNo);
        analysis.setWorkingAnalysisRevision(null);
        analysis.setStatus(STATUS_CONFIRMED);
        analysis.setConfirmedRooms(toJson(rooms.stream().map(this::toConfirmedRoomSnapshot).toList()));
        if (StringUtils.hasText(revision.getPreviewImageId())) {
            analysis.setPreviewImageId(revision.getPreviewImageId());
        }
        if (StringUtils.hasText(revision.getQualityIssues())) {
            analysis.setQualityIssues(revision.getQualityIssues());
        }
        analysis.setUpdatedAt(now);
        updateAnalysis(analysis);
        // ⑫ 审计
        auditLogService.logAction("floor_plan_analysis", analysisId, "ACTIVATE_SCENE_REVISION",
            oldActive != null ? Map.of("activeAnalysisRevision", oldActive) : null,
            Map.of("activeAnalysisRevision", revisionNo), operator);
        log.info("Scene Revision 已激活，analysisId={}，revision={}（旧 active={}）",
            analysisId, revisionNo, oldActive);
        return assembleRevisionResponse(analysis, revision);
    }

    /** confirmed_rooms 快照元素（与旧确认提交结构兼容：roomId/roomType/label/尺寸/bbox）。 */
    private Map<String, Object> toConfirmedRoomSnapshot(FloorPlanRoom room) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("roomId", room.getRoomId());
        snapshot.put("roomType", room.getRoomType());
        snapshot.put("label", room.getLabel());
        snapshot.put("widthMm", room.getWidthMm());
        snapshot.put("depthMm", room.getDepthMm());
        snapshot.put("bbox", parseBBox(room.getBbox()));
        return snapshot;
    }

    /**
     * 派生工作 Revision（V20，POST /{analysisId}/working-revision）：从 active（无 active 则
     * latest）复制已确认/未软删 Room+Furniture 对象（新行新对象 ID，家具以
     * matched_from_object_id 记录来源）+ 复制自动快照/预览/质量问题引用。
     * 已有工作 Revision 时 409 WORKING_REVISION_EXISTS。
     *
     * @param analysisId 分析批次 ID
     * @return {analysisId, analysisRevision, revisionStatus}
     */
    @Transactional
    public Map<String, Object> createWorkingRevision(String analysisId) {
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        if (analysis.getWorkingAnalysisRevision() != null) {
            throw new BusinessException(409, "WORKING_REVISION_EXISTS: 已存在工作 Revision "
                + analysis.getWorkingAnalysisRevision() + "，请先激活或继续编辑");
        }
        Integer sourceNo = analysis.getActiveAnalysisRevision() != null
            ? analysis.getActiveAnalysisRevision() : analysis.getLatestAnalysisRevision();
        if (sourceNo == null) {
            throw new BusinessException("当前分析没有可派生的 Scene Revision");
        }
        FloorPlanSceneRevision source = sceneRevisionService.lockRevision(analysisId, sourceNo);
        if (source == null) {
            throw new ResourceNotFoundException("Scene Revision 不存在或不属于该分析: " + sourceNo);
        }
        String operator = SecurityOperatorContext.currentUsername();
        FloorPlanSceneRevision derived = sceneRevisionService.deriveWorkingRevision(
            analysis, source, operator);
        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);
        auditLogService.logAction("floor_plan_analysis", analysisId, "DERIVE_WORKING_REVISION",
            Map.of("sourceAnalysisRevision", sourceNo),
            Map.of("workingAnalysisRevision", derived.getAnalysisRevision()), operator);
        Map<String, Object> result = new HashMap<>();
        result.put("analysisId", analysisId);
        result.put("analysisRevision", derived.getAnalysisRevision());
        result.put("revisionStatus", derived.getRevisionStatus());
        return result;
    }

    /**
     * Target Scene 切换（V20，设计文档 V1.2.3 §8.2，PUT /{analysisId}/target-scene）：
     * 不重新上传 DWG，从 cad_object_key 读取原文件重跑 Parser，产生新 Analysis Revision
     * （latest+1，PROCESSING，trigger=TARGET_SCENE_SWITCH，parent=base）；旧 Revision 不删不改。
     * 异步执行，HTTP 202。
     *
     * @param analysisId 分析批次 ID
     * @param request    {baseAnalysisRevision, targetId}
     * @return {analysisId, analysisRevision, taskId, status=PROCESSING}
     */
    @Transactional
    public Map<String, Object> switchTargetScene(String analysisId, FloorPlanTargetSceneRequest request) {
        FloorPlanAnalysis analysis = analysisMapper.selectByIdForUpdate(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis);
        if (!FloorPlanSceneRevisionService.revisionEquals(analysis.getLatestAnalysisRevision(),
            request.getBaseAnalysisRevision())) {
            throw FloorPlanSceneRevisionService.versionConflict(
                "baseAnalysisRevision=" + request.getBaseAnalysisRevision()
                    + " 与当前 latestAnalysisRevision=" + analysis.getLatestAnalysisRevision() + " 不一致");
        }
        if (!StringUtils.hasText(analysis.getCadObjectKey())) {
            throw new BusinessException(400, "TARGET_SCENE_UNSUPPORTED: 视觉识别通道不支持 Target Scene 切换");
        }
        FloorPlanSceneRevision baseRevision = sceneRevisionService.getRevision(
            analysisId, request.getBaseAnalysisRevision());
        if (baseRevision == null) {
            throw new ResourceNotFoundException(
                "Scene Revision 不存在或不属于该分析: " + request.getBaseAnalysisRevision());
        }
        if (!candidateContains(baseRevision.getTargetSceneCandidates(), request.getTargetId())) {
            throw new BusinessException(400,
                "TARGET_SCENE_INVALID: targetId 不属于当前 Revision 的候选列表: " + request.getTargetId());
        }

        String operator = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();
        // 先提取上一轮任务的用户补充说明（换挂新任务后旧 input_data 不可达）
        String hint = extractHintFromTask(analysis.getTaskId());
        FloorPlanSceneRevision newRevision = sceneRevisionService.createProcessingRevision(
            analysis, FloorPlanSceneRevision.TRIGGER_TARGET_SCENE_SWITCH,
            request.getBaseAnalysisRevision(), request.getTargetId(), operator);

        String newTaskId = IdGenerator.taskId();
        analysis.setWorkingAnalysisRevision(newRevision.getAnalysisRevision());
        analysis.setStatus(STATUS_ANALYZING);
        analysis.setTaskId(newTaskId);
        analysis.setErrorMessage(null);
        analysis.setUpdatedAt(now);
        analysisMapper.updateById(analysis);

        ImageAssets imageAsset = imageAssetsMapper.selectById(analysis.getImageId());
        String objectKey = imageAsset != null ? imageAsset.getStoragePath() : null;

        AsyncTask task = new AsyncTask();
        task.setTaskId(newTaskId);
        task.setTaskType(TASK_TYPE);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("analysisId", analysisId);
        inputData.put("imageId", analysis.getImageId());
        if (StringUtils.hasText(objectKey)) {
            inputData.put("objectKey", objectKey);
        }
        inputData.put("cadObjectKey", analysis.getCadObjectKey());
        inputData.put("analysisRevision", newRevision.getAnalysisRevision());
        inputData.put("targetId", request.getTargetId());
        if (StringUtils.hasText(hint)) {
            inputData.put("hint", hint);
        }
        task.setInputData(toJson(inputData));
        task.setCreatedBy(operator);
        task.setCreatedAt(now);
        asyncTaskMapper.insert(task);
        auditLogService.logAction("floor_plan_analysis", analysisId, "TARGET_SCENE_SWITCH",
            Map.of("baseAnalysisRevision", request.getBaseAnalysisRevision()),
            Map.of("analysisRevision", newRevision.getAnalysisRevision(),
                "targetId", request.getTargetId()), operator);

        triggerAsyncAnalysis(newTaskId, analysisId, objectKey, hint, analysis.getCadObjectKey(), false);
        log.info("Target Scene 切换重解析已发起，analysisId={}，revision={}，targetId={}，taskId={}",
            analysisId, newRevision.getAnalysisRevision(), request.getTargetId(), newTaskId);

        Map<String, Object> result = new HashMap<>();
        result.put("analysisId", analysisId);
        result.put("analysisRevision", newRevision.getAnalysisRevision());
        result.put("taskId", newTaskId);
        result.put("status", FloorPlanSceneRevision.STATUS_PROCESSING);
        return result;
    }

    /** targetId 是否属于 Revision 候选列表（candidates JSON 解析失败/无候选恒 false）。 */
    private boolean candidateContains(String candidatesJson, String targetId) {
        if (!StringUtils.hasText(candidatesJson) || !StringUtils.hasText(targetId)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(candidatesJson);
            if (!node.isArray()) {
                return false;
            }
            for (JsonNode candidate : node) {
                JsonNode id = candidate.get("targetId");
                if (id != null && targetId.equals(id.asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("解析 targetSceneCandidates 失败", e);
        }
        return false;
    }

    /**
     * 解析成功落库（V20 新写路径，由 {@link AsyncTaskProcessor} 调用）：
     * 房间/家具带 analysis_revision 落库 → 预览存 image_assets → Revision 快照一次性写入
     * 并冻结（READY）→ 工作指针指向本 Revision → analysis=awaiting_confirm。
     * 分析级 raw_result 列不再写入（Invariant 12）。
     *
     * @param analysisId   分析批次 ID
     * @param revisionNo   目标 Revision
     * @param cadResult    CAD 解析结果
     * @param previewBytes 规范预览 PNG 字节，可空
     * @param codeNameMode 历史代号模式标记（兼容保留）
     */
    @Transactional
    public void finalizeCadParseSuccess(String analysisId, Integer revisionNo, CadParseResult cadResult,
                                        byte[] previewBytes, boolean codeNameMode) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            log.warn("户型图分析记录不存在，analysisId={}", analysisId);
            return;
        }
        Map<String, String> parserRoomIdMap = buildCadRooms(analysisId, revisionNo, cadResult, codeNameMode);
        buildCadFurniture(analysisId, revisionNo, cadResult, parserRoomIdMap);
        String previewImageId = storeCadPreviewImage(analysis, previewBytes,
            cadResult != null ? cadResult.getPreview() : null);
        sceneRevisionService.completeCadRevision(analysis, revisionNo, cadResult, previewImageId);
        analysisMapper.updateById(analysis);
    }

    /**
     * 视觉通道解析成功落库（V20 新写路径）：房间带 analysis_revision 落库 →
     * Revision 1 快照 READY（parser_version='vision'）→ analysis=awaiting_confirm。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo 目标 Revision
     * @param detected   视觉识别结果
     */
    @Transactional
    public void finalizeVisionParseSuccess(String analysisId, Integer revisionNo,
                                           FloorPlanDetectResult detected) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            log.warn("户型图分析记录不存在，analysisId={}", analysisId);
            return;
        }
        buildRooms(analysisId, revisionNo, detected);
        sceneRevisionService.completeVisionRevision(analysis, revisionNo, detected);
        analysisMapper.updateById(analysis);
    }

    /**
     * 解析失败落库（V20 新写路径）：Revision → FAILED（保存诊断，不产生对象）。
     *
     * <p>裁决 #11（FAILED Revision 不占工作指针）：若 working 指针正指向失败 Revision
     * （target-scene 重解析场景），则回退——父 Revision 仍处于可编辑状态
     * （READY/EDITING/READY_TO_ACTIVATE）则回退到父 Revision，否则置 null；
     * 同时分析状态回退（有 active → confirmed，否则 awaiting_confirm），
     * 保证旧工作版可继续编辑。首解析/重试失败（working 未指向失败 Revision）维持
     * analysis=failed（retry 语义不变）。</p>
     *
     * @param analysisId   分析批次 ID
     * @param revisionNo   目标 Revision
     * @param errorMessage 失败原因
     */
    @Transactional
    public void finalizeParseFailure(String analysisId, Integer revisionNo, String errorMessage) {
        sceneRevisionService.markRevisionFailed(analysisId, revisionNo);
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            return;
        }
        if (FloorPlanSceneRevisionService.revisionEquals(analysis.getWorkingAnalysisRevision(), revisionNo)) {
            analysis.setWorkingAnalysisRevision(resolveWorkingFallback(analysisId, revisionNo));
            analysis.setStatus(analysis.getActiveAnalysisRevision() != null
                ? STATUS_CONFIRMED : STATUS_AWAITING_CONFIRM);
            analysis.setErrorMessage(sanitizeErrorMessage(errorMessage));
        } else {
            analysis.setStatus(STATUS_FAILED);
            analysis.setErrorMessage(sanitizeErrorMessage(errorMessage));
        }
        analysis.setUpdatedAt(LocalDateTime.now());
        updateAnalysis(analysis);
    }

    /**
     * 失败原因收敛为可读中文摘要（BUG-5）：SQL/驱动异常细节（表名/约束名/SQL 文本/参数）
     * 不透传给前端，完整堆栈只保留在服务端日志；业务异常（中文可读）原样透传（截 200 字）。
     *
     * @param errorMessage 原始异常消息
     * @return 可透传摘要
     */
    private String sanitizeErrorMessage(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return "户型图解析失败";
        }
        if (errorMessage.contains("PSQLException") || errorMessage.contains("SQLException")
            || errorMessage.contains("SQL [") || errorMessage.contains("constraint")
            || errorMessage.contains("duplicate key") || errorMessage.contains("SQLState")) {
            return "户型图解析结果落库失败，请稍后重试（详细原因已记录服务端日志）";
        }
        String message = errorMessage.replaceAll("\\s+", " ").trim();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /**
     * 工作指针回退目标（裁决 #11）：失败 Revision 的父 Revision 仍可编辑
     * （READY/EDITING/READY_TO_ACTIVATE）则回退到父 Revision，否则 null
     * （FAILED/无父版/父版 ACTIVE/SUPERSEDED 均不占工作指针）。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo 失败 Revision 序号
     * @return 回退 Revision 序号或 null
     */
    private Integer resolveWorkingFallback(String analysisId, Integer revisionNo) {
        FloorPlanSceneRevision failed = sceneRevisionService.getRevision(analysisId, revisionNo);
        Integer parentNo = failed != null ? failed.getParentAnalysisRevision() : null;
        if (parentNo == null) {
            return null;
        }
        FloorPlanSceneRevision parent = sceneRevisionService.getRevision(analysisId, parentNo);
        return parent != null
            && FloorPlanSceneRevisionService.EDITABLE_STATUSES.contains(parent.getRevisionStatus())
            ? parentNo : null;
    }

    /**
     * Revision 详情装配（编辑/激活/legacy 包装共用）：按目标 Revision 实时组装
     * （Invariant 1：一次响应只来自一个 analysisRevision）。
     *
     * @param analysis 分析记录
     * @param revision 目标 Revision
     * @return 分析详情
     */
    private FloorPlanAnalysisResponse assembleRevisionResponse(FloorPlanAnalysis analysis,
                                                               FloorPlanSceneRevision revision) {
        List<FloorPlanRoom> rooms = sceneRevisionService.listRooms(
            analysis.getAnalysisId(), revision.getAnalysisRevision());
        List<FloorPlanFurniture> furniture = sceneRevisionService.listFurniture(
            analysis.getAnalysisId(), revision.getAnalysisRevision());
        return toResponse(analysis, revision, rooms, furniture);
    }
    /**
     * 接口 5：软删分析批次（@TableLogic 模式），级联软删其下空间明细与家具对象
     * （V20：全部 Revision 的对象一并级联；Scene Revision 头表无 deleted_at，保留留档）。
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
        furnitureMapper.delete(new QueryWrapper<FloorPlanFurniture>().eq("analysis_id", analysisId));
        analysisMapper.deleteById(analysisId);
        auditLogService.logDelete("floor_plan_analysis", analysisId, analysis,
            SecurityOperatorContext.currentUsername());
        log.info("户型图分析已软删，analysisId={}", analysisId);
    }

    /**
     * 批量软删户型图分析记录。
     *
     * <p>去重后逐条在独立事务中复用 {@link #deleteAnalysis}，保留原有归属校验、
     * 空间级联软删和审计语义；单条失败不会回滚已成功的其他记录。</p>
     *
     * @param analysisIds 待删除的分析批次 ID 列表
     * @return 删除结果（成功数 + 失败明细）
     */
    public FloorPlanBatchDeleteResponse batchDeleteAnalyses(List<String> analysisIds) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<FloorPlanBatchDeleteResponse.Failure> failures = new ArrayList<>();
        int deletedCount = 0;
        LinkedHashSet<String> normalizedIds = analysisIds.stream()
            .map(analysisId -> analysisId != null ? analysisId.trim() : null)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String analysisId : normalizedIds) {
            if (!StringUtils.hasText(analysisId)) {
                failures.add(new FloorPlanBatchDeleteResponse.Failure(analysisId, "分析批次 ID 不能为空"));
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> deleteAnalysis(analysisId));
                deletedCount++;
            } catch (Exception e) {
                String reason = StringUtils.hasText(e.getMessage()) ? e.getMessage() : "删除失败";
                log.warn("批量删除户型图分析记录失败，analysisId={}，reason={}", analysisId, reason);
                failures.add(new FloorPlanBatchDeleteResponse.Failure(analysisId, reason));
            }
        }
        return new FloorPlanBatchDeleteResponse(deletedCount, failures.size(), failures);
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
        // 双文件通道（CAD 户型导入增强）：从上一轮任务 input_data 提取 CAD 对象键与代号模式标记，重试沿用
        String cadObjectKey = extractTaskInputField(analysis.getTaskId(), "cadObjectKey");
        boolean codeNameMode = Boolean.parseBoolean(extractTaskInputField(analysis.getTaskId(), "codeNameMode"));

        LocalDateTime now = LocalDateTime.now();
        String operator = SecurityOperatorContext.currentUsername();
        // V20：重试产生新 Revision（latest+1，REPARSE）；旧 Revision（含残留明细）不删不改（Invariant 11）
        FloorPlanSceneRevision reparseRevision = sceneRevisionService.createProcessingRevision(
            analysis, FloorPlanSceneRevision.TRIGGER_REPARSE,
            analysis.getLatestAnalysisRevision(), null, operator);

        String newTaskId = IdGenerator.taskId();
        analysis.setStatus(STATUS_PENDING);
        analysis.setErrorMessage(null);
        analysis.setTaskId(newTaskId);
        analysis.setWorkingAnalysisRevision(null);
        analysis.setUpdatedAt(now);
        updateAnalysis(analysis);
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
        inputData.put("analysisRevision", reparseRevision.getAnalysisRevision());
        if (StringUtils.hasText(cadObjectKey)) {
            inputData.put("cadObjectKey", cadObjectKey);
            inputData.put("codeNameMode", codeNameMode);
        }
        if (StringUtils.hasText(hint)) {
            inputData.put("hint", hint);
        }
        task.setInputData(toJson(inputData));
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(now);
        asyncTaskMapper.insert(task);

        triggerAsyncAnalysis(newTaskId, analysisId, objectKey, hint, cadObjectKey, codeNameMode);
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
        analysis.setInputMode(INPUT_MODE_IMAGE);
        analysis.setCreatedBy(null);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, PUBLIC_OPERATOR);
        // V20：视觉同步链路同样建 Revision 1（parser_version='vision'，parse_status=NULL，
        // 快照直接 READY；分析级 raw_result 列不再写入，Invariant 12）
        FloorPlanSceneRevision revision = new FloorPlanSceneRevision();
        revision.setAnalysisId(analysisId);
        revision.setAnalysisRevision(1);
        revision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_INITIAL_PARSE);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY);
        revision.setRoomRevision(1);
        revision.setFurnitureRevision(1);
        revision.setParserVersion("vision");
        revision.setRawResult(toJson(detected));
        revision.setCreatedAt(now);
        revision.setReadyAt(now);
        sceneRevisionMapper.insert(revision);
        analysis.setWorkingAnalysisRevision(1);
        analysis.setLatestAnalysisRevision(1);
        analysisMapper.updateById(analysis);

        int sortOrder = 0;
        for (FloorPlanDetectResult.Room detectedRoom : detected.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
            room.setAnalysisRevision(1);
            room.setRoomType(normalizeRoomType(detectedRoom.getRoomType()));
            room.setLabel(detectedRoom.getLabel());
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
            room.setGeometrySource(GEOMETRY_SOURCE_VISION);
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
        buildRooms(analysisId, 1, detected);
    }

    /**
     * 尺寸三级提取并落空间明细（V20 Revision 化重载）：明细带 analysis_revision 落库，
     * confirmation_status=AUTO（待人工确认）。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Scene Revision
     * @param detected         AI 空间识别结果
     */
    public void buildRooms(String analysisId, Integer analysisRevision, FloorPlanDetectResult detected) {
        LocalDateTime now = LocalDateTime.now();
        // 第②级换算上下文按批次解析一次（scaleText 为全图级标注）：解析不出可靠关系为 null
        ScaleContext scaleContext = resolveScaleConversion(analysisId, detected.getScaleText());
        int sortOrder = 0;
        for (FloorPlanDetectResult.Room detectedRoom : detected.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
            room.setAnalysisRevision(analysisRevision);
            room.setRoomType(normalizeRoomType(detectedRoom.getRoomType()));
            room.setLabel(detectedRoom.getLabel());
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

            room.setGeometrySource(GEOMETRY_SOURCE_VISION);
            room.setSortOrder(sortOrder++);
            room.setCreatedAt(now);
            room.setUpdatedAt(now);
            roomMapper.insert(room);
        }
    }

    /**
     * CAD 解析结果落空间明细（CAD 户型导入 P3，由
     * {@link AsyncTaskProcessor#processFloorPlanAnalysis} 调用）：
     *
     * <ul>
     *   <li>rooms：label/roomType（CAD 服务已给字典码，越界兜底 OTHER）/polygon（毫米坐标
     *       原样落库）/bbox（由 polygon 外包络按 drawingBounds 归一化 [0,1] 派生，兼容现有编辑器）
     *       /centroid（优先使用保证位于多边形内部的 labelPoint）/widthMm/depthMm/areaM2，dimension_source=cad_geometry，
     *       confidence 取 CAD 服务分级，geometry_source=cad_geometry；</li>
     *   <li>unnamedRegions：落 roomType=OTHER、label="未命名空间 N"、confidence=low，
     *       尺寸和面积均取 CAD 几何值，引导确认页人工命名；</li>
     *   <li>qualityIssues 不落明细，随 CadParseResult 整体存 floor_plan_analysis.raw_result 备查。</li>
     * </ul>
     *
     * @param analysisId 分析批次 ID
     * @param cadResult  CAD 解析服务输出（success=true 才进入本方法）
     */
    public void buildCadRooms(String analysisId, CadParseResult cadResult) {
        buildCadRooms(analysisId, cadResult, false);
    }

    /**
     * CAD 解析结果落空间明细（重载，CAD 户型导入增强·双文件通道）。
     *
     * <p>历史参数 codeNameMode 为兼容已入队任务保留，但不再丢弃 CAD 已识别出的房间名称和类型；
     * 双文件模式与单 CAD 模式均以 CAD 语义为初始值，用户可在确认页校正。</p>
     *
     * @param analysisId   分析批次 ID
     * @param cadResult    CAD 解析服务输出（success=true 才进入本方法）
     * @param codeNameMode 历史代号模式标记（兼容保留，当前不改变落库语义）
     */
    public void buildCadRooms(String analysisId, CadParseResult cadResult, boolean codeNameMode) {
        buildCadRooms(analysisId, 1, cadResult, codeNameMode);
    }

    /**
     * CAD 解析结果落空间明细（V20 Revision 化）：明细带 analysis_revision 落库，
     * confirmation_status=AUTO；返回 Parser 侧 roomId → 落库 room_id 映射
     * （供家具归属解析；Parser 未输出 roomId 的房间不在映射中）。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Scene Revision
     * @param cadResult        CAD 解析服务输出
     * @param codeNameMode     历史代号模式标记（兼容保留）
     * @return Parser roomId → DB roomId 映射
     */
    public Map<String, String> buildCadRooms(String analysisId, Integer analysisRevision,
                                             CadParseResult cadResult, boolean codeNameMode) {
        LocalDateTime now = LocalDateTime.now();
        CadParseResult.Bounds drawingBounds = cadResult.getDrawingBounds();
        int sortOrder = 0;
        Map<String, String> parserRoomIdMap = new HashMap<>();

        List<CadParseResult.Room> cadRooms = cadResult.getRooms() != null
            ? cadResult.getRooms() : List.of();
        for (CadParseResult.Room cadRoom : cadRooms) {
            FloorPlanRoom room = newCadRoom(analysisId, analysisRevision, cadRoom.getPolygon(),
                drawingBounds, cadRoom.getLabelPoint(), now);
            room.setLabel(cadRoom.getLabel());
            room.setRoomType(normalizeCadRoomType(cadRoom.getRoomType()));
            room.setWidthMm(roundMm(cadRoom.getWidthMm()));
            room.setDepthMm(roundMm(cadRoom.getDepthMm()));
            room.setAreaM2(roundAreaM2(cadRoom.getAreaM2()));
            room.setDimensionConfidence(normalizeConfidence(cadRoom.getConfidence()));
            if (cadRoom.getDimensionCheck() != null
                && StringUtils.hasText(cadRoom.getDimensionCheck().getAnnotated())) {
                // 图上尺寸标注原文（DIMENSION 交叉验证值），供确认页展示对照
                room.setDimensionText(cadRoom.getDimensionCheck().getAnnotated());
            }
            room.setSortOrder(sortOrder++);
            roomMapper.insert(room);
            if (StringUtils.hasText(cadRoom.getRoomId())) {
                parserRoomIdMap.put(cadRoom.getRoomId(), room.getRoomId());
            }
        }

        List<CadParseResult.UnnamedRegion> unnamedRegions = cadResult.getUnnamedRegions() != null
            ? cadResult.getUnnamedRegions() : List.of();
        int unnamedIndex = 0;
        for (CadParseResult.UnnamedRegion region : unnamedRegions) {
            FloorPlanRoom room = newCadRoom(analysisId, analysisRevision, region.getPolygon(),
                drawingBounds, region.getLabelPoint(), now);
            room.setLabel("未命名空间 " + (++unnamedIndex));
            room.setRoomType("OTHER");
            room.setWidthMm(roundMm(region.getWidthMm()));
            room.setDepthMm(roundMm(region.getDepthMm()));
            room.setAreaM2(roundAreaM2(region.getAreaM2()));
            room.setDimensionConfidence(CONFIDENCE_LOW);
            room.setSortOrder(sortOrder++);
            roomMapper.insert(room);
            // P1：Parser 为未命名空间也分配稳定 roomId，纳入家具归属映射
            if (StringUtils.hasText(region.getRoomId())) {
                parserRoomIdMap.put(region.getRoomId(), room.getRoomId());
            }
        }
        log.info("CAD 户型空间明细已落库，analysisId={}，revision={}，房间数={}，未命名空间数={}",
            analysisId, analysisRevision, cadRooms.size(), unnamedRegions.size());
        return parserRoomIdMap;
    }

    /**
     * CAD 家具候选落库（V20，P0-B）：每件 Parser 家具候选落一行 floor_plan_furniture
     * （analysis_revision 双条件），confirmation_status=AUTO（待人工逐件确认）。
     * 家具归属房间按 Parser roomId → DB room_id 映射解析，无法解析（跨房间/开放空间/
     * 目标区域外）置 null 并提示人工确认（§18.8）。UNKNOWN 类别不丢失。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Scene Revision
     * @param cadResult        CAD 解析服务输出
     * @param parserRoomIdMap  Parser roomId → DB roomId 映射
     */
    public void buildCadFurniture(String analysisId, Integer analysisRevision, CadParseResult cadResult,
                                  Map<String, String> parserRoomIdMap) {
        List<CadParseResult.Furniture> items = cadResult != null && cadResult.getFurniture() != null
            ? cadResult.getFurniture() : List.of();
        if (items.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int sortOrder = 0;
        for (CadParseResult.Furniture item : items) {
            FloorPlanFurniture furniture = new FloorPlanFurniture();
            furniture.setFurnitureId(IdGenerator.floorPlanFurnitureId());
            furniture.setAnalysisId(analysisId);
            furniture.setAnalysisRevision(analysisRevision);
            furniture.setSourceKind(StringUtils.hasText(item.getSourceKind())
                ? item.getSourceKind() : FloorPlanFurniture.SOURCE_KIND_INSERT);
            furniture.setCategory(StringUtils.hasText(item.getCategory()) ? item.getCategory() : "UNKNOWN");
            furniture.setSemanticLabel(item.getSemanticLabel());
            furniture.setRoomId(StringUtils.hasText(item.getRoomId()) && parserRoomIdMap != null
                ? parserRoomIdMap.get(item.getRoomId()) : null);
            furniture.setBlockName(item.getBlockName());
            furniture.setSourceHandles(toJson(item.getSourceHandles()));
            furniture.setInsertPath(toJson(item.getInsertPath()));
            furniture.setPosition(item.getPosition() != null
                ? toJson(Map.of("x", item.getPosition().getX(), "y", item.getPosition().getY())) : null);
            furniture.setRotationDeg(item.getRotationDeg() != null
                ? BigDecimal.valueOf(item.getRotationDeg()) : null);
            furniture.setFootprintPolygon(toJson(item.getFootprintPolygon()));
            furniture.setGeometryWidthMm(roundMm(item.getGeometryWidthMm()));
            furniture.setGeometryDepthMm(roundMm(item.getGeometryDepthMm()));
            furniture.setAnnotatedWidthMm(roundMm(item.getAnnotatedWidthMm()));
            furniture.setAnnotatedDepthMm(roundMm(item.getAnnotatedDepthMm()));
            furniture.setEffectiveWidthMm(roundMm(item.getEffectiveWidthMm()));
            furniture.setEffectiveDepthMm(roundMm(item.getEffectiveDepthMm()));
            furniture.setHeightMm(roundMm(item.getHeightMm()));
            furniture.setDimensionSource(item.getDimensionSource());
            furniture.setSemanticSource(item.getSemanticSource());
            furniture.setEvidence(toJson(item.getEvidence()));
            furniture.setQualityFlags(toJson(item.getQualityFlags()));
            furniture.setDetectionConfidence(item.getDetectionConfidence());
            furniture.setCategoryConfidence(item.getCategoryConfidence());
            furniture.setConfirmationStatus(FloorPlanFurniture.CONFIRM_AUTO);
            // P1：候选清洗/去重分组/视觉语义分组原样落库（REJECTED/DUPLICATE 仅标记不删除）
            furniture.setCandidateStatus(StringUtils.hasText(item.getCandidateStatus())
                ? item.getCandidateStatus() : "VALID");
            furniture.setGeometryFingerprint(item.getGeometryFingerprint());
            furniture.setDuplicateGroupId(item.getDuplicateGroupId());
            furniture.setDuplicateStatus(item.getDuplicateStatus());
            furniture.setDuplicateCount(item.getDuplicateCount());
            furniture.setVisionGroupId(item.getVisionGroupId());
            furniture.setSortOrder(sortOrder++);
            furniture.setCreatedAt(now);
            furniture.setUpdatedAt(now);
            furnitureMapper.insert(furniture);
        }
        log.info("CAD 家具候选已落库，analysisId={}，revision={}，家具数={}",
            analysisId, analysisRevision, items.size());
    }

    /**
     * 存储 CAD 解析器生成的规范 PNG 预览，并关联到分析批次。
     * 预览与 {@link CadParseResult#getDrawingBounds()} 共用 CAD 坐标范围，仅用于展示，
     * 不替代用户上传的原始文件。
     *
     * @param analysisId  分析批次 ID
     * @param previewBytes PNG 字节；为空时跳过（解析器可在渲染失败时仍返回几何结果）
     * @param preview     预览元数据（宽、高、坐标范围）
     */
    @Transactional
    public void storeCadPreview(String analysisId, byte[] previewBytes, CadParseResult.Preview preview) {
        if (previewBytes == null || previewBytes.length == 0 || preview == null) {
            log.warn("CAD 规范预览为空，跳过存储，analysisId={}", analysisId);
            return;
        }
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        String imageId = storeCadPreviewImage(analysis, previewBytes, preview);
        if (imageId != null) {
            analysis.setPreviewImageId(imageId);
            analysis.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(analysis);
        }
    }

    /**
     * 存储 CAD 规范预览到 image_assets 并返回图片 ID（V20：预览二进制只落 image_assets，
     * Revision 仅保存 preview_image_id 引用；本方法不更新任何分析/Revision 列）。
     *
     * @param analysis     分析记录（取 createdBy 归属）
     * @param previewBytes PNG 字节；为空返回 null
     * @param preview      预览元数据
     * @return 图片 ID；无预览返回 null
     */
    private String storeCadPreviewImage(FloorPlanAnalysis analysis, byte[] previewBytes,
                                        CadParseResult.Preview preview) {
        if (previewBytes == null || previewBytes.length == 0 || preview == null) {
            log.warn("CAD 规范预览为空，跳过存储，analysisId={}", analysis.getAnalysisId());
            return null;
        }
        String imageId = IdGenerator.imageId();
        String objectKey = "images/cad-preview/" + imageId + ".png";
        String storagePath;
        try {
            storagePath = storageService.store(new ByteArrayInputStream(previewBytes), objectKey,
                previewBytes.length, "image/png");
        } catch (IOException e) {
            log.error("CAD 规范预览存储失败，analysisId={}", analysis.getAnalysisId(), e);
            throw new BusinessException("CAD 规范预览存储失败");
        }
        registerStorageRollbackCleanup(storagePath);

        LocalDateTime now = LocalDateTime.now();
        ImageAssets asset = new ImageAssets();
        asset.setImageId(imageId);
        asset.setImageType("floor_plan_cad_preview");
        asset.setStoragePath(storagePath);
        asset.setFileSize((long) previewBytes.length);
        asset.setWidth(preview.getWidth());
        asset.setHeight(preview.getHeight());
        asset.setFormat("png");
        asset.setPrimary(false);
        asset.setAiProcessed(false);
        asset.setUploadedBy(analysis.getCreatedBy());
        asset.setCreatedAt(now);
        imageAssetsMapper.insert(asset);
        auditLogService.logCreate("image_assets", imageId, asset,
            StringUtils.hasText(analysis.getCreatedBy()) ? analysis.getCreatedBy() : PUBLIC_OPERATOR);
        log.info("CAD 规范预览已存储，analysisId={}，imageId={}，size={} bytes",
            analysis.getAnalysisId(), imageId, previewBytes.length);
        return imageId;
    }

    /** 构造 CAD 通道空间明细公共字段（polygon/centroid/bbox/dimensionSource/geometrySource/时间戳）。 */
    private FloorPlanRoom newCadRoom(String analysisId, Integer analysisRevision, List<List<Double>> polygon,
                                     CadParseResult.Bounds drawingBounds,
                                     CadParseResult.Point labelPoint, LocalDateTime now) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(IdGenerator.floorPlanRoomId());
        room.setAnalysisId(analysisId);
        room.setAnalysisRevision(analysisRevision);
        room.setConfirmationStatus("AUTO");
        room.setPolygon(toJson(polygon));
        room.setCentroid(toJson(labelPoint != null && labelPoint.getX() != null && labelPoint.getY() != null
            ? Map.of("x", labelPoint.getX(), "y", labelPoint.getY())
            : computeCentroid(polygon)));
        room.setBbox(toJson(deriveNormalizedBBox(polygon, drawingBounds)));
        room.setDimensionSource(DIM_SOURCE_CAD);
        room.setGeometrySource(GEOMETRY_SOURCE_CAD);
        room.setCreatedAt(now);
        room.setUpdatedAt(now);
        return room;
    }

    /** CAD 通道 room_type 归一：字典码大写化（study → STUDY_ROOM 沿用视觉通道映射），越界兜底 OTHER。 */
    private String normalizeCadRoomType(String roomType) {
        String normalized = normalizeRoomType(roomType);
        return VALID_ROOM_TYPES.contains(normalized) ? normalized : "OTHER";
    }

    /** 置信度归一：仅接受 high/mid/low，其余兜底 mid（CAD 服务置信度分级的防御性校验）。 */
    private String normalizeConfidence(String confidence) {
        if (CONFIDENCE_HIGH.equals(confidence) || CONFIDENCE_MID.equals(confidence)
            || CONFIDENCE_LOW.equals(confidence)) {
            return confidence;
        }
        return CONFIDENCE_MID;
    }

    /** 毫米数取整（CAD 服务为 double，落库 INTEGER）；空值/非正数返回 null。 */
    private Integer roundMm(Double mm) {
        return mm != null && mm > 0 ? (int) Math.round(mm) : null;
    }

    /** 面积（平方米，两位小数）；空值/非正数返回 null。 */
    private BigDecimal roundAreaM2(Double areaM2) {
        return areaM2 != null && areaM2 > 0
            ? BigDecimal.valueOf(areaM2).setScale(2, RoundingMode.HALF_UP) : null;
    }

    /** 质心：顶点均值（毫米），{"x":..,"y":..}；顶点缺失返回 null。 */
    private Map<String, Double> computeCentroid(List<List<Double>> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return null;
        }
        double sumX = 0;
        double sumY = 0;
        int count = 0;
        for (List<Double> point : polygon) {
            if (point != null && point.size() >= 2 && point.get(0) != null && point.get(1) != null) {
                sumX += point.get(0);
                sumY += point.get(1);
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return Map.of("x", sumX / count, "y", sumY / count);
    }

    /**
     * 由 polygon 外包络派生归一化 bbox（[0,1]，兼容现有编辑器）：外包络相对
     * drawingBounds（图纸外包络）归一；drawingBounds 缺失时以 polygon 自身外包络
     * 为参照（bbox 退化为 0,0,1,1）。顶点缺失返回 null。
     */
    private FloorPlanBBox deriveNormalizedBBox(List<List<Double>> polygon, CadParseResult.Bounds drawing) {
        if (polygon == null || polygon.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean hasPoint = false;
        for (List<Double> point : polygon) {
            if (point != null && point.size() >= 2 && point.get(0) != null && point.get(1) != null) {
                minX = Math.min(minX, point.get(0));
                minY = Math.min(minY, point.get(1));
                maxX = Math.max(maxX, point.get(0));
                maxY = Math.max(maxY, point.get(1));
                hasPoint = true;
            }
        }
        if (!hasPoint) {
            return null;
        }
        double originX = drawing != null && drawing.getMinX() != null ? drawing.getMinX() : minX;
        double originY = drawing != null && drawing.getMinY() != null ? drawing.getMinY() : minY;
        double spanX = drawing != null && drawing.getMaxX() != null && drawing.getMinX() != null
            ? drawing.getMaxX() - drawing.getMinX() : maxX - minX;
        double spanY = drawing != null && drawing.getMaxY() != null && drawing.getMinY() != null
            ? drawing.getMaxY() - drawing.getMinY() : maxY - minY;
        if (spanX <= 0 || spanY <= 0) {
            return null;
        }
        return new FloorPlanBBox(
            (minX - originX) / spanX, (minY - originY) / spanY,
            (maxX - minX) / spanX, (maxY - minY) / spanY);
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
     * 按 analysisId 批量聚合未软删空间数与几何来源（列表页避免 N+1，一次查询同时产出两项）。
     *
     * <p>V20 Revision 化：只统计各分析展示 Revision（working ?? active ?? latest ?? 1）的房间，
     * 与详情默认装配口径一致；房间 analysis_revision 为 null 按 1 处理（历史数据）。</p>
     *
     * <p>几何来源（V14 列表契约）：展示 Revision 下任一空间为 cad_geometry 则 cad_geometry，
     * 否则有空间即 ai_vision；无空间则不写入 geometrySourceMap（列表项为 null）。</p>
     *
     * @param records           页内分析批次记录
     * @param roomCountMap      输出：analysisId → 空间数
     * @param geometrySourceMap 输出：analysisId → 几何来源
     */
    private void batchRoomStats(List<FloorPlanAnalysis> records, Map<String, Long> roomCountMap,
                                Map<String, String> geometrySourceMap) {
        if (records.isEmpty()) {
            return;
        }
        Map<String, Integer> displayRevisionMap = new HashMap<>();
        for (FloorPlanAnalysis analysis : records) {
            displayRevisionMap.put(analysis.getAnalysisId(),
                analysis.getWorkingAnalysisRevision() != null ? analysis.getWorkingAnalysisRevision()
                    : analysis.getActiveAnalysisRevision() != null ? analysis.getActiveAnalysisRevision()
                    : analysis.getLatestAnalysisRevision() != null ? analysis.getLatestAnalysisRevision() : 1);
        }
        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .select("analysis_id", "analysis_revision", "geometry_source")
            .in("analysis_id", displayRevisionMap.keySet()));
        for (FloorPlanRoom room : rooms) {
            Integer roomRevision = room.getAnalysisRevision() != null ? room.getAnalysisRevision() : 1;
            if (!roomRevision.equals(displayRevisionMap.get(room.getAnalysisId()))) {
                continue;
            }
            roomCountMap.merge(room.getAnalysisId(), 1L, Long::sum);
            if (GEOMETRY_SOURCE_CAD.equals(room.getGeometrySource())) {
                geometrySourceMap.put(room.getAnalysisId(), GEOMETRY_SOURCE_CAD);
            } else if (GEOMETRY_SOURCE_MANUAL.equals(room.getGeometrySource())
                && !GEOMETRY_SOURCE_CAD.equals(geometrySourceMap.get(room.getAnalysisId()))) {
                geometrySourceMap.put(room.getAnalysisId(), GEOMETRY_SOURCE_MANUAL);
            } else {
                geometrySourceMap.putIfAbsent(room.getAnalysisId(), GEOMETRY_SOURCE_VISION);
            }
        }
    }

    /**
     * 从上一轮任务的 input_data 中提取用户补充说明（hint），供重试沿用；无则返回 null。
     */
    private String extractHintFromTask(String taskId) {
        return extractTaskInputField(taskId, "hint");
    }

    /**
     * 从上一轮任务的 input_data 中提取指定字段（文本/布尔/数值均按字符串返回），
     * 供重试沿用（hint、cadObjectKey、codeNameMode）；无则返回 null。
     *
     * @param taskId 上一轮任务 ID
     * @param field  input_data 字段名
     * @return 字段值字符串，缺失/解析失败返回 null
     */
    private String extractTaskInputField(String taskId, String field) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        try {
            AsyncTask oldTask = asyncTaskMapper.selectById(taskId);
            if (oldTask == null || !StringUtils.hasText(oldTask.getInputData())) {
                return null;
            }
            JsonNode node = objectMapper.readTree(oldTask.getInputData()).get(field);
            return node != null && (node.isTextual() || node.isBoolean() || node.isNumber())
                && StringUtils.hasText(node.asText())
                ? node.asText() : null;
        } catch (Exception e) {
            log.warn("解析上一轮任务 input_data 字段失败，按无该字段处理，taskId={}，field={}", taskId, field, e);
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
     * 校验并规整归属项目 ID（V14）：非空时去首尾空白并复用
     * {@link ProjectService#getAccessibleProject} 校验项目存在（未软删）且当前用户可见
     * （归属人或 ADMIN）；空值返回 null（不挂项目）。
     *
     * @param projectId 项目 ID，可空
     * @return 规整后的项目 ID，空入参返回 null
     */
    private String resolveAccessibleProjectId(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return null;
        }
        String trimmed = projectId.trim();
        projectService.getAccessibleProject(trimmed);
        return trimmed;
    }

    /**
     * 规整户型名称/备注（V14）：去首尾空白，空值返回 null；
     * 超过 source_name 列长（128）抛 400 中文提示。
     */
    private String normalizeSourceName(String sourceName) {
        if (!StringUtils.hasText(sourceName)) {
            return null;
        }
        String trimmed = sourceName.trim();
        if (trimmed.length() > 128) {
            throw new BusinessException("户型名称长度不能超过 128");
        }
        return trimmed;
    }

    /** 详情项目名称解析：无归属或项目已删除返回 null（防御性，不阻断详情查询）。 */
    private String resolveProjectName(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return null;
        }
        Project project = projectMapper.selectById(projectId);
        return project != null ? project.getProjectName() : null;
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
        triggerAsyncAnalysis(taskId, analysisId, objectKey, hint, null, false);
    }

    /**
     * 事务提交后触发异步分析（重载，CAD 户型导入增强·双文件通道）：
     * cadObjectKey 非空时走 6 参 {@link AsyncTaskProcessor#processFloorPlanAnalysis}
     * （CAD 通道 + 可选代号模式），否则走既有 4 参形态（视觉/单文件 CAD 行为零变化）。
     */
    private void triggerAsyncAnalysis(String taskId, String analysisId, String objectKey, String hint,
                                      String cadObjectKey, boolean codeNameMode) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invokeFloorPlanProcessor(taskId, analysisId, objectKey, hint, cadObjectKey, codeNameMode);
                }
            });
        } else {
            invokeFloorPlanProcessor(taskId, analysisId, objectKey, hint, cadObjectKey, codeNameMode);
        }
    }

    /** 按是否携带 CAD 对象键分派异步处理器重载（保持单文件通道调用形态不变）。 */
    private void invokeFloorPlanProcessor(String taskId, String analysisId, String objectKey, String hint,
                                          String cadObjectKey, boolean codeNameMode) {
        if (StringUtils.hasText(cadObjectKey)) {
            asyncTaskProcessor.processFloorPlanAnalysis(
                taskId, analysisId, objectKey, hint, cadObjectKey, codeNameMode);
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

    /**
     * Revision 化详情装配（V20）：旧字段回归 + Revision 元数据/快照字段/家具/激活就绪度。
     * 预览图取本 Revision 的 preview_image_id；质量提示与 bounds 优先 Revision 列，
     * 回退 Revision 快照，再回退分析级旧列（历史数据兼容）。
     *
     * @param analysis  分析记录
     * @param revision  展示 Revision
     * @param rooms     该 Revision 未软删房间
     * @param furniture 该 Revision 未软删家具
     * @return 分析详情
     */
    private FloorPlanAnalysisResponse toResponse(FloorPlanAnalysis analysis, FloorPlanSceneRevision revision,
                                                 List<FloorPlanRoom> rooms,
                                                 List<FloorPlanFurniture> furniture) {
        FloorPlanAnalysisResponse response = toResponse(analysis, rooms);
        String revisionRaw = StringUtils.hasText(revision.getRawResult())
            ? revision.getRawResult() : analysis.getRawResult();

        String previewImageId = StringUtils.hasText(revision.getPreviewImageId())
            ? revision.getPreviewImageId() : analysis.getPreviewImageId();
        response.setPreviewImageId(previewImageId);
        response.setPreviewUrl(previewImageId != null ? "/api/v1/images/" + previewImageId : null);
        response.setQualityIssues(parseQualityIssuesColumn(revision.getQualityIssues(), revisionRaw));
        response.setDrawingBounds(parseBoundsColumn(revision.getDrawingBounds(),
            parseDrawingBounds(revisionRaw)));
        response.setPreviewBounds(parseBoundsColumn(revision.getPreviewBounds(),
            parsePreviewBounds(revisionRaw)));

        response.setAnalysisRevision(revision.getAnalysisRevision());
        response.setRevisionStatus(revision.getRevisionStatus());
        response.setActiveAnalysisRevision(analysis.getActiveAnalysisRevision());
        response.setWorkingAnalysisRevision(analysis.getWorkingAnalysisRevision());
        response.setLatestAnalysisRevision(analysis.getLatestAnalysisRevision());
        response.setRoomRevision(revision.getRoomRevision());
        response.setFurnitureRevision(revision.getFurnitureRevision());
        response.setParseStatus(revision.getParseStatus());
        response.setCapabilities(parseJsonField(revisionRaw, "capabilities"));
        Object unitResolution = parseJsonObject(revision.getUnitResolution());
        response.setUnitResolution(unitResolution != null ? unitResolution
            : parseJsonField(revisionRaw, "unitResolution"));
        response.setTargetScene(parseJsonField(revisionRaw, "targetScene"));
        Object candidates = parseJsonObject(revision.getTargetSceneCandidates());
        response.setTargetSceneCandidates(candidates != null ? candidates
            : parseJsonField(revisionRaw, "targetSceneCandidates"));
        response.setFurniture(furniture.stream().map(this::toFurnitureResponse).toList());
        response.setActivationReadiness(sceneRevisionService.evaluateReadiness(revision));
        return response;
    }

    /** 质量提示：优先 Revision quality_issues 列（JSON 数组），回退快照 raw_result.qualityIssues。 */
    private List<FloorPlanQualityIssue> parseQualityIssuesColumn(String qualityIssuesJson, String rawResult) {
        if (StringUtils.hasText(qualityIssuesJson)) {
            try {
                JsonNode issuesNode = objectMapper.readTree(qualityIssuesJson);
                if (issuesNode.isArray()) {
                    List<FloorPlanQualityIssue> issues = new ArrayList<>();
                    for (JsonNode node : issuesNode) {
                        issues.add(new FloorPlanQualityIssue(
                            textOrNull(node.get("level")), textOrNull(node.get("code")),
                            textOrNull(node.get("message"))));
                    }
                    return issues;
                }
            } catch (Exception e) {
                log.warn("解析 Revision 质量提示列失败，回退快照", e);
            }
        }
        return parseQualityIssues(rawResult);
    }

    /** bounds 列 JSON → FloorPlanDrawingBounds；缺失/解析失败返回 fallback。 */
    private FloorPlanDrawingBounds parseBoundsColumn(String boundsJson, FloorPlanDrawingBounds fallback) {
        if (!StringUtils.hasText(boundsJson)) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(boundsJson);
            if (!node.isObject()) {
                return fallback;
            }
            FloorPlanDrawingBounds bounds = new FloorPlanDrawingBounds();
            bounds.setMinX(doubleOrNull(node.get("minX")));
            bounds.setMinY(doubleOrNull(node.get("minY")));
            bounds.setMaxX(doubleOrNull(node.get("maxX")));
            bounds.setMaxY(doubleOrNull(node.get("maxY")));
            return bounds;
        } catch (Exception e) {
            log.warn("解析 Revision bounds 列失败，回退快照", e);
            return fallback;
        }
    }

    /** 提取快照 JSON 字段为通用对象（capabilities/unitResolution/targetScene/candidates）。 */
    private Object parseJsonField(String rawJson, String field) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson).get(field);
            return node != null && !node.isNull() ? objectMapper.convertValue(node, Object.class) : null;
        } catch (Exception e) {
            log.warn("解析快照字段失败，field={}", field, e);
            return null;
        }
    }

    /** 解析 JSON 列为通用对象；缺失/失败返回 null。 */
    private Object parseJsonObject(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("解析 JSON 列失败", e);
            return null;
        }
    }

    /** 家具实体 → 响应（JSONB 列反序列化为契约结构）。 */
    private FloorPlanFurnitureResponse toFurnitureResponse(FloorPlanFurniture furniture) {
        FloorPlanFurnitureResponse response = new FloorPlanFurnitureResponse();
        response.setFurnitureId(furniture.getFurnitureId());
        response.setSourceKind(furniture.getSourceKind());
        response.setCategory(furniture.getCategory());
        response.setSemanticLabel(furniture.getSemanticLabel());
        response.setRoomId(furniture.getRoomId());
        response.setBlockName(furniture.getBlockName());
        response.setSourceHandles(parseStringList(furniture.getSourceHandles()));
        response.setInsertPath(parseStringList(furniture.getInsertPath()));
        response.setPosition(parsePoint(furniture.getPosition()));
        response.setRotationDeg(furniture.getRotationDeg());
        response.setFootprintPolygon(parsePolygon(furniture.getFootprintPolygon()));
        response.setGeometryWidthMm(furniture.getGeometryWidthMm());
        response.setGeometryDepthMm(furniture.getGeometryDepthMm());
        response.setAnnotatedWidthMm(furniture.getAnnotatedWidthMm());
        response.setAnnotatedDepthMm(furniture.getAnnotatedDepthMm());
        response.setEffectiveWidthMm(furniture.getEffectiveWidthMm());
        response.setEffectiveDepthMm(furniture.getEffectiveDepthMm());
        response.setHeightMm(furniture.getHeightMm());
        response.setDimensionSource(furniture.getDimensionSource());
        response.setSemanticSource(furniture.getSemanticSource());
        response.setEvidence(parseStringList(furniture.getEvidence()));
        response.setQualityFlags(parseStringList(furniture.getQualityFlags()));
        response.setDetectionConfidence(furniture.getDetectionConfidence());
        response.setCategoryConfidence(furniture.getCategoryConfidence());
        response.setConfirmationStatus(furniture.getConfirmationStatus());
        response.setCandidateStatus(furniture.getCandidateStatus());
        response.setDuplicateGroupId(furniture.getDuplicateGroupId());
        response.setDuplicateStatus(furniture.getDuplicateStatus());
        response.setDuplicateCount(furniture.getDuplicateCount());
        response.setVisionGroupId(furniture.getVisionGroupId());
        response.setVisionConfidence(furniture.getVisionConfidence());
        response.setMatchedFromObjectId(furniture.getMatchedFromObjectId());
        response.setDerivedFromIds(parseStringList(furniture.getDerivedFromIds()));
        response.setSortOrder(furniture.getSortOrder());
        return response;
    }

    /** 解析字符串数组 JSON 列；缺失/失败返回 null（可空字段）。 */
    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("解析字符串数组 JSON 失败", e);
            return null;
        }
    }

    /** 旧装配（分析级字段 + 房间列表）；Revision 化装配在此基础上叠加 Revision 字段。 */
    private FloorPlanAnalysisResponse toResponse(FloorPlanAnalysis analysis, List<FloorPlanRoom> rooms) {
        FloorPlanAnalysisResponse response = new FloorPlanAnalysisResponse();
        response.setAnalysisId(analysis.getAnalysisId());
        response.setImageId(analysis.getImageId());
        response.setImageUrl(analysis.getImageId() != null ? "/api/v1/images/" + analysis.getImageId() : null);
        ImageAssets sourceAsset = analysis.getImageId() != null
            ? imageAssetsMapper.selectById(analysis.getImageId()) : null;
        boolean sourceIsCad = sourceAsset != null && sourceAsset.getFormat() != null
            && CAD_EXTENSIONS.contains(sourceAsset.getFormat().toLowerCase());
        response.setReferenceImageUrl(!sourceIsCad ? response.getImageUrl() : null);
        response.setPreviewImageId(analysis.getPreviewImageId());
        response.setPreviewUrl(analysis.getPreviewImageId() != null
            ? "/api/v1/images/" + analysis.getPreviewImageId() : null);
        response.setStatus(analysis.getStatus());
        response.setTaskId(analysis.getTaskId());
        response.setScaleRatio(analysis.getScaleRatio());
        response.setSource(analysis.getSource());
        response.setProjectId(analysis.getProjectId());
        response.setProjectName(resolveProjectName(analysis.getProjectId()));
        response.setSourceName(analysis.getSourceName());
        response.setErrorMessage(analysis.getErrorMessage());
        response.setCreatedBy(analysis.getCreatedBy());
        response.setCreatedAt(analysis.getCreatedAt());
        response.setUpdatedAt(analysis.getUpdatedAt());
        List<FloorPlanRoomResponse> roomResponses = new ArrayList<>();
        for (FloorPlanRoom room : rooms) {
            roomResponses.add(toRoomResponse(room));
        }
        response.setRooms(roomResponses);
        response.setGeometrySource(resolveGeometrySource(analysis, rooms));
        response.setQualityIssues(parseQualityIssues(analysis.getRawResult()));
        response.setDrawingBounds(parseDrawingBounds(analysis.getRawResult()));
        response.setPreviewBounds(parsePreviewBounds(analysis.getRawResult()));
        response.setInputMode(analysis.getInputMode());
        response.setLayout(parseJsonTree(analysis.getLayoutJson()));
        response.setLayoutRevision(analysis.getLayoutRevision());
        return response;
    }

    /**
     * 几何来源判定（CAD 户型导入 P3，前端契约）：优先取落库明细的 geometry_source；
     * 无明细（pending/failed/空结果）按原图格式判定（dwg/dxf → cad_geometry），兜底 ai_vision。
     */
    private String resolveGeometrySource(FloorPlanAnalysis analysis, List<FloorPlanRoom> rooms) {
        for (FloorPlanRoom room : rooms) {
            if (StringUtils.hasText(room.getGeometrySource())) {
                return room.getGeometrySource();
            }
        }
        if (INPUT_MODE_MANUAL.equals(analysis.getInputMode())) {
            return GEOMETRY_SOURCE_MANUAL;
        }
        if (StringUtils.hasText(analysis.getImageId())) {
            ImageAssets imageAsset = imageAssetsMapper.selectById(analysis.getImageId());
            if (imageAsset != null && imageAsset.getFormat() != null
                && CAD_EXTENSIONS.contains(imageAsset.getFormat().toLowerCase())) {
                return GEOMETRY_SOURCE_CAD;
            }
        }
        return GEOMETRY_SOURCE_VISION;
    }

    /**
     * 从 raw_result 提取质量问题清单（CAD 通道 raw_result 即 CadParseResult，
     * 含 qualityIssues；视觉通道无此字段，恒返回空数组——前端契约：无问题为空数组而非 null）。
     */
    private List<FloorPlanQualityIssue> parseQualityIssues(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return List.of();
        }
        try {
            JsonNode issuesNode = objectMapper.readTree(rawResult).get("qualityIssues");
            if (issuesNode == null || !issuesNode.isArray()) {
                return List.of();
            }
            List<FloorPlanQualityIssue> issues = new ArrayList<>();
            for (JsonNode node : issuesNode) {
                issues.add(new FloorPlanQualityIssue(
                    textOrNull(node.get("level")), textOrNull(node.get("code")), textOrNull(node.get("message"))));
            }
            return issues;
        } catch (Exception e) {
            log.warn("解析户型分析质量问题清单失败，按无问题处理", e);
            return List.of();
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * 从 raw_result 提取 CAD 图纸外包络（CAD 户型导入增强·双文件通道，前端契约）：
     * CAD 通道 raw_result 即 CadParseResult（含 drawingBounds 毫米坐标系范围），
     * 前端用它把 polygon 毫米坐标归一化叠加到底图上；视觉通道/缺失恒返回 null。
     */
    private FloorPlanDrawingBounds parseDrawingBounds(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return null;
        }
        try {
            JsonNode boundsNode = objectMapper.readTree(rawResult).get("drawingBounds");
            if (boundsNode == null || !boundsNode.isObject()) {
                return null;
            }
            FloorPlanDrawingBounds bounds = new FloorPlanDrawingBounds();
            bounds.setMinX(doubleOrNull(boundsNode.get("minX")));
            bounds.setMinY(doubleOrNull(boundsNode.get("minY")));
            bounds.setMaxX(doubleOrNull(boundsNode.get("maxX")));
            bounds.setMaxY(doubleOrNull(boundsNode.get("maxY")));
            if (bounds.getMinX() == null && bounds.getMinY() == null
                && bounds.getMaxX() == null && bounds.getMaxY() == null) {
                return null;
            }
            return bounds;
        } catch (Exception e) {
            log.warn("解析户型分析图纸外包络失败，按无外包络处理", e);
            return null;
        }
    }

    /** 从 CAD raw_result.preview.bounds 提取规范预览坐标范围；旧数据回退 drawingBounds。 */
    private FloorPlanDrawingBounds parsePreviewBounds(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            JsonNode previewNode = root.get("preview");
            JsonNode boundsNode = previewNode != null ? previewNode.get("bounds") : null;
            if (boundsNode == null || !boundsNode.isObject()) {
                return parseDrawingBounds(rawResult);
            }
            FloorPlanDrawingBounds bounds = new FloorPlanDrawingBounds();
            bounds.setMinX(doubleOrNull(boundsNode.get("minX")));
            bounds.setMinY(doubleOrNull(boundsNode.get("minY")));
            bounds.setMaxX(doubleOrNull(boundsNode.get("maxX")));
            bounds.setMaxY(doubleOrNull(boundsNode.get("maxY")));
            return bounds;
        } catch (Exception e) {
            log.warn("解析 CAD 规范预览坐标范围失败，回退 drawingBounds", e);
            return parseDrawingBounds(rawResult);
        }
    }

    private static Double doubleOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private FloorPlanRoomResponse toRoomResponse(FloorPlanRoom room) {
        FloorPlanRoomResponse response = new FloorPlanRoomResponse();
        response.setRoomId(room.getRoomId());
        response.setRoomType(room.getRoomType());
        response.setLabel(room.getLabel());
        response.setPolygon(parsePolygon(room.getPolygon()));
        response.setLabelPoint(parsePoint(room.getCentroid()));
        response.setBbox(parseBBox(room.getBbox()));
        response.setWidthMm(room.getWidthMm());
        response.setDepthMm(room.getDepthMm());
        response.setAreaM2(room.getAreaM2());
        response.setDimensionSource(room.getDimensionSource());
        response.setDimensionConfidence(room.getDimensionConfidence());
        response.setDimensionText(room.getDimensionText());
        response.setSortOrder(room.getSortOrder());
        response.setConfirmationStatus(room.getConfirmationStatus());
        response.setShapeParts(parseManualRects(room.getShapeParts()));
        response.setPlacementZone(parseManualRect(room.getPlacementZone()));
        response.setPlacementZones(parsePlacementZones(room.getPlacementZone()));
        return response;
    }

    private JsonNode parseJsonTree(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("解析手画布局 JSON 失败，按空布局处理", e);
            return null;
        }
    }

    private List<ManualFloorPlanRectResponse> parseManualRects(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, ManualFloorPlanRectResponse.class));
        } catch (Exception e) {
            log.warn("解析手画空间组成块失败", e);
            return null;
        }
    }

    private ManualFloorPlanRectResponse parseManualRect(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.treeToValue(node.has("primary") ? node.get("primary") : node,
                ManualFloorPlanRectResponse.class);
        } catch (Exception e) {
            log.warn("解析手画空间主布置区失败", e);
            return null;
        }
    }

    private List<ManualFloorPlanRectResponse> parsePlacementZones(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("zones") && node.get("zones").isArray()) {
                return objectMapper.convertValue(node.get("zones"), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ManualFloorPlanRectResponse.class));
            }
            return List.of(objectMapper.treeToValue(node, ManualFloorPlanRectResponse.class));
        } catch (Exception e) {
            log.warn("解析手画空间布置区列表失败", e);
            return null;
        }
    }

    private FloorPlanPoint parsePoint(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FloorPlanPoint.class);
        } catch (Exception e) {
            log.warn("解析 CAD 空间标签锚点失败", e);
            return null;
        }
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

    /** 解析空间 polygon JSON（[[x,y],...] 毫米坐标）；缺失/解析失败返回 null（可空字段）。 */
    private List<List<Double>> parsePolygon(String polygonJson) {
        if (!StringUtils.hasText(polygonJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(polygonJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
        } catch (Exception e) {
            log.warn("解析空间 polygon 失败，按无多边形处理，polygon={}", polygonJson, e);
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
