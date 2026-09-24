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
import com.rsdp.dto.response.FloorPlanBatchDeleteResponse;
import com.rsdp.dto.response.FloorPlanDrawingBounds;
import com.rsdp.dto.response.FloorPlanQualityIssue;
import com.rsdp.dto.response.FloorPlanRoomResponse;
import com.rsdp.dto.response.FloorPlanPoint;
import com.rsdp.dto.response.ScaleSuggestionResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.floorplan.parser.FloorPlanFileType;
import com.rsdp.floorplan.parser.FloorPlanParserRegistry;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
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

        // 2. 建分析批次记录
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_PENDING);
        analysis.setTaskId(taskId);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setProjectId(accessibleProjectId);
        analysis.setSourceName(normalizedSourceName);
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

        // 3. 建分析批次记录（imageId 指向底图，imageUrl 即预览图地址）
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId(imageId);
        analysis.setStatus(STATUS_PENDING);
        analysis.setTaskId(taskId);
        analysis.setSource(SOURCE_ADMIN);
        analysis.setProjectId(accessibleProjectId);
        analysis.setSourceName(normalizedSourceName);
        analysis.setCreatedBy(operator);
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        analysisMapper.insert(analysis);
        auditLogService.logCreate("floor_plan_analysis", analysisId, analysis, operator);

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
        batchRoomStats(records.stream().map(FloorPlanAnalysis::getAnalysisId).toList(),
            roomCountMap, geometrySourceMap);
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
            item.setGeometrySource(geometrySourceMap.get(analysis.getAnalysisId()));
            item.setQualityIssueCount(qualityIssueCount(analysis.getQualityIssues(), analysis.getRawResult()));
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
        return getAnalysisInternal(analysisId, true);
    }

    /**
     * 查询官网游客 CAD 分析详情；调用方须在进入本方法前完成匿名访问凭证校验。
     *
     * @param analysisId 分析批次 ID
     * @return 分析详情
     */
    public FloorPlanAnalysisResponse getPublicAnalysis(String analysisId) {
        return getAnalysisInternal(analysisId, false);
    }

    private FloorPlanAnalysisResponse getAnalysisInternal(String analysisId, boolean enforceOwner) {
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

        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .orderByAsc("sort_order"));
        FloorPlanAnalysisResponse response = toResponse(analysis, rooms);
        // CAD 通道尺寸来自真实几何，跳过自动标定建议（CAD 户型导入 P3）
        if (!GEOMETRY_SOURCE_CAD.equals(response.getGeometrySource())) {
            response.setScaleSuggestion(buildScaleSuggestion(analysis, rooms));
        }
        return response;
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
     * 接口 3：人工校正，整体替换语义——提交的列表为最终生效数据
     * （带 roomId 就地更新、不带 roomId 新增、未提交的已识别空间软删），状态 → confirmed。
     *
     * <p>视觉识别空间仍按人工提交的矩形尺寸重算并标记为 manual/high；CAD 空间只允许
     * 校正名称、类型和顺序，精确多边形、面积、包围盒及 CAD 尺寸元数据保持不变。</p>
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
                if (StringUtils.hasText(item.getLabel())) {
                    room.setLabel(item.getLabel().trim());
                }
                if (!GEOMETRY_SOURCE_CAD.equals(room.getGeometrySource())) {
                    room.setWidthMm(item.getWidthMm());
                    room.setDepthMm(item.getDepthMm());
                    room.setAreaM2(areaM2);
                    room.setBbox(bboxJson);
                    room.setDimensionSource(DIM_SOURCE_MANUAL);
                    room.setDimensionConfidence(CONFIDENCE_HIGH);
                }
                room.setSortOrder(sort);
                room.setUpdatedAt(now);
                roomMapper.updateById(room);
            } else {
                FloorPlanRoom room = new FloorPlanRoom();
                room.setRoomId(IdGenerator.floorPlanRoomId());
                room.setAnalysisId(analysisId);
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
                room.setSortOrder(sort);
                room.setCreatedAt(now);
                room.setUpdatedAt(now);
                roomMapper.insert(room);
            }
            sort++;
        }

        // V14：传了 projectId 则校验项目可见性后覆盖写入项目归属（补挂/改挂），并记审计日志；
        // 不传则不动 project_id
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
        LocalDateTime now = LocalDateTime.now();
        // 第②级换算上下文按批次解析一次（scaleText 为全图级标注）：解析不出可靠关系为 null
        ScaleContext scaleContext = resolveScaleConversion(analysisId, detected.getScaleText());
        int sortOrder = 0;
        for (FloorPlanDetectResult.Room detectedRoom : detected.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(IdGenerator.floorPlanRoomId());
            room.setAnalysisId(analysisId);
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
        LocalDateTime now = LocalDateTime.now();
        CadParseResult.Bounds drawingBounds = cadResult.getDrawingBounds();
        int sortOrder = 0;

        List<CadParseResult.Room> cadRooms = cadResult.getRooms() != null
            ? cadResult.getRooms() : List.of();
        for (CadParseResult.Room cadRoom : cadRooms) {
            FloorPlanRoom room = newCadRoom(analysisId, cadRoom.getPolygon(), drawingBounds,
                cadRoom.getLabelPoint(), now);
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
        }

        List<CadParseResult.UnnamedRegion> unnamedRegions = cadResult.getUnnamedRegions() != null
            ? cadResult.getUnnamedRegions() : List.of();
        int unnamedIndex = 0;
        for (CadParseResult.UnnamedRegion region : unnamedRegions) {
            FloorPlanRoom room = newCadRoom(analysisId, region.getPolygon(), drawingBounds,
                region.getLabelPoint(), now);
            room.setLabel("未命名空间 " + (++unnamedIndex));
            room.setRoomType("OTHER");
            room.setWidthMm(roundMm(region.getWidthMm()));
            room.setDepthMm(roundMm(region.getDepthMm()));
            room.setAreaM2(roundAreaM2(region.getAreaM2()));
            room.setDimensionConfidence(CONFIDENCE_LOW);
            room.setSortOrder(sortOrder++);
            roomMapper.insert(room);
        }
        log.info("CAD 户型空间明细已落库，analysisId={}，房间数={}，未命名空间数={}",
            analysisId, cadRooms.size(), unnamedRegions.size());
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

        String imageId = IdGenerator.imageId();
        String objectKey = "images/cad-preview/" + imageId + ".png";
        String storagePath;
        try {
            storagePath = storageService.store(new ByteArrayInputStream(previewBytes), objectKey,
                previewBytes.length, "image/png");
        } catch (IOException e) {
            log.error("CAD 规范预览存储失败，analysisId={}", analysisId, e);
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

        analysis.setPreviewImageId(imageId);
        analysis.setUpdatedAt(now);
        analysisMapper.updateById(analysis);
        auditLogService.logCreate("image_assets", imageId, asset,
            StringUtils.hasText(analysis.getCreatedBy()) ? analysis.getCreatedBy() : PUBLIC_OPERATOR);
        log.info("CAD 规范预览已存储，analysisId={}，imageId={}，size={} bytes",
            analysisId, imageId, previewBytes.length);
    }

    /** 构造 CAD 通道空间明细公共字段（polygon/centroid/bbox/dimensionSource/geometrySource/时间戳）。 */
    private FloorPlanRoom newCadRoom(String analysisId, List<List<Double>> polygon,
                                     CadParseResult.Bounds drawingBounds,
                                     CadParseResult.Point labelPoint, LocalDateTime now) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(IdGenerator.floorPlanRoomId());
        room.setAnalysisId(analysisId);
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
     * <p>几何来源（V14 列表契约）：批次下任一空间为 cad_geometry 则 cad_geometry，
     * 否则有空间即 ai_vision；批次无空间则不写入 geometrySourceMap（列表项为 null）。</p>
     *
     * @param analysisIds       页内分析批次 ID 列表
     * @param roomCountMap      输出：analysisId → 空间数
     * @param geometrySourceMap 输出：analysisId → 几何来源
     */
    private void batchRoomStats(List<String> analysisIds, Map<String, Long> roomCountMap,
                                Map<String, String> geometrySourceMap) {
        if (analysisIds.isEmpty()) {
            return;
        }
        List<FloorPlanRoom> rooms = roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .select("analysis_id", "geometry_source")
            .in("analysis_id", analysisIds));
        for (FloorPlanRoom room : rooms) {
            roomCountMap.merge(room.getAnalysisId(), 1L, Long::sum);
            if (GEOMETRY_SOURCE_CAD.equals(room.getGeometrySource())) {
                geometrySourceMap.put(room.getAnalysisId(), GEOMETRY_SOURCE_CAD);
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
        return response;
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
