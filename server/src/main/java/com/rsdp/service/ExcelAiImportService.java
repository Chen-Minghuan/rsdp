package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.request.PriceColumnSelection;
import com.rsdp.dto.request.RspuFactoryMappingRequest;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.ExcelAiImportFailure;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
import com.rsdp.dto.response.CategoryMappingItem;
import com.rsdp.dto.response.ExcelSheetInfo;
import com.rsdp.dto.response.PriceColumnInfo;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportRow;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.CategoryPaths;
import com.rsdp.util.ExcelFileValidator;
import com.rsdp.util.ExcelHeaderNormalizer;
import com.rsdp.util.ExcelImageExtractor;
import com.rsdp.util.ImageUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.rsdp.util.IdGenerator;

/**
 * Excel AI 辅助导入服务。
 *
 * <p>支持非标准表头的 Excel 产品目录：AI 自动识别字段映射，
 * 支持图片 URL 与 Excel 内嵌图片，每行生成一个 RSPU + 默认变体 + 异步 AI 识别任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelAiImportService {

    private static final int MAX_ROWS = 500;
    private static final long MAX_FILE_SIZE = 200 * 1024 * 1024; // 200 MB
    private static final int MAX_IMAGE_SIZE = 20 * 1024 * 1024; // 20 MB
    private static final int PREVIEW_ROW_COUNT = 5;
    private static final int MAX_CATEGORY_SUGGESTIONS = 50;
    /** 标题行扫描上限：公司标题行一般不超过 10 行 */
    private static final int MAX_TITLE_SCAN_ROWS = 10;
    /** 真表头行关键词密度阈值：含 ≥2 个表头关键词的行视为表头行 */
    private static final int HEADER_KEYWORD_THRESHOLD = 2;
    /** 价格列角色：出厂价（建变体 + RSKU） */
    private static final String PRICE_ROLE_FACTORY = "factory";
    /** 价格列角色：销售价（写 RSPU 零售参考价，不建变体/RSKU） */
    private static final String PRICE_ROLE_SALES = "sales";

    private static final String MAPPING_SYSTEM_PROMPT = """
        你是家具产品目录结构化专家。用户上传的是工厂报价单 Excel，表头可能包含中英双语、括号单位、多行父子表头。
        我会先对表头做清洗：去掉英文备注、括号及单位、合并多行表头。你收到的表头已经是清洗后的版本。

        标准字段列表及常见中文表头同义词：
        - categoryCode（品类码，如 FS/SF/DT/BS/OF；对应：品类、分类、产品类别）
        - externalCode（外部编码/型号；对应：型号、编码、编号、ITEM NO）
        - productName（产品名称；对应：品名、名称、产品名称、DESCRIPTION）
        - positioningLabel（风格，如中古风/意式/奶油风；对应：风格、定位标签、STYLE）
        - colorPrimaryName（主色；对应：颜色、主色、COLOR、色系）
        - materialTags（材质标签，多个用逗号分隔；对应：材质、材质说明、面料、布料、皮质、MATERIAL）
        - sceneTags（场景，多个用逗号分隔；对应：场景、空间、SCENE、ROOM）
        - productLevel（产品等级，如 S/A/B/C；对应：等级、产品等级、LEVEL、GRADE）
        - warrantyYears（保修年限，数字；对应：保修、保修年限、WARRANTY）
        - referencePriceBand（价格带 low/mid/high；对应：价格带、参考价格带、PRICE、价格）
        - sixDimTags（六维标签 JSON；对应：六维标签、标签、TAGS）
        - keySpecs（关键规格 JSON；对应：规格、关键规格、SPEC、参数）
        - primaryImageUrl（主图 URL；对应：主图、图片、IMAGE、产品图样）
        - detailImageUrls（详情图 URLs，多个用逗号分隔；对应：详情图、附图、DETAIL IMAGES）
        - variantDisplayName（变体显示名称；对应：变体、规格/模块、Modular Components、变体名称）
        - sizeCode（尺寸码，仅限字典码 S/M/L/SINGLE/DOUBLE/TRIPLE；对应：尺寸码、尺码、SIZE CODE。注意：W*D*H 形式的尺寸数值不是尺寸码，必须映射为 dimensions）
        - colorCode（颜色码，仅限字典码；对应：颜色码、COLOR CODE。颜色名称请映射 colorPrimaryName）
        - materialCode（材质码，仅限字典码如 WO/PE/FA；对应：材质码、面料码、MATERIAL CODE。材质名称/说明请映射 materialTags）
        - dimensions（尺寸文字，如 800*900*1000mm；对应：尺寸、产品尺寸、规格（数值尺寸）、SIZE CM、DIMENSIONS）
        - leadTimeDays（交期天数，数字，单位天；对应：交期、货期、生产周期、交货期、PRODUCTION CYCLE、LEAD TIME）
        - description（长文本描述/配置说明原文，原样保留不加工；对应：材质解析、材质说明、功能配置、配置说明、DISPOSE）
        - retailPrice（零售参考价，数字；对应：销售价、含税价、零售价、市场价）

        复合表头处理规则：
        - 「型号品名」这类同时包含型号和名称的列，请映射为 "externalCode,productName"（用英文逗号分隔）。
        - 「规格/模块」映射为 variantDisplayName。
        - 「产品尺寸」及一切 W*D*H 数值尺寸列映射为 dimensions，严禁映射为 sizeCode。

        价格列处理规则（非常重要）：
        - 清洗后的表头可能是「价格-A级布」「价格-AA级布」「价格-S级布」「价格-半皮」等父子表头。
        - 每一列价格都对应一个材质版本的变体，请把这种列映射为特殊字段："__PRICE__:材质名"。
        - 例如：「价格-A级布」→ "__PRICE__:A级布"；「价格-半皮」→ "__PRICE__:半皮"。
        - 不要把价格列映射为 referencePriceBand。

        无需映射的列：
        - 产品图样/PICTURE/图片/IMAGE 等含图片列：value 填 null，系统会自动提取内嵌图片。

        输出 JSON：
        {
          "mapping": {"清洗后表头1": "标准字段", "清洗后表头2": "标准字段"},
          "categoryGuess": "FS",
          "notes": "说明"
        }
        约束：
        - 只能使用上面列出的标准字段作为 value；复合映射用英文逗号分隔多个字段。
        - 无法映射或无需映射的表头 value 填 null。
        - 不要输出任何其他文字。
        """;

    private final ExcelImportBatchMapper batchMapper;
    private final VisionService visionService;
    private final RspuMapper rspuMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final RspuVariantService rspuVariantService;
    private final RskuService rskuService;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final AsyncTaskProcessor asyncTaskProcessor;
    private final StorageService storageService;
    private final DictService dictService;
    private final AuditLogService auditLogService;
    private final VariantCodeMapper variantCodeMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final RspuFactoryMappingService rspuFactoryMappingService;
    private final FactoryLeadTimeRuleService factoryLeadTimeRuleService;
    private final ExcelImportRowService excelImportRowService;
    private final DictResolverService dictResolverService;
    private final DictAliasService dictAliasService;
    private final DictUnresolvedService dictUnresolvedService;

    @Value("${rsdp.import.allowed-image-hosts:}")
    private Set<String> allowedImageHosts = Set.of();

    @Value("${rsdp.excel-import.max-total-image-mb:500}")
    private int maxTotalImageMb = 500;

    /**
     * 上传 Excel 并请求 AI 生成字段映射预览（默认解析第一个工作表）。
     *
     * @param file Excel 文件
     * @return 映射预览响应
     */
    public ExcelAiMappingResponse previewMapping(MultipartFile file) {
        return previewMapping(file, 0);
    }

    /**
     * 上传 Excel 并请求 AI 生成字段映射预览。
     *
     * <p>不使用方法级事务：AI 调用与文件存储耗时较长，长事务会占用数据库连接；
     * 仅批次记录的 DB 写入（{@link #saveBatch}）使用编程式事务。</p>
     *
     * @param file       Excel 文件
     * @param sheetIndex 待解析的工作表索引（0-based，多 Sheet 文件逐一导入用）
     * @return 映射预览响应
     */
    public ExcelAiMappingResponse previewMapping(MultipartFile file, int sheetIndex) {
        validateFile(file);

        byte[] fileBytes = readFileBytes(file);
        List<ExcelSheetInfo> sheets = listSheets(fileBytes, file.getOriginalFilename());
        if (sheetIndex < 0 || sheetIndex >= sheets.size()) {
            throw new BusinessException("工作表不存在: sheetIndex=" + sheetIndex);
        }
        List<IndexedRow> rawRows = parseExcelRaw(fileBytes, sheetIndex);
        if (rawRows.isEmpty()) {
            throw new BusinessException("Excel 文件为空");
        }

        // 定位真表头行（跳过公司标题行），识别单行/双行表头与英文对照副表头行
        List<Map<Integer, String>> rawValues = valuesOf(rawRows);
        HeaderLayout headerLayout = detectHeaderLayout(rawValues);
        // 数据行数上限按实际表头行数判断，不再写死单行表头（P2-5 off-by-one）
        if (rawRows.size() - headerLayout.dataStartIndex() > MAX_ROWS) {
            throw new BusinessException("单次导入不能超过 " + MAX_ROWS + " 行数据");
        }
        List<Map<Integer, String>> headerRows = headerLayout.mergeSubHeader()
            ? rawValues.subList(headerLayout.headerStartIndex(), headerLayout.dataStartIndex())
            : List.of(rawValues.get(headerLayout.headerStartIndex()));
        List<Map<Integer, String>> dataRows = rawValues.subList(headerLayout.dataStartIndex(), rawRows.size());

        // 同名表头自动加消歧后缀（如两个「价格」→「价格」「价格#2」），
        // 避免以表头为 key 时同名列互相覆盖、前一列数据静默丢失（P2-11）
        Map<Integer, String> mergedHeaders = ExcelHeaderNormalizer.disambiguateDuplicateHeaders(
            ExcelHeaderNormalizer.mergeHeaderRows(headerRows));
        Map<Integer, String> cleanHeaders = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : mergedHeaders.entrySet()) {
            cleanHeaders.put(entry.getKey(), ExcelHeaderNormalizer.clean(entry.getValue()));
        }

        String headersText = buildHeadersText(cleanHeaders);
        String previewText = buildPreviewText(mergedHeaders, dataRows);
        String imageHint = "如果 Excel 包含内嵌图片，主图无需映射，系统会自动将每行第一个内嵌图片作为主图。";

        String aiResponse = visionService.chatText(MAPPING_SYSTEM_PROMPT,
            "表头：\n" + headersText + "\n\n样例数据（前 " + Math.min(dataRows.size(), PREVIEW_ROW_COUNT) + " 行）：\n"
                + previewText + "\n\n" + imageHint);

        AiMappingResult mappingResult = parseMappingResponse(aiResponse);
        ProcessedMapping processed = postProcessMapping(mappingResult.mapping, cleanHeaders, mergedHeaders);
        mappingResult.mapping = processed.mapping();
        mappingResult.priceColumns = processed.priceColumns();

        String storagePath = storeOriginalFile(fileBytes, file.getOriginalFilename());
        ExcelImportBatch batch = saveBatch(file.getOriginalFilename(), storagePath, mappingResult,
            mergedHeaders, dataRows, sheetIndex);

        ExcelAiMappingResponse response = buildMappingResponse(batch, mergedHeaders, mappingResult);
        response.setSheetIndex(sheetIndex);
        response.setSheets(sheets);
        // 品类中文名映射建议：字典/别名命中 + AI 批量归一（用户确认后写回别名库自学习）；
        // 工作表名作为品类线索注入（无品类列的多 Sheet 文件常按品类分 sheet）
        response.setCategoryMappings(suggestCategoryMappings(mappingResult.mapping, mergedHeaders, dataRows,
            sheets.get(sheetIndex).getName()));
        return response;
    }

    /**
     * 确认字段映射并执行导入。
     *
     * @param request 映射确认请求
     * @return 导入结果
     */
    public ExcelAiImportResult confirmAndImport(ExcelAiMappingRequest request) {
        ExcelImportBatch batch = batchMapper.selectById(request.getBatchId());
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + request.getBatchId());
        }
        // 能在抢占前完成的前置校验全部前置：校验失败不应把批次推进 importing（P1-1）
        Map<String, String> mapping = sanitizeMapping(request.getMapping());
        if (mapping == null || mapping.isEmpty()) {
            throw new BusinessException("字段映射不能为空");
        }
        // 原子抢占导入权，防止并发重复导入（替代先查状态再判断的 check-then-act 竞态）；
        // pending / done 均可抢占（done 批次支持「以更新模式重新导入」），importing 拒绝
        if (batchMapper.claimForImport(batch.getBatchId()) == 0) {
            throw new BusinessException("批次正在导入中，请稍后重试: " + batch.getStatus());
        }
        batch.setStatus("importing");
        try {
            return doConfirmImport(batch, request, mapping);
        } catch (RuntimeException | Error e) {
            // 抢占成功后任何异常都必须把批次复位为 pending，否则批次永久卡死 importing、
            // 用户无法重试（claimForImport 只认 pending/done）；复位本身容错，不掩盖原始异常
            resetBatchToPendingQuietly(batch);
            throw e;
        }
    }

    /**
     * 复位批次状态为 pending（导入主流程异常时调用）。复位失败只记日志，不再抛出。
     *
     * @param batch 导入批次
     */
    private void resetBatchToPendingQuietly(ExcelImportBatch batch) {
        try {
            batchMapper.resetToPending(batch.getBatchId());
            batch.setStatus("pending");
        } catch (Exception resetError) {
            log.error("复位导入批次状态失败，batchId={}", batch.getBatchId(), resetError);
        }
    }

    /**
     * 确认导入主流程（批次已抢占为 importing）。
     *
     * @param batch   导入批次
     * @param request 映射确认请求
     * @param mapping 已清洗的字段映射（表头 → 标准字段）
     * @return 导入结果
     */
    private ExcelAiImportResult doConfirmImport(ExcelImportBatch batch, ExcelAiMappingRequest request,
                                                Map<String, String> mapping) {
        List<Map<String, String>> rawDataRows = loadRawDataRows(batch);
        if (rawDataRows.isEmpty()) {
            throw new BusinessException("Excel 数据为空，无法导入");
        }

        // 批次 sheet 上下文：历史批次 sheet_index 为 null 按 0 处理
        int sheetIndex = batch.getSheetIndex() != null ? batch.getSheetIndex() : 0;
        // 预览时暂存的 AI 品类猜测（category_hint 列），在 applyBatchFactoryInfo 覆盖前捕获，
        // 品类兜底链末位（行类别列 > sheet 名 > 用户品类提示 > categoryGuess）
        String categoryGuess = batch.getCategoryHint();

        // 重新导入（done 批次）前清理上一轮行级结果记录：整体删除比逐行覆盖简单安全，
        // 行记录会按同一批物理行号重建（pending 批次无旧记录，删除为空操作）
        excelImportRowService.deleteByBatch(batch.getBatchId());

        // 型号/品名列向下填充（纵向合并单元格语义，模块行继承上行型号）
        forwardFillKeyColumns(rawDataRows, mapping);

        List<PriceColumnInfo> priceColumns = loadPriceColumns(batch);
        List<PriceColumnInfo> selectedPriceColumns = resolveSelectedPriceColumns(priceColumns, request);

        // 从 storage 读取原始 Excel，提取内嵌图片并重建数据行物理布局（图片锚点行/列对齐用）
        EmbeddedImagesResult embeddedImagesResult = loadEmbeddedImages(batch);
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages = embeddedImagesResult.images();
        PhysicalLayout physicalLayout = loadPhysicalLayout(batch);
        // 工作表名（品类线索）：优先取物理布局重建结果，失败时无 sheet 名兜底
        String sheetName = physicalLayout != null ? physicalLayout.sheetName() : null;

        Map<String, List<CategoryDict>> dictCache = preloadDicts();
        ExcelAiImportResult result = new ExcelAiImportResult();
        result.setBatchId(batch.getBatchId());
        result.setTotalRows(rawDataRows.size());

        List<ExcelAiImportFailure> failures = new ArrayList<>();
        // 图片提取整体失败/截断对用户可见（不再只写日志）；截断原因区分总字节超限与总张数超限
        if (embeddedImagesResult.errorMessage() != null) {
            failures.add(new ExcelAiImportFailure(0, "图片提取失败：" + embeddedImagesResult.errorMessage()));
        } else if (embeddedImagesResult.truncated()) {
            failures.add(new ExcelAiImportFailure(0,
                "部分图片未提取：" + embeddedImagesResult.truncationReason() + "，已保留前部分图片"));
        }
        List<String> rspuIds = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();
        List<ExcelAiImportResult.TaskLink> tasks = new ArrayList<>();
        int skippedCount = 0;
        int failedRowCount = 0;

        // 保存批次级工厂/发货地信息
        applyBatchFactoryInfo(batch, request);

        int rowIndex = 1; // 第 1 行为表头
        int dataRowOrdinal = 0;
        ProductGroup currentGroup = null;
        for (Map<String, String> dataRow : rawDataRows) {
            rowIndex++;
            // 数据行物理行号：优先取重建结果（与 previewRows 顺序一一对应），缺失回退旧换算
            Integer physicalRowIndex =
                physicalLayout != null && dataRowOrdinal < physicalLayout.dataRowPhysicalIndexes().size()
                    ? physicalLayout.dataRowPhysicalIndexes().get(dataRowOrdinal)
                    : null;
            dataRowOrdinal++;
            // 失败明细使用 Excel 物理行号（0-based +1），缺失时回退旧的序号换算
            int displayRowIndex = physicalRowIndex != null ? physicalRowIndex + 1 : rowIndex;
            Long importRowId = null;
            try {
                // 行级记录与失败明细统一使用 Excel 物理行号（+1 转 1-based 展示口径）；
                // 物理行号单调递增，不撞 (batch_id, excel_row_number) 唯一约束（P2-4）
                importRowId = excelImportRowService.initRow(batch.getBatchId(), displayRowIndex, "product", dataRow, null);
                RowResult rowResult = processRowInTransaction(dataRow, mapping, request.getCategoryHint(),
                    selectedPriceColumns, request, embeddedImages, dictCache, rowIndex, importRowId, physicalRowIndex,
                    currentGroup, physicalLayout, sheetIndex, sheetName, categoryGuess, batch.getBatchId());
                if (rowResult.rspuId != null) {
                    rspuIds.add(rowResult.rspuId);
                    excelImportRowService.markSuccess(importRowId, rowResult.rspuId, rowResult.variantId,
                        rowResult.rskuIds, rowResult.imageCount, rowResult.imageAssetIds, rowResult.taskId);
                    // 行内部分失败（如某价格列 RSKU 创建失败）不吞掉：记入批次失败明细，用户可见
                    for (String issue : rowResult.issues) {
                        failures.add(new ExcelAiImportFailure(displayRowIndex, issue));
                    }
                    // 行提交成功后更新产品组状态（回滚行不影响组）
                    if (rowResult.createdNewRspu) {
                        currentGroup = new ProductGroup();
                        currentGroup.externalCode = rowResult.groupKey;
                        currentGroup.rspuId = rowResult.rspuId;
                        currentGroup.hasPrimaryImage = rowResult.primaryImageSaved;
                        currentGroup.hasAiTask = rowResult.taskId != null;
                    } else if (currentGroup != null) {
                        currentGroup.hasPrimaryImage |= rowResult.primaryImageSaved;
                        currentGroup.hasAiTask |= rowResult.taskId != null;
                    }
                } else if (rowResult.skipped) {
                    skippedCount++;
                    excelImportRowService.markSkipped(importRowId, rowResult.skipReason);
                }
                if (rowResult.taskId != null) {
                    taskIds.add(rowResult.taskId);
                    if (rowResult.rspuId != null) {
                        tasks.add(new ExcelAiImportResult.TaskLink(rowResult.taskId, rowResult.rspuId));
                    }
                }
            } catch (BusinessException e) {
                failedRowCount++;
                failures.add(new ExcelAiImportFailure(displayRowIndex, e.getMessage()));
                if (importRowId != null) {
                    excelImportRowService.markFailed(importRowId, "validate_or_create", e.getMessage());
                }
            } catch (Exception e) {
                failedRowCount++;
                log.error("Excel AI 导入行处理异常，rowIndex={}", rowIndex, e);
                failures.add(new ExcelAiImportFailure(displayRowIndex, "系统异常: " + e.getMessage()));
                if (importRowId != null) {
                    excelImportRowService.markFailed(importRowId, "system", e.getMessage());
                }
            }
        }

        // 口径说明（评估后保持现状）：rspuIds 按「成功处理行」逐行记录，同组模块行会重复同一 RSPU ID，
        // 因此 successCount 语义 = 成功处理行数（与 skippedCount/failedCount 对齐、与前端「成功 N 行」展示一致），
        // 而非去重后的新建产品数；tasks 配对独立构建，不依赖 rspuIds 的重复项，无需去重。
        result.setSuccessCount(rspuIds.size());
        result.setFailedCount(failedRowCount);
        result.setSkippedCount(skippedCount);
        result.setRspuIds(rspuIds);
        result.setTaskIds(taskIds);
        result.setTasks(tasks);
        result.setFailures(failures);

        updateBatchResult(batch, request, result);
        // 别名自学习：用户确认的品类映射写回别名库，后续导入直接命中，不再调 AI
        learnCategoryAliases(request);
        return result;
    }

    /**
     * 查询导入批次状态。
     */
    public ExcelAiImportStatusResponse getStatus(String batchId) {
        ExcelImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + batchId);
        }
        ExcelAiImportStatusResponse response = new ExcelAiImportStatusResponse();
        response.setBatchId(batch.getBatchId());
        response.setFileName(batch.getFileName());
        response.setStatus(batch.getStatus());
        response.setTotalRows(batch.getTotalRows());
        response.setSuccessCount(batch.getSuccessCount());
        response.setFailedCount(batch.getFailedCount());
        response.setCreatedAt(batch.getCreatedAt());
        response.setUpdatedAt(batch.getUpdatedAt());
        if (StringUtils.hasText(batch.getFailures())) {
            try {
                List<ExcelAiImportFailure> failures = objectMapper.readValue(batch.getFailures(),
                    new TypeReference<List<ExcelAiImportFailure>>() {
                    });
                response.setFailures(failures);
            } catch (Exception e) {
                log.warn("解析批次失败明细失败，batchId={}", batchId, e);
            }
        }
        // 从行级记录聚合识别任务列表与跳过行数：前端超时恢复后可凭 tasks 恢复轮询（P2-15）
        List<ExcelImportRow> rows = excelImportRowService.listByBatch(batchId);
        if (rows == null) {
            rows = List.of();
        }
        List<ExcelAiImportResult.TaskLink> tasks = new ArrayList<>();
        int skippedCount = 0;
        for (ExcelImportRow row : rows) {
            if (StringUtils.hasText(row.getAiTaskId()) && StringUtils.hasText(row.getGeneratedRspuId())) {
                tasks.add(new ExcelAiImportResult.TaskLink(row.getAiTaskId(), row.getGeneratedRspuId()));
            }
            if ("skipped".equals(row.getStatus())) {
                skippedCount++;
            }
        }
        response.setTasks(tasks);
        response.setSkippedCount(skippedCount);
        return response;
    }

    /**
     * 查询导入批次并校验归属：仅批次创建者本人或平台 ADMIN 可访问。
     *
     * @param batchId 批次 ID
     * @return 批次实体
     * @throws BusinessException  批次不存在
     * @throws ForbiddenException 无权访问该批次
     */
    public ExcelImportBatch getAccessibleBatch(String batchId) {
        ExcelImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + batchId);
        }
        if (SecurityOperatorContext.isCurrentUserAdmin()) {
            return batch;
        }
        // createdBy 为 null 的历史批次无法确认归属，仅 ADMIN 可访问（P2-7）
        if (batch.getCreatedBy() == null
            || !batch.getCreatedBy().equals(SecurityOperatorContext.currentUserId())) {
            throw new ForbiddenException("无权访问该导入批次: " + batchId);
        }
        return batch;
    }

    // ==================== 私有方法 ====================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
        }
        if (!ExcelFileValidator.isExcelOrCsv(file)) {
            throw new BusinessException("仅支持 Excel (.xlsx/.xls) 或 CSV 文件");
        }
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException("读取上传文件失败");
        }
    }

    /**
     * 解析 Excel 全部非空行，并保留每行的物理行号（0-based）。
     *
     * <p>物理行号用于将 EasyExcel 行与 POI 图片锚点行精确对齐——真实工厂表格
     * 常见双行表头/标题行/中间空行，不能用「数据序号 + 1」反推物理行号。</p>
     *
     * @param fileBytes  Excel 文件字节
     * @param sheetIndex 待解析的工作表索引（0-based）
     * @return 非空行列表（含物理行号）
     */
    private List<IndexedRow> parseExcelRaw(byte[] fileBytes, int sheetIndex) {
        List<IndexedRow> rows = new ArrayList<>();
        try (InputStream stream = new ByteArrayInputStream(fileBytes)) {
            EasyExcel.read(stream, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> row, AnalysisContext context) {
                    Integer rowIndex = context.readRowHolder().getRowIndex();
                    rows.add(new IndexedRow(rowIndex != null ? rowIndex : rows.size(), row));
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            }).sheet(sheetIndex).headRowNumber(0).doRead();
        } catch (Exception e) {
            log.error("解析 Excel 失败", e);
            throw new BusinessException("解析 Excel 失败，请检查文件格式");
        }
        return rows;
    }

    /**
     * 提取行的数据部分（丢弃物理行号）。
     *
     * @param rows 含物理行号的行列表
     * @return 数据行列表
     */
    private List<Map<Integer, String>> valuesOf(List<IndexedRow> rows) {
        return rows.stream().map(IndexedRow::values).toList();
    }

    /**
     * Excel 非空行（含物理行号，0-based）。
     */
    record IndexedRow(int physicalRowIndex, Map<Integer, String> values) {
    }

    private String buildHeadersText(Map<Integer, String> headerMap) {
        return headerMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> "列" + e.getKey() + ": " + (e.getValue() != null ? e.getValue() : "未命名列"))
            .collect(Collectors.joining("\n"));
    }

    private String buildPreviewText(Map<Integer, String> headerMap, List<Map<Integer, String>> dataRows) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(dataRows.size(), PREVIEW_ROW_COUNT);
        for (int i = 0; i < limit; i++) {
            sb.append("行").append(i + 2).append(":\n");
            Map<Integer, String> row = dataRows.get(i);
            for (Map.Entry<Integer, String> entry : headerMap.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                String header = entry.getValue() != null ? entry.getValue() : "未命名列";
                sb.append("  ").append(header).append("=").append(row.getOrDefault(entry.getKey(), "")).append("\n");
            }
        }
        return sb.toString();
    }

    private AiMappingResult parseMappingResponse(String aiResponse) {
        if (!StringUtils.hasText(aiResponse)) {
            throw new ExternalServiceException("AI 映射返回为空");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(aiResponse, new TypeReference<>() {
            });
            AiMappingResult result = new AiMappingResult();
            Object mappingObj = map.get("mapping");
            if (mappingObj instanceof Map<?, ?> rawMapping) {
                Map<String, String> mapping = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
                    String key = entry.getKey() != null ? entry.getKey().toString() : null;
                    String value = entry.getValue() != null ? entry.getValue().toString() : null;
                    if (key != null) {
                        mapping.put(key, value);
                    }
                }
                result.mapping = mapping;
            }
            Object categoryGuess = map.get("categoryGuess");
            result.categoryGuess = categoryGuess != null ? categoryGuess.toString() : null;
            Object notes = map.get("notes");
            result.notes = notes != null ? notes.toString() : null;
            return result;
        } catch (Exception e) {
            log.error("解析 AI 映射响应失败，response={}", aiResponse, e);
            throw new BusinessException("解析 AI 字段映射失败，请重试");
        }
    }

    /**
     * 定位表头布局：关键词密度扫描定位真表头行（跳过公司标题行），
     * 再区分单行表头 / 中文父子双行表头 / 中文表头 + 英文对照副表头行。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>自首行起扫描，含 ≥2 个表头关键词（型号/价格/图片/序号/类别/ITEM/NO 等）的行为真表头行，
     *       其前的低密度行视为公司标题行跳过；无命中时回退首行（兼容既有行为）。</li>
     *   <li>表头行下一行为英文对照行（整行 ASCII 为主且与表头列对齐，如 SERIAL/PICTURE/SORT）时：
     *       中文行作为唯一表头（不做父子合并），英文行跳过不进数据区。</li>
     *   <li>表头行下一行为中文子表头（如 A级布/半皮）时：保持既有父子合并（价格-A级布）。</li>
     * </ul>
     *
     * @param rawValues 全部非空行（按物理顺序）
     * @return 表头布局
     */
    private HeaderLayout detectHeaderLayout(List<Map<Integer, String>> rawValues) {
        if (rawValues.isEmpty()) {
            return new HeaderLayout(0, 0, true);
        }
        // 1. 关键词密度扫描：定位真表头行，跳过公司标题/说明行
        int headerStart = 0;
        int scanLimit = Math.min(rawValues.size(), MAX_TITLE_SCAN_ROWS);
        for (int i = 0; i < scanLimit; i++) {
            if (ExcelHeaderNormalizer.countHeaderKeywordHits(rawValues.get(i)) >= HEADER_KEYWORD_THRESHOLD) {
                headerStart = i;
                break;
            }
        }
        // 2. 双行表头判定：英文对照副表头行（跳过不合并）优先于中文子表头（父子合并）
        Map<Integer, String> headerRow = rawValues.get(headerStart);
        if (headerStart + 1 < rawValues.size()) {
            Map<Integer, String> nextRow = rawValues.get(headerStart + 1);
            if (ExcelHeaderNormalizer.looksLikeEnglishMirrorRow(nextRow, headerRow)) {
                return new HeaderLayout(headerStart, 2, false);
            }
            if (ExcelHeaderNormalizer.looksLikeHeaderRow(nextRow)) {
                return new HeaderLayout(headerStart, 2, true);
            }
        }
        return new HeaderLayout(headerStart, 1, true);
    }

    /**
     * 表头布局：真表头行位置 + 表头行数 + 是否父子合并。
     *
     * @param headerStartIndex 真表头行在原始行列表中的下标（其前为公司标题行）
     * @param headerRowCount   表头占用行数（英文对照副表头行计入，数据从下标 headerStartIndex+headerRowCount 开始）
     * @param mergeSubHeader   是否父子合并双行表头（英文对照副表头场景为 false，中文行为唯一表头）
     */
    private record HeaderLayout(int headerStartIndex, int headerRowCount, boolean mergeSubHeader) {
        /**
         * 数据区起始下标（原始行列表）。
         */
        int dataStartIndex() {
            return headerStartIndex + headerRowCount;
        }
    }

    /**
     * 枚举工作簿全部工作表（名称 + 近似行数）。CSV 等非 Excel 内容回退单工作表。
     *
     * @param fileBytes  文件字节
     * @param fileName   原始文件名（日志用）
     * @return 工作表列表（至少含一个元素）
     */
    private List<ExcelSheetInfo> listSheets(byte[] fileBytes, String fileName) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            List<ExcelSheetInfo> sheets = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                // lastRowNum + 1 为物理行数上限（含表头/标题行），作为近似行数即可
                sheets.add(new ExcelSheetInfo(i, workbook.getSheetName(i),
                    workbook.getSheetAt(i).getLastRowNum() + 1));
            }
            return sheets.isEmpty() ? List.of(new ExcelSheetInfo(0, "Sheet1", 0)) : sheets;
        } catch (Exception e) {
            // CSV 等非 POI 可解析内容：无工作表概念，按单表处理
            log.debug("枚举工作表失败（按单工作表处理），file={}", fileName, e);
            return List.of(new ExcelSheetInfo(0, "Sheet1", 0));
        }
    }

    private ProcessedMapping postProcessMapping(Map<String, String> aiMapping,
                                                  Map<Integer, String> cleanHeaders,
                                                  Map<Integer, String> mergedHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        List<PriceColumnInfo> priceColumns = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : cleanHeaders.entrySet()) {
            Integer index = entry.getKey();
            String cleanHeader = entry.getValue();
            String mergedHeader = mergedHeaders.get(index);
            if (!StringUtils.hasText(mergedHeader)) {
                continue;
            }
            String standardField = aiMapping.get(cleanHeader);
            if (!StringUtils.hasText(standardField)) {
                standardField = fuzzyMatchStandardField(cleanHeader);
            }
            if (!StringUtils.hasText(standardField)) {
                continue;
            }

            // 丢弃图片列被 AI 误映射的 URL 字段：含内嵌图片的列应走图片提取，不要尝试当 URL 下载
            if (isImageUrlField(standardField) && isEmbeddedImageHeader(mergedHeader)) {
                continue;
            }

            // 识别价格列特殊字段：__PRICE__:材质名
            if (standardField.startsWith("__PRICE__:")) {
                String materialName = standardField.substring("__PRICE__:".length()).trim();
                if (!StringUtils.hasText(materialName)) {
                    // 从合并表头兜底提取材质名
                    materialName = extractMaterialNameFromPriceHeader(mergedHeader);
                }
                PriceColumnInfo info = new PriceColumnInfo();
                info.setHeader(mergedHeader);
                info.setMaterialName(materialName);
                info.setSuggestedField(standardField);
                info.setRole(resolvePriceRole(mergedHeader));
                priceColumns.add(info);
                continue;
            }

            result.put(mergedHeader, standardField);
        }
        return new ProcessedMapping(result, priceColumns);
    }

    /**
     * 按表头关键词识别价格列角色：出厂价/工厂价/EXW → factory；
     * 销售价/含税价/零售价/市场价 → sales；其余默认 factory。
     *
     * @param header 价格列原始表头
     * @return "factory" | "sales"
     */
    private String resolvePriceRole(String header) {
        if (!StringUtils.hasText(header)) {
            return PRICE_ROLE_FACTORY;
        }
        String h = header.toLowerCase();
        if (h.contains("销售价") || h.contains("含税价") || h.contains("零售价") || h.contains("市场价")) {
            return PRICE_ROLE_SALES;
        }
        return PRICE_ROLE_FACTORY;
    }

    /**
     * 读取价格列角色（null 安全）：历史批次/旧前端无角色信息时按 factory 处理。
     *
     * @param priceColumn 价格列信息
     * @return "factory" | "sales"
     */
    private String roleOf(PriceColumnInfo priceColumn) {
        return PRICE_ROLE_SALES.equals(priceColumn.getRole()) ? PRICE_ROLE_SALES : PRICE_ROLE_FACTORY;
    }

    private String extractMaterialNameFromPriceHeader(String header) {
        if (!StringUtils.hasText(header)) {
            return header;
        }
        String cleaned = ExcelHeaderNormalizer.clean(header);
        if (cleaned.startsWith("价格")) {
            String suffix = cleaned.substring(2).trim();
            if (suffix.startsWith("-") || suffix.startsWith("/") || suffix.startsWith(" ")) {
                suffix = suffix.substring(1).trim();
            }
            return StringUtils.hasText(suffix) ? suffix : cleaned;
        }
        return cleaned;
    }

    private boolean isImageUrlField(String standardField) {
        return "primaryImageUrl".equals(standardField) || "detailImageUrls".equals(standardField);
    }

    /**
     * 判断原始表头是否明显是 Excel 内嵌图片列（而非 URL 图片列）。
     */
    private boolean isEmbeddedImageHeader(String header) {
        if (!StringUtils.hasText(header)) {
            return false;
        }
        String h = header.toLowerCase();
        return h.contains("图样") || h.contains("picture") || h.contains("image")
            || h.contains("photo") || h.contains("产品图")
            || h.contains("图片") || h.contains("照片") || h.contains("效果图");
    }

    /**
     * 判断一行是否是说明/备注行或完全空行，应跳过不导入。
     */
    private boolean isNoteOrEmptyRow(Map<String, String> dataRow) {
        if (dataRow == null || dataRow.isEmpty()) {
            return true;
        }
        boolean hasAnyValue = false;
        for (String value : dataRow.values()) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            hasAnyValue = true;
            String v = value.trim();
            if (v.contains("产品下单说明") || v.contains("注意事项") || v.contains("温馨提示")
                || v.startsWith("由于") || v.contains("特别声明") || v.contains("免责声明")
                || v.contains("更新日期") || v.contains("修订日期") || v.startsWith("更新：")) {
                return true;
            }
        }
        return !hasAnyValue;
    }

    /**
     * 判断一行是否是「重复表头行」：工厂 Excel 常在每个品类区块前重复一次表头，
     * 此类行的单元格值与列名高度自指（如「价格-A级布」列的值为「A级布」、
     * 「型号品名」列的值为「型号品名」），而非真实产品数据。
     *
     * @param dataRow 数据行（表头 → 值）
     * @return 多数非空值均与各自列名自指时为 true
     */
    private boolean isRepeatedHeaderRow(Map<String, String> dataRow) {
        if (dataRow == null || dataRow.isEmpty()) {
            return false;
        }
        int nonBlank = 0;
        int selfRef = 0;
        for (Map.Entry<String, String> entry : dataRow.entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            String value = ExcelHeaderNormalizer.clean(entry.getValue());
            if (!StringUtils.hasText(value) || value.length() < 2) {
                continue;
            }
            nonBlank++;
            String header = ExcelHeaderNormalizer.clean(entry.getKey());
            if (StringUtils.hasText(header) && header.contains(value)) {
                selfRef++;
            }
        }
        return nonBlank >= 2 && selfRef >= 2 && selfRef * 2 >= nonBlank;
    }

    /**
     * 型号/品名列向下填充（纵向合并单元格语义）。
     *
     * <p>工厂 Excel 常把「型号品名」做成纵向合并单元格：同一产品的多个模块行
     * 只有首行有型号，后续行该列为空。导入前把映射到 externalCode/productName
     * 的列向下填充，让模块行继承所属产品的型号品名。重复表头行不参与填充，
     * 避免表头文本污染。品类列（categoryCode）同样按合并单元格语义向下填充，
     * 修复合并「类别」单元格的后续行品类为空导致校验失败的问题。</p>
     *
     * @param dataRows 数据行列表（就地修改）
     * @param mapping  确认后的字段映射（表头 → 标准字段）
     */
    private void forwardFillKeyColumns(List<Map<String, String>> dataRows, Map<String, String> mapping) {
        List<String> fillHeaders = mapping.entrySet().stream()
            .filter(e -> {
                String field = e.getValue();
                return field != null && (field.contains("externalCode") || field.contains("productName")
                    || field.contains("categoryCode"));
            })
            .map(Map.Entry::getKey)
            .toList();
        if (fillHeaders.isEmpty()) {
            return;
        }
        Map<String, String> lastValues = new HashMap<>();
        for (Map<String, String> row : dataRows) {
            if (isRepeatedHeaderRow(row)) {
                continue;
            }
            for (String header : fillHeaders) {
                String value = row.get(header);
                if (StringUtils.hasText(value)) {
                    lastValues.put(header, value);
                } else {
                    String last = lastValues.get(header);
                    if (last != null) {
                        row.put(header, last);
                    }
                }
            }
        }
    }

    private Map<String, String> sanitizeMapping(Map<String, String> mapping) {
        if (mapping == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (isImageUrlField(entry.getValue()) && isEmbeddedImageHeader(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String fuzzyMatchStandardField(String cleanHeader) {
        String h = cleanHeader.toLowerCase();
        if (h.contains("型号") && h.contains("品名")) {
            return "externalCode,productName";
        }
        if (h.contains("型号") || h.contains("编码") || h.contains("编号") || h.contains("item no")) {
            return "externalCode";
        }
        if (h.contains("品名") || h.contains("名称") || h.contains("产品名") || h.contains("description")) {
            return "productName";
        }
        if (h.contains("品类") || h.contains("分类") || h.contains("category")) {
            return "categoryCode";
        }
        if (h.contains("风格") || h.contains("style")) {
            return "positioningLabel";
        }
        // 码类窄匹配必须先于颜色/尺寸宽匹配，否则「颜色码/尺寸码/size code/color code」
        // 会被宽匹配截获成主色/尺寸文字，窄匹配分支成为死代码（P1-3）
        if (h.contains("颜色码") || h.contains("color code")) {
            return "colorCode";
        }
        if (h.contains("尺寸码") || h.contains("尺码") || h.contains("size code")) {
            return "sizeCode";
        }
        if (h.contains("主色") || h.contains("颜色") || h.contains("color")) {
            return "colorPrimaryName";
        }
        if (h.contains("材质码") || h.contains("面料码") || h.contains("material code")) {
            return "materialCode";
        }
        if (h.contains("材质") || h.contains("面料") || h.contains("布料") || h.contains("皮质") || h.contains("material")) {
            return "materialTags";
        }
        if (h.contains("场景") || h.contains("空间") || h.contains("scene") || h.contains("room")) {
            return "sceneTags";
        }
        // 长文本描述类表头：原文写 rspu_master.description；
        // 必须先于「材质」宽匹配，否则「材质解析」会被截获为材质标签
        if (h.contains("材质解析") || h.contains("功能配置") || h.contains("配置说明")
            || h.contains("dispose") || h.contains("描述")) {
            return "description";
        }
        // 「规格/模块」类表头优先映射为变体显示名（prompt 约定的标准字段）；
        // 必须先于「规格」→ dimensions 宽匹配，「规格」单独出现时仍走 dimensions（P1-3）
        if (h.contains("变体") || h.contains("模块") || h.contains("module") || h.contains("component")) {
            return "variantDisplayName";
        }
        if (h.contains("尺寸") || h.contains("规格") || h.contains("size") || h.contains("dimension")) {
            return "dimensions";
        }
        if (h.contains("交期") || h.contains("货期") || h.contains("生产周期") || h.contains("交货期")
            || h.contains("lead time") || h.contains("cycle")) {
            return "leadTimeDays";
        }
        if (h.contains("价格带") || h.contains("价格区间") || h.contains("价位")) {
            return "referencePriceBand";
        }
        // 零售参考价类表头（不含「价格/单价」等价格列词）：直接映射 retailPrice 标准字段，
        // 写 rspu_master.retail_price；「销售价」保持价格列通道（双价格列场景由用户选角色，既有行为兼容）
        if (h.contains("含税价") || h.contains("零售价") || h.contains("市场价")) {
            return "retailPrice";
        }
        // 价格类表头走价格列通道（由用户在向导中勾选启用），不要误映射为参考价格带
        if (h.contains("价格") || h.contains("price") || h.contains("出厂价")
            || h.contains("销售价") || h.contains("单价") || h.contains("批发价")) {
            return "__PRICE__:" + cleanHeader;
        }
        if (h.contains("图") || h.contains("picture") || h.contains("image") || h.contains("photo")) {
            return null; // 图片列不映射，由内嵌图片提取处理
        }
        return null;
    }

    private String storeOriginalFile(byte[] fileBytes, String originalFilename) {
        String batchId = IdGenerator.excelBatchId();
        String extension = resolveFileExtension(originalFilename);
        String objectKey = "excel-imports/" + batchId + "." + extension;
        try {
            storageService.store(new ByteArrayInputStream(fileBytes), objectKey, fileBytes.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            return objectKey;
        } catch (IOException e) {
            log.error("保存原始 Excel 文件失败", e);
            throw new BusinessException("保存原始 Excel 文件失败");
        }
    }

    private String resolveFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "xlsx";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private ExcelImportBatch saveBatch(String fileName, String storagePath, AiMappingResult mappingResult,
                                       Map<Integer, String> headerMap, List<Map<Integer, String>> dataRows,
                                       int sheetIndex) {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId(IdGenerator.batchId());
        batch.setFileName(fileName);
        batch.setStoragePath(storagePath);
        batch.setStatus("pending");
        batch.setSheetIndex(sheetIndex);
        batch.setTotalRows(dataRows.size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setCreatedBy(SecurityOperatorContext.currentUserId());
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        // AI 品类猜测暂存 category_hint 列：确认导入时作为行类别列/sheet 名/用户提示之后的最后兜底；
        // 确认时会被请求中的用户品类提示覆盖（applyBatchFactoryInfo），无需新增字段
        if (StringUtils.hasText(mappingResult.categoryGuess)) {
            // category_hint 列宽 VARCHAR(16)，超长截断防御
            String guess = mappingResult.categoryGuess.trim();
            batch.setCategoryHint(guess.length() > 16 ? guess.substring(0, 16) : guess);
        }

        try {
            batch.setColumnMapping(objectMapper.writeValueAsString(mappingResult.mapping));
            batch.setPriceColumns(objectMapper.writeValueAsString(mappingResult.priceColumns));
            List<Map<String, String>> keyedRows = dataRows.stream()
                .map(row -> convertRowToKeyMap(row, headerMap))
                .toList();
            batch.setPreviewRows(objectMapper.writeValueAsString(keyedRows));
            batch.setFailures("[]");
        } catch (Exception e) {
            log.error("序列化批次数据失败", e);
            throw new BusinessException("保存导入批次失败");
        }

        // 仅 DB 写入使用编程式事务（previewMapping 不再持有方法级事务，避免长事务）
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            batchMapper.insert(batch);
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
        return batch;
    }

    private Map<String, String> convertRowToKeyMap(Map<Integer, String> row, Map<Integer, String> headerMap) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : headerMap.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String header = entry.getValue();
            if (!StringUtils.hasText(header)) {
                // 空表头单元格无法作为 JSON key，跳过
                continue;
            }
            String value = row.getOrDefault(entry.getKey(), "");
            result.put(header, value);
        }
        return result;
    }

    private ExcelAiMappingResponse buildMappingResponse(ExcelImportBatch batch, Map<Integer, String> headerMap,
                                                        AiMappingResult mappingResult) {
        ExcelAiMappingResponse response = new ExcelAiMappingResponse();
        response.setBatchId(batch.getBatchId());
        response.setHeaders(headerMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList());
        response.setSuggestedMapping(mappingResult.mapping);
        response.setPriceColumns(mappingResult.priceColumns);
        response.setNotes(mappingResult.notes);

        try {
            List<Map<String, String>> previewRows = objectMapper.readValue(batch.getPreviewRows(),
                new TypeReference<List<Map<String, String>>>() {
                });
            response.setPreviewRows(previewRows.subList(0, Math.min(previewRows.size(), PREVIEW_ROW_COUNT)));
        } catch (Exception e) {
            log.warn("解析预览行失败，batchId={}", batch.getBatchId(), e);
            response.setPreviewRows(new ArrayList<>());
        }
        return response;
    }

    private List<Map<String, String>> loadRawDataRows(ExcelImportBatch batch) {
        if (!StringUtils.hasText(batch.getPreviewRows())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(batch.getPreviewRows(), new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            log.error("读取批次原始数据失败，batchId={}", batch.getBatchId(), e);
            throw new BusinessException("读取批次原始数据失败");
        }
    }

    private List<PriceColumnInfo> loadPriceColumns(ExcelImportBatch batch) {
        if (!StringUtils.hasText(batch.getPriceColumns())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(batch.getPriceColumns(), new TypeReference<List<PriceColumnInfo>>() {
            });
        } catch (Exception e) {
            log.error("读取批次价格列失败，batchId={}", batch.getBatchId(), e);
            return List.of();
        }
    }

    private List<PriceColumnInfo> filterSelectedPriceColumns(List<PriceColumnInfo> all,
                                                             List<String> selectedHeaders) {
        // 前端契约：字段缺省/null = 未提供 → 默认全部；显式空数组 [] = 用户明确不选任何价格列（P2-10）
        if (selectedHeaders == null) {
            return all;
        }
        if (selectedHeaders.isEmpty()) {
            return List.of();
        }
        return all.stream()
            .filter(p -> selectedHeaders.contains(p.getHeader()))
            .toList();
    }

    /**
     * 解析确认请求中的价格列选择（含角色）。
     *
     * <p>优先级：priceColumnSelections（新契约，逐列带 factory/sales 角色）
     * > selectedPriceColumns（旧契约兼容，全部视为 factory）。两者都为 null 时默认全部
     * （保留 preview 识别的角色）；显式空数组 = 不选任何价格列。请求中的未知表头忽略；
     * 角色仅接受 "sales"，其余一律 factory。</p>
     *
     * @param all     批次识别的全部价格列
     * @param request 确认导入请求
     * @return 选中价格列（角色已按请求覆盖）
     */
    private List<PriceColumnInfo> resolveSelectedPriceColumns(List<PriceColumnInfo> all,
                                                              ExcelAiMappingRequest request) {
        if (request.getPriceColumnSelections() != null) {
            if (request.getPriceColumnSelections().isEmpty()) {
                return List.of();
            }
            Map<String, String> roleByHeader = new HashMap<>();
            for (PriceColumnSelection selection : request.getPriceColumnSelections()) {
                if (selection != null && StringUtils.hasText(selection.getHeader())) {
                    roleByHeader.put(selection.getHeader(),
                        PRICE_ROLE_SALES.equals(selection.getRole()) ? PRICE_ROLE_SALES : PRICE_ROLE_FACTORY);
                }
            }
            List<PriceColumnInfo> result = new ArrayList<>();
            for (PriceColumnInfo column : all) {
                String role = roleByHeader.get(column.getHeader());
                if (role == null) {
                    continue;
                }
                column.setRole(role);
                result.add(column);
            }
            return result;
        }
        // 旧契约：selectedPriceColumns 选择的价格列全部视为 factory
        List<PriceColumnInfo> selected = filterSelectedPriceColumns(all, request.getSelectedPriceColumns());
        if (request.getSelectedPriceColumns() != null) {
            selected.forEach(p -> p.setRole(PRICE_ROLE_FACTORY));
        }
        return selected;
    }

    private EmbeddedImagesResult loadEmbeddedImages(ExcelImportBatch batch) {
        if (!StringUtils.hasText(batch.getStoragePath())) {
            return new EmbeddedImagesResult(Collections.emptyMap(), false, null, null);
        }
        try (InputStream in = storageService.get(batch.getStoragePath())) {
            byte[] bytes = in.readAllBytes();
            // CSV 无内嵌图片概念：直接返回空结果，不走 POI 解析，
            // 避免每个 CSV 批次都产生一条虚假的「图片提取失败」批次级失败明细（P2-9）
            if (isCsvContent(bytes, batch.getFileName())) {
                return new EmbeddedImagesResult(Collections.emptyMap(), false, null, null);
            }
            long maxBytes = (long) maxTotalImageMb * 1024 * 1024;
            ExcelImageExtractor.ExtractionResult extraction =
                ExcelImageExtractor.extractWithLimit(new MultipartFileAdapter(bytes, batch.getFileName()), maxBytes);
            return new EmbeddedImagesResult(extraction.images(), extraction.truncated(),
                extraction.truncationReason(), null);
        } catch (Exception e) {
            log.warn("提取 Excel 内嵌图片失败，batchId={}", batch.getBatchId(), e);
            return new EmbeddedImagesResult(Collections.emptyMap(), false, null, e.getMessage());
        }
    }

    /**
     * 判断文件内容是否为 CSV（与 ExcelFileValidator 同思路：按魔数判断，文件名为兜底）。
     * 文件已通过上传校验，非 XLSX（ZIP）/XLS（OLE2）魔数即为 CSV。
     *
     * @param bytes    文件字节
     * @param fileName 原始文件名
     * @return true 表示 CSV（无内嵌图片）
     */
    private boolean isCsvContent(byte[] bytes, String fileName) {
        if (bytes != null && bytes.length >= 4) {
            boolean xlsxMagic = bytes[0] == 'P' && bytes[1] == 'K';
            boolean xlsMagic = (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF;
            return !xlsxMagic && !xlsMagic;
        }
        return fileName != null && fileName.toLowerCase().endsWith(".csv");
    }

    /**
     * 内嵌图片提取结果：图片分组 + 是否截断 + 截断原因（区分总字节/总张数超限）+ 整体失败原因。
     */
    private record EmbeddedImagesResult(Map<String, List<ExcelImageExtractor.EmbeddedImage>> images,
                                        boolean truncated, String truncationReason, String errorMessage) {
    }

    /**
     * 重建导入数据行的物理布局（与 previewRows 顺序一一对应）。
     *
     * <p>与预览时解析同一原始文件（EasyExcel 行序确定，两次解析必然一致），
     * 用真实物理行号把数据行与 POI 图片锚点行精确对齐，根治「表头行数假设」
     * 导致的图片挂错行问题。同时从合并表头提取图片列的真实物理列索引——
     * previewRows 经 jsonb 存储后键序被打乱，不能用于推断列位置。
     * 失败时返回 null，调用方回退旧的序号换算行为。</p>
     *
     * @param batch 导入批次
     * @return 物理布局（数据行物理行号序列 + 图片列索引 + 数据列边界），失败为 null
     */
    private PhysicalLayout loadPhysicalLayout(ExcelImportBatch batch) {
        if (!StringUtils.hasText(batch.getStoragePath())) {
            return null;
        }
        int sheetIndex = batch.getSheetIndex() != null ? batch.getSheetIndex() : 0;
        try (InputStream in = storageService.get(batch.getStoragePath())) {
            byte[] bytes = in.readAllBytes();
            List<IndexedRow> rawRows = parseExcelRaw(bytes, sheetIndex);
            List<Map<Integer, String>> rawValues = valuesOf(rawRows);
            HeaderLayout headerLayout = detectHeaderLayout(rawValues);
            List<Integer> indexes = new ArrayList<>();
            for (int i = headerLayout.dataStartIndex(); i < rawRows.size(); i++) {
                indexes.add(rawRows.get(i).physicalRowIndex());
            }
            // 图片列/数据列边界：用合并表头的真实物理列索引（不接受 jsonb 乱序的 previewRows 键序）
            Set<Integer> imageColumns = new HashSet<>();
            int minCol = Integer.MAX_VALUE;
            int maxCol = -1;
            if (headerLayout.headerRowCount() > 0 && rawValues.size() > headerLayout.headerStartIndex()) {
                List<Map<Integer, String>> headerRows = headerLayout.mergeSubHeader()
                    ? rawValues.subList(headerLayout.headerStartIndex(), headerLayout.dataStartIndex())
                    : List.of(rawValues.get(headerLayout.headerStartIndex()));
                Map<Integer, String> headerMap = ExcelHeaderNormalizer.mergeHeaderRows(headerRows);
                for (Map.Entry<Integer, String> entry : headerMap.entrySet()) {
                    minCol = Math.min(minCol, entry.getKey());
                    maxCol = Math.max(maxCol, entry.getKey());
                    if (isEmbeddedImageHeader(entry.getValue())) {
                        imageColumns.add(entry.getKey());
                    }
                }
            }
            return new PhysicalLayout(indexes, imageColumns, minCol, maxCol, resolveSheetName(bytes, sheetIndex));
        } catch (Exception e) {
            log.warn("重建数据行物理布局失败，batchId={}", batch.getBatchId(), e);
            return null;
        }
    }

    /**
     * 读取工作表名（品类线索）。CSV/解析失败返回 null，由调用方走后续兜底链。
     *
     * @param bytes      原始文件字节
     * @param sheetIndex 工作表索引
     * @return 工作表名；不可得为 null
     */
    private String resolveSheetName(byte[] bytes, int sheetIndex) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (sheetIndex >= 0 && sheetIndex < workbook.getNumberOfSheets()) {
                return workbook.getSheetName(sheetIndex);
            }
        } catch (Exception e) {
            log.debug("读取工作表名失败，sheetIndex={}", sheetIndex, e);
        }
        return null;
    }

    private Map<String, List<CategoryDict>> preloadDicts() {
        Map<String, List<CategoryDict>> cache = new HashMap<>();
        cache.put("category", safeList(dictService.listByType("category")));
        cache.put("style", safeList(dictService.listByType("style")));
        cache.put("scene", safeList(dictService.listByType("scene")));
        cache.put("material", safeList(dictService.listByType("material")));
        cache.put("size", safeList(dictService.listByType("size")));
        cache.put("color", safeList(dictService.listByType("color")));
        cache.put("factory_level", safeList(dictService.listByType("factory_level")));
        return cache;
    }

    private List<CategoryDict> safeList(List<CategoryDict> list) {
        return list == null ? List.of() : list;
    }

    private RowResult processRowInTransaction(Map<String, String> dataRow, Map<String, String> mapping,
                                              String categoryHint,
                                              List<PriceColumnInfo> priceColumns,
                                              ExcelAiMappingRequest request,
                                              Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages,
                                              Map<String, List<CategoryDict>> dictCache, int rowIndex,
                                              Long importRowId, Integer physicalRowIndex,
                                              ProductGroup currentGroup, PhysicalLayout physicalLayout,
                                              int sheetIndex, String sheetName, String categoryGuess,
                                              String batchId) {
        // 事务外预处理：行构建/校验、URL 图片下载、内嵌图提取。
        // 网络与文件 IO 耗时可达数十秒，绝不放入 DB 事务（长事务占用连接池会拖垮全系统）；
        // 同时 MinIO 不参与 DB 事务，先存文件、事务内只登记元数据。
        // 行回滚时可能产生孤儿存储对象，属可接受代价（量大时可加定期清理）。
        PreparedRow prep = prepareRow(dataRow, mapping, categoryHint, priceColumns, request,
            embeddedImages, dictCache, rowIndex, importRowId, physicalRowIndex,
            currentGroup, physicalLayout, sheetIndex, sheetName, categoryGuess, batchId);
        if (prep.earlyResult() != null) {
            return prep.earlyResult();
        }
        List<StoredImage> storedProductImages = storeImages(prep.productImages(), prep.rowIssues());
        List<StoredImage> storedVariantImages = storeImages(prep.variantImages(), prep.rowIssues());

        // 事务内：纯数据库写入（RSPU/关联/变体/RSKU/图片元数据/异步任务），保持短事务
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            RowResult result = persistRow(prep, storedProductImages, storedVariantImages,
                dataRow, priceColumns, request, dictCache, rowIndex, importRowId, currentGroup);
            transactionManager.commit(status);
            return result;
        } catch (Exception e) {
            transactionManager.rollback(status);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException("系统异常: " + e.getMessage());
        }
    }

    /**
     * 行预处理（事务外）：行构建、校验、图片下载/提取、主图策略计算。
     * 返回的 {@link PreparedRow#earlyResult()} 非空时表示该行应直接跳过。
     */
    private PreparedRow prepareRow(Map<String, String> dataRow, Map<String, String> mapping, String categoryHint,
                                   List<PriceColumnInfo> priceColumns,
                                   ExcelAiMappingRequest request,
                                   Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages,
                                   Map<String, List<CategoryDict>> dictCache, int rowIndex, Long importRowId,
                                   Integer physicalRowIndex,
                                   ProductGroup currentGroup, PhysicalLayout physicalLayout,
                                   int sheetIndex, String sheetName, String categoryGuess, String batchId) {
        if (isNoteOrEmptyRow(dataRow)) {
            log.debug("第 {} 行为说明或空行，已跳过", rowIndex);
            return PreparedRow.skip(RowResult.skipped("说明或空行"));
        }
        if (isRepeatedHeaderRow(dataRow)) {
            log.debug("第 {} 行为重复表头行，已跳过", rowIndex);
            return PreparedRow.skip(RowResult.skipped("重复表头行"));
        }

        excelImportRowService.updateStage(importRowId, "build_product_row");
        ProductImportRow row = buildProductImportRow(dataRow, mapping, categoryHint, categoryGuess, sheetName,
            dictCache);
        // 品类分层解析：中文品名/方言 → 字典码（用户确认映射 > 字典码 > 字典名 > 别名库），未命中保留原值
        String normalizedCategory = normalizeCategoryCode(row.getCategoryCode(), request.getCategoryMapping(),
            dictCache);
        if (normalizedCategory != null) {
            row.setCategoryCode(normalizedCategory);
        }
        String error = validateRow(row, dictCache);
        if (error != null) {
            throw new BusinessException(error);
        }

        excelImportRowService.updateStage(importRowId, "download_images");
        List<String> rowIssues = new ArrayList<>();
        // 三码容错解析（V19）：未识别的尺寸/颜色/材质码降级为原文保留并采集待治理，不阻断导入
        resolveVariantCodeFields(row, dictCache, rowIssues, batchId);
        // sales 角色价格列：不建变体/RSKU，价格值作为零售参考价（仅当行内尚无 retailPrice 时取值）
        if (row.getRetailPrice() == null) {
            row.setRetailPrice(extractSalesRetailPrice(dataRow, priceColumns, rowIndex, rowIssues));
        }
        List<DownloadedImage> images = new ArrayList<>();
        images.addAll(downloadUrlImages(row, rowIndex));
        images.addAll(extractEmbeddedImagesForRow(embeddedImages, rowIndex, physicalRowIndex, physicalLayout,
            sheetIndex, rowIssues));

        // 同型号连续行归入上一产品的 RSPU（规格模块共享产品主图，不再创建独立产品）
        String groupKey = trim(row.getExternalCode());
        boolean sameProduct = currentGroup != null && StringUtils.hasText(groupKey)
            && groupKey.equals(currentGroup.externalCode);

        // 模块行图片：组内已有主图时全部作为详情图；组内尚无主图时允许本行首图升为主图
        boolean allowPrimary = !sameProduct || !currentGroup.hasPrimaryImage;
        // 表格有图片列时启用严格主图模式：规格模块示例图不兜底升主图，避免示例图被当成产品主图
        boolean strictPrimary = physicalLayout != null && !physicalLayout.imageColumns().isEmpty();
        // 产品级图：URL 图 + 产品图样列内嵌图 → 挂 RSPU；
        // 变体级图：规格模块示例图（锚在模块列等非图片列）→ 挂本行变体，变体无图时展示层回退产品主图
        List<DownloadedImage> productImages = images.stream().filter(img -> !img.variantLevel()).toList();
        List<DownloadedImage> variantImages = images.stream().filter(DownloadedImage::variantLevel).toList();

        return new PreparedRow(row, rowIssues, productImages, variantImages,
            groupKey, sameProduct, allowPrimary, strictPrimary, images.size(), null);
    }

    /**
     * 行持久化（事务内）：纯数据库写入，RSPU/工厂关联/变体/RSKU/图片元数据/异步任务。
     */
    private RowResult persistRow(PreparedRow prep, List<StoredImage> storedProductImages,
                                 List<StoredImage> storedVariantImages,
                                 Map<String, String> dataRow, List<PriceColumnInfo> priceColumns,
                                 ExcelAiMappingRequest request, Map<String, List<CategoryDict>> dictCache,
                                 int rowIndex, Long importRowId, ProductGroup currentGroup) {
        ProductImportRow row = prep.row();
        List<String> rowIssues = prep.rowIssues();
        String groupKey = prep.groupKey();

        excelImportRowService.updateStage(importRowId, "create_rspu");
        String rspuId;
        boolean createdNewRspu;
        if (prep.sameProduct()) {
            rspuId = currentGroup.rspuId;
            createdNewRspu = false;
            log.debug("第 {} 行与上一产品同型号（{}），归入已有 RSPU {}", rowIndex, groupKey, rspuId);
        } else {
            // updateIfExists 开关：externalCode 已存在（未软删）时，true 复用并更新已有 RSPU，false 跳过该行
            RspuMaster existing = findRspuByExternalCode(groupKey);
            if (existing != null && !request.isUpdateIfExists()) {
                log.debug("第 {} 行外部编码 {} 已存在，按配置跳过", rowIndex, groupKey);
                return RowResult.skipped("已存在，跳过: " + groupKey);
            }
            if (existing != null) {
                rspuId = existing.getRspuId();
                updateExistingRspu(existing, row, dictCache);
                saveStylesAndScenes(rspuId, row, dictCache);
                createdNewRspu = false;
                log.debug("第 {} 行外部编码 {} 已存在，复用并更新已有 RSPU {}", rowIndex, groupKey, rspuId);
            } else {
                rspuId = createRspu(row, dictCache);
                saveStylesAndScenes(rspuId, row, dictCache);
                createdNewRspu = true;
            }
        }
        // 创建 RSPU-工厂关联与变体（先建变体，模块行的规格示例图要挂到本行变体上）
        excelImportRowService.updateStage(importRowId, "create_factory_mapping");
        VariantRskuOutcome variantRskuOutcome = createRspuFactoryMappingAndVariants(rspuId, dataRow, row,
            priceColumns, request, dictCache, importRowId, rowIssues);
        String variantId = variantRskuOutcome.firstVariantId();

        // 登记图片元数据（文件已在事务外写入对象存储）
        String primaryObjectKey = registerImages(rspuId, null, storedProductImages,
            prep.allowPrimary(), prep.strictPrimary());
        registerImages(rspuId, variantId, storedVariantImages, false, false);

        excelImportRowService.updateStage(importRowId, "create_async_task");
        String taskId = null;
        if (primaryObjectKey != null) {
            // 同组行仅在组内尚无 AI 任务时补建（主图后至的场景），避免重复识别
            boolean needTask = !prep.sameProduct() || !currentGroup.hasAiTask;
            if (needTask) {
                taskId = createAsyncTask(rspuId, primaryObjectKey);
            }
        }

        List<String> imageAssetIds = List.of(); // 目前 registerImages 未返回 ID 列表，后续可扩展
        return RowResult.success(rspuId, variantId, variantRskuOutcome.rskuIds(), prep.imageCount(), imageAssetIds,
            taskId, groupKey, createdNewRspu, primaryObjectKey != null, rowIssues);
    }

    private ProductImportRow buildProductImportRow(Map<String, String> dataRow, Map<String, String> mapping,
                                                   String categoryHint, String categoryGuess, String sheetName,
                                                   Map<String, List<CategoryDict>> dictCache) {
        ProductImportRow row = new ProductImportRow();
        Map<String, String> standardValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : dataRow.entrySet()) {
            String header = entry.getKey();
            String standardField = mapping.get(header);
            if (!StringUtils.hasText(standardField)) {
                continue;
            }
            // 支持复合映射：一个原始列映射到多个标准字段
            if (standardField.contains(",")) {
                for (String field : standardField.split(",")) {
                    standardValues.put(field.trim(), entry.getValue());
                }
            } else {
                standardValues.put(standardField, entry.getValue());
            }
        }

        row.setExternalCode(getValue(standardValues, "externalCode"));
        // 「规格/模块」列由 prompt 引导映射为标准字段 variantDisplayName，优先读取；
        // 缺失时回退 productName（复合「型号品名」列场景）
        String productName = getValue(standardValues, "productName");
        String variantDisplayName = getValue(standardValues, "variantDisplayName");
        row.setVariantDisplayName(StringUtils.hasText(variantDisplayName) ? variantDisplayName : productName);

        // 复合表头"型号品名"的值同时包含型号和品名时，尝试拆分
        splitExternalCodeAndProductName(row, productName);
        row.setPositioningLabel(getValue(standardValues, "positioningLabel"));
        row.setColorPrimaryName(getValue(standardValues, "colorPrimaryName"));
        row.setMaterialTags(getValue(standardValues, "materialTags"));
        row.setSceneTags(getValue(standardValues, "sceneTags"));
        row.setProductLevel(getValue(standardValues, "productLevel"));
        row.setWarrantyYears(parseInt(getValue(standardValues, "warrantyYears")));
        row.setReferencePriceBand(getValue(standardValues, "referencePriceBand"));
        row.setSixDimTags(getValue(standardValues, "sixDimTags"));
        row.setKeySpecs(getValue(standardValues, "keySpecs"));
        row.setPrimaryImageUrl(getValue(standardValues, "primaryImageUrl"));
        row.setDetailImageUrls(getValue(standardValues, "detailImageUrls"));
        row.setSizeCode(getValue(standardValues, "sizeCode"));
        row.setColorCode(getValue(standardValues, "colorCode"));
        row.setMaterialCode(getValue(standardValues, "materialCode"));
        row.setLeadTimeDays(parseDaysValue(getValue(standardValues, "leadTimeDays")));
        // 长文本描述原文（材质解析/功能配置/配置说明等），零售参考价数字解析（复用多行容忍）
        row.setDescription(getValue(standardValues, "description"));
        row.setRetailPrice(parsePrice(getValue(standardValues, "retailPrice")));

        // 品类兜底链：行类别列 > sheet 名归一 > 用户品类提示 > AI 品类猜测
        String categoryCode = getValue(standardValues, "categoryCode");
        if (!StringUtils.hasText(categoryCode)) {
            categoryCode = resolveCategoryBySheetName(sheetName, dictCache);
        }
        if (!StringUtils.hasText(categoryCode)) {
            categoryCode = categoryHint;
        }
        if (!StringUtils.hasText(categoryCode)) {
            categoryCode = categoryGuess;
        }
        row.setCategoryCode(categoryCode);

        String dimensions = getValue(standardValues, "dimensions");
        if (StringUtils.hasText(dimensions) && !StringUtils.hasText(row.getKeySpecs())) {
            try {
                row.setKeySpecs(objectMapper.writeValueAsString(Map.of("dimensions", dimensions)));
            } catch (Exception e) {
                log.warn("序列化 dimensions 失败", e);
            }
        }

        return row;
    }

    private void splitExternalCodeAndProductName(ProductImportRow row, String productName) {
        String externalCode = row.getExternalCode();
        if (!StringUtils.hasText(externalCode) || !externalCode.equals(productName)) {
            return;
        }
        // 常见分隔：空格、斜杠、竖线、中文斜杠
        String combined = externalCode;
        int idx = findSplitIndex(combined);
        if (idx > 0) {
            row.setExternalCode(combined.substring(0, idx).trim());
            // 仅当变体名同样来自该复合值（无独立「规格/模块」列）时才回填品名部分，
            // 避免覆盖 variantDisplayName 标准字段读到的模块名
            if (combined.equals(row.getVariantDisplayName())) {
                row.setVariantDisplayName(combined.substring(idx + 1).trim());
            }
        }
    }

    private int findSplitIndex(String text) {
        for (char sep : new char[]{' ', '/', '\\', '|', '／', '、'}) {
            int idx = text.indexOf(sep);
            if (idx > 0 && idx < text.length() - 1) {
                return idx;
            }
        }
        return -1;
    }

    private String getValue(Map<String, String> map, String key) {
        String value = map.get(key);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer parseInt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析天数类数值，容忍「30天」「约30天」「25-30天」等写法（取首个数字串）。
     *
     * @param value 原始文本
     * @return 天数，无法解析时为 null
     */
    private Integer parseDaysValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 品类值分层解析：①用户确认映射（最高优先）②字典码 ③字典中文名精确匹配 ④别名库。
     *
     * <p>工厂 Excel 品类列常是中文品名（茶桌/主椅/方凳），逐层归一为字典码；
     * 都无法解析时返回原值（{@code validateRow} 仍会报「品类码不存在」，行为兼容）。</p>
     *
     * @param value       Excel 原始品类值（或 categoryHint 兜底值）
     * @param userMapping 用户在确认页编辑的品类映射（rawValue → dictCode），可为 null
     * @param dictCache   字典缓存
     * @return 归一后的字典码；输入为空返回 null；无法解析返回原值
     */
    private String normalizeCategoryCode(String value, Map<String, String> userMapping,
                                         Map<String, List<CategoryDict>> dictCache) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        List<CategoryDict> categories = dictCache.get("category");
        // ① 用户确认映射（最高优先）
        if (userMapping != null) {
            String mapped = userMapping.get(trimmed);
            if (StringUtils.hasText(mapped) && isValidDictCode(mapped.trim().toUpperCase(), categories)) {
                return mapped.trim().toUpperCase();
            }
        }
        // ② 本身就是字典码
        if (isValidDictCode(trimmed.toUpperCase(), categories)) {
            return trimmed.toUpperCase();
        }
        // ③ 字典中文名精确匹配
        String byName = dictResolverService.resolveCodeByName("category", trimmed);
        if (StringUtils.hasText(byName)) {
            return byName.trim().toUpperCase();
        }
        // ④ 别名库
        String byAlias = dictAliasService.resolveAlias("category", trimmed);
        if (StringUtils.hasText(byAlias) && isValidDictCode(byAlias.trim().toUpperCase(), categories)) {
            return byAlias.trim().toUpperCase();
        }
        // ⑤ 无法解析：返回原值，由 validateRow 报错（行为兼容）
        return trimmed;
    }

    /**
     * 工作表名 → 品类字典码归一（无品类列的多 Sheet 文件常按品类分 sheet，如「茶几」「沙发」）。
     *
     * <p>归一顺序与行级品类一致：①字典码 ②字典中文名 ③别名库；都不命中返回 null，
     * 由调用方继续走用户品类提示/AI 猜测兜底，不报错。</p>
     *
     * @param sheetName 工作表名
     * @param dictCache 字典缓存
     * @return 归一后的品类字典码；无法归一为 null
     */
    private String resolveCategoryBySheetName(String sheetName, Map<String, List<CategoryDict>> dictCache) {
        if (!StringUtils.hasText(sheetName)) {
            return null;
        }
        String name = sheetName.trim();
        List<CategoryDict> categories = dictCache.get("category");
        // ① 本身就是字典码
        if (isValidDictCode(name.toUpperCase(), categories)) {
            return name.toUpperCase();
        }
        // ② 字典中文名精确匹配
        String byName = dictResolverService.resolveCodeByName("category", name);
        if (StringUtils.hasText(byName)) {
            return byName.trim().toUpperCase();
        }
        // ③ 别名库
        String byAlias = dictAliasService.resolveAlias("category", name);
        if (StringUtils.hasText(byAlias) && isValidDictCode(byAlias.trim().toUpperCase(), categories)) {
            return byAlias.trim().toUpperCase();
        }
        return null;
    }

    /**
     * 生成品类映射建议（预览用）：收集映射到 categoryCode 的列的 distinct 非空值（截断 50 个），
     * 字典码/字典名/别名库可直接解析的标记 dict/alias；其余批量一次 AI 归一标记 ai，
     * AI 失败降级为 none，不阻断预览。
     *
     * @param mapping       AI 后处理的字段映射（表头 → 标准字段）
     * @param mergedHeaders 合并表头（列索引 → 表头）
     * @param dataRows      数据行（列索引 → 值）
     * @param sheetName     当前工作表名（品类线索，注入 AI 归一上下文）
     * @return 品类映射建议列表
     */
    private List<CategoryMappingItem> suggestCategoryMappings(Map<String, String> mapping,
                                                              Map<Integer, String> mergedHeaders,
                                                              List<Map<Integer, String>> dataRows,
                                                              String sheetName) {
        try {
            // 找到映射到 categoryCode 的列（兼容复合映射）
            Set<Integer> categoryColumns = new HashSet<>();
            for (Map.Entry<Integer, String> entry : mergedHeaders.entrySet()) {
                String field = mapping.get(entry.getValue());
                if (field == null) {
                    continue;
                }
                for (String f : field.split(",")) {
                    if ("categoryCode".equals(f.trim())) {
                        categoryColumns.add(entry.getKey());
                    }
                }
            }
            if (categoryColumns.isEmpty()) {
                return List.of();
            }

            // distinct 非空值（保持出现顺序），截断到 50 个
            Set<String> seen = new LinkedHashSet<>();
            for (Map<Integer, String> row : dataRows) {
                for (Integer col : categoryColumns) {
                    String value = row.get(col);
                    if (StringUtils.hasText(value)) {
                        seen.add(value.trim());
                    }
                }
            }
            List<String> rawValues = new ArrayList<>(seen);
            if (rawValues.size() > MAX_CATEGORY_SUGGESTIONS) {
                rawValues = rawValues.subList(0, MAX_CATEGORY_SUGGESTIONS);
            }
            if (rawValues.isEmpty()) {
                return List.of();
            }

            List<CategoryDict> categories = safeList(dictService.listByType("category"));
            Map<String, String> aliasMap = dictAliasService.resolveAliases("category", rawValues);

            List<CategoryMappingItem> items = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            for (String raw : rawValues) {
                if (isValidDictCode(raw.toUpperCase(), categories)) {
                    items.add(new CategoryMappingItem(raw, raw.toUpperCase(), "dict"));
                    continue;
                }
                String byName = matchDictName(raw, categories);
                if (byName != null) {
                    items.add(new CategoryMappingItem(raw, byName, "dict"));
                    continue;
                }
                String byAlias = aliasMap.get(raw);
                if (StringUtils.hasText(byAlias) && isValidDictCode(byAlias.trim().toUpperCase(), categories)) {
                    items.add(new CategoryMappingItem(raw, byAlias.trim().toUpperCase(), "alias"));
                    continue;
                }
                unresolved.add(raw);
            }

            // 未解析词批量一次 AI 归一；AI 失败降级为 none，不阻断预览
            if (!unresolved.isEmpty()) {
                Map<String, String> aiResolved = tryResolveCategoriesByAi(unresolved, categories, sheetName);
                for (String raw : unresolved) {
                    String code = aiResolved.get(raw);
                    items.add(code != null
                        ? new CategoryMappingItem(raw, code, "ai")
                        : new CategoryMappingItem(raw, null, "none"));
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("生成品类映射建议失败，降级为无建议", e);
            return List.of();
        }
    }

    /**
     * 字典中文名/英文名精确匹配（与 DictResolverService.resolveCodeByName 同语义，基于给定列表本地匹配）。
     */
    private String matchDictName(String name, List<CategoryDict> categories) {
        for (CategoryDict d : categories) {
            if (name.equals(d.getDictName()) || (d.getDictNameEn() != null && name.equalsIgnoreCase(d.getDictNameEn()))) {
                return d.getDictCode();
            }
        }
        return null;
    }

    /**
     * 批量调用 AI 把无法解析的品类词归一到字典码；任何失败都降级为空结果。
     *
     * @param unknownValues 待归一的品类词
     * @param categories    品类字典
     * @param sheetName     当前工作表名（品类线索，注入 AI 上下文）
     * @return rawValue → dictCode（仅含成功归一的词条）
     */
    private Map<String, String> tryResolveCategoriesByAi(List<String> unknownValues,
                                                         List<CategoryDict> categories, String sheetName) {
        try {
            String systemPrompt = """
                你是家具品类归一专家。给定家具产品的中文品类叫法（可能是工厂方言/俗称），把它归一到给定的品类枚举字典码。
                输出 JSON 数组：[{"rawValue":"原始词","dictCode":"字典码"}]，每个输入词恰好一条；
                无法可靠判断时 dictCode 填 null。只输出 JSON，不要输出任何其他文字。
                """;
            String sheetHint = StringUtils.hasText(sheetName)
                ? "\n\n这批数据来自 Excel 工作表「" + sheetName + "」，工作表名可能暗示了品类。"
                : "";
            String userPrompt = "可选品类枚举：" + buildCategoryEnumText(categories)
                + "\n\n待归一品类词：\n"
                + unknownValues.stream().map(v -> "- " + v).collect(Collectors.joining("\n"))
                + sheetHint;
            String aiResponse = visionService.chatText(systemPrompt, userPrompt);
            return parseCategoryAiResponse(aiResponse, categories);
        } catch (Exception e) {
            log.warn("AI 品类归一失败，未解析词降级为 none", e);
            return Map.of();
        }
    }

    /**
     * 解析 AI 品类归一响应（JSON 数组 [{rawValue, dictCode}]），容错截取首个 '['；
     * 只接受合法字典码，非法/空码忽略。
     */
    private Map<String, String> parseCategoryAiResponse(String aiResponse, List<CategoryDict> categories) {
        if (!StringUtils.hasText(aiResponse)) {
            return Map.of();
        }
        String json = aiResponse.trim();
        int start = json.indexOf('[');
        if (start < 0) {
            return Map.of();
        }
        json = json.substring(start);
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {
            });
            Map<String, String> result = new HashMap<>();
            for (Map<String, Object> item : list) {
                Object raw = item.get("rawValue");
                Object code = item.get("dictCode");
                if (raw == null || code == null) {
                    continue;
                }
                String codeText = code.toString().trim().toUpperCase();
                if (StringUtils.hasText(raw.toString()) && isValidDictCode(codeText, categories)) {
                    result.put(raw.toString().trim(), codeText);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 AI 品类归一响应失败，response={}", aiResponse, e);
            return Map.of();
        }
    }

    /**
     * 品类枚举文本（与 VisionService.buildCategoryEnumText 同格式）：FS(沙发)、TB(茶桌)。
     */
    private String buildCategoryEnumText(List<CategoryDict> categories) {
        return categories.stream()
            .filter(d -> d.getDictCode() != null && !d.getDictCode().isBlank())
            .map(d -> d.getDictCode() + "(" + (d.getDictName() != null ? d.getDictName() : "") + ")")
            .sorted()
            .collect(Collectors.joining("、"));
    }

    /**
     * 别名自学习：把用户在确认页提交/确认过的品类映射写回别名库。
     * 单个词条失败不影响其他词条与导入结果。
     *
     * @param request 确认导入请求（含 categoryMapping）
     */
    private void learnCategoryAliases(ExcelAiMappingRequest request) {
        Map<String, String> categoryMapping = request.getCategoryMapping();
        if (categoryMapping == null || categoryMapping.isEmpty()) {
            return;
        }
        // 写回前校验目标码是合法 category 字典码，非法值跳过不写，避免污染别名库（P2-6）
        List<CategoryDict> categories = safeList(dictService.listByType("category"));
        String operator = SecurityOperatorContext.currentUsername();
        for (Map.Entry<String, String> entry : categoryMapping.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            String code = entry.getValue().trim().toUpperCase();
            if (!isValidDictCode(code, categories)) {
                log.warn("品类映射目标码不是合法字典码，跳过写回别名库: {} -> {}", entry.getKey(), code);
                continue;
            }
            try {
                dictAliasService.saveAlias("category", entry.getKey().trim(), code, operator);
            } catch (Exception e) {
                log.warn("写回品类别名失败: {} -> {}", entry.getKey(), entry.getValue(), e);
            }
        }
    }

    private String validateRow(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        if (!StringUtils.hasText(row.getCategoryCode())) {
            return "品类码不能为空，请在 Excel 中提供品类字段或在导入时选择品类提示";
        }
        String categoryCode = row.getCategoryCode().trim().toUpperCase();
        if (categoryCode.length() > 16) {
            return "品类码长度不能超过 16";
        }
        if (!isValidDictCode(categoryCode, dictCache.get("category"))) {
            return "品类码不存在: " + categoryCode;
        }

        if (StringUtils.hasText(row.getPositioningLabel())) {
            // 多风格：每个值都必须是合法字典风格
            for (String styleName : splitCsv(row.getPositioningLabel())) {
                String styleCode = normalizeDictCode(styleName, dictCache.get("style"));
                if (!isValidDictCode(styleCode, dictCache.get("style"))) {
                    return "定位标签不存在: " + styleName;
                }
            }
        }
        if (StringUtils.hasText(row.getProductLevel())) {
            String levelCode = normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"));
            if (!isValidDictCode(levelCode, dictCache.get("factory_level"))) {
                return "产品等级不存在: " + row.getProductLevel();
            }
        }
        if (StringUtils.hasText(row.getReferencePriceBand())) {
            String band = row.getReferencePriceBand().trim().toLowerCase();
            if (!List.of("low", "mid", "high").contains(band)) {
                return "参考价格带必须是 low/mid/high 之一";
            }
        }
        if (row.getWarrantyYears() != null && (row.getWarrantyYears() < 0 || row.getWarrantyYears() > 100)) {
            return "保修年限必须在 0~100 之间";
        }
        // 尺寸码/颜色码/材质码不再强制字典校验（V19：原文为主、码为辅）——
        // 由 prepareRow 中的 resolveVariantCodeFields 做"别名→字典"尽力解析，
        // 未识别值降级为原文保留（*_text）并采集待治理，不阻断导入
        return null;
    }

    /**
     * 解析行内尺寸/颜色/材质三码（V19 容错降级）。
     *
     * <p>尽力归一为字典码（别名 → 字典码 → 字典名）；未识别的值码置空、
     * 原文保留到 *_text 字段，记行级警告并采集到 dict_unresolved_value 待治理。</p>
     *
     * @param row       产品导入行（就地修改）
     * @param dictCache 字典缓存
     * @param rowIssues 行级问题收集器
     * @param batchId   导入批次 ID（采集上下文，可为 null）
     */
    private void resolveVariantCodeFields(ProductImportRow row, Map<String, List<CategoryDict>> dictCache,
                                          List<String> rowIssues, String batchId) {
        resolveOneCodeField("size", "尺寸码", row.getSizeCode(), dictCache.get("size"), rowIssues, batchId,
            row::setSizeCode, row::setSizeText);
        resolveOneCodeField("color", "颜色码", row.getColorCode(), dictCache.get("color"), rowIssues, batchId,
            row::setColorCode, row::setColorText);
        resolveOneCodeField("material", "材质码", row.getMaterialCode(), dictCache.get("material"), rowIssues, batchId,
            row::setMaterialCode, row::setMaterialText);
    }

    private void resolveOneCodeField(String dictType, String label, String raw, List<CategoryDict> dicts,
                                     List<String> rowIssues, String batchId,
                                     java.util.function.Consumer<String> codeSetter,
                                     java.util.function.Consumer<String> textSetter) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String resolved = normalizeDictCode(dictType, raw, dicts);
        if (isValidDictCode(resolved, dicts)) {
            codeSetter.accept(resolved);
            return;
        }
        String trimmed = raw.trim();
        codeSetter.accept(null);
        textSetter.accept(trimmed);
        rowIssues.add(label + "未识别: " + trimmed + "，已按原文保留，待治理归一");
        dictUnresolvedService.record(dictType, trimmed, batchId, SecurityOperatorContext.currentUsername());
    }

    private boolean isValidDictCode(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equalsIgnoreCase(d.getDictCode()));
    }

    private String normalizeDictCode(String input, List<CategoryDict> dicts) {
        return normalizeDictCode(null, input, dicts);
    }

    /**
     * 尽力将输入归一为字典码：别名（dict_alias）→ 字典码（忽略大小写）→ 字典名；未命中返回原值。
     *
     * @param dictType 字典类型（提供时启用别名解析；为 null 时跳过别名）
     * @param input    原始输入
     * @param dicts    字典列表
     * @return 归一后的字典码，未命中返回原值
     */
    private String normalizeDictCode(String dictType, String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        if (StringUtils.hasText(dictType)) {
            String byAlias = dictAliasService.resolveAlias(dictType, trimmed);
            if (StringUtils.hasText(byAlias)) {
                return byAlias;
            }
        }
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
        return trimmed;
    }

    private List<DownloadedImage> downloadUrlImages(ProductImportRow row, int rowIndex) {
        List<DownloadedImage> images = new ArrayList<>();
        if (StringUtils.hasText(row.getPrimaryImageUrl())) {
            String url = row.getPrimaryImageUrl().trim();
            if (!looksLikeUrl(url)) {
                log.debug("第 {} 行主图值不是 URL，跳过: {}", rowIndex, url);
            } else if (ImageUrlValidator.isAllowed(url, allowedImageHosts)) {
                DownloadedImage image = downloadImage(url, true);
                if (image != null) {
                    images.add(image);
                }
            } else {
                log.warn("第 {} 行主图 URL 不安全，已跳过: {}", rowIndex, url);
            }
        }
        if (StringUtils.hasText(row.getDetailImageUrls())) {
            for (String url : row.getDetailImageUrls().split("[，,]")) {
                url = url.trim();
                if (url.isBlank()) {
                    continue;
                }
                if (!looksLikeUrl(url)) {
                    log.debug("第 {} 行详情图值不是 URL，跳过: {}", rowIndex, url);
                    continue;
                }
                if (ImageUrlValidator.isAllowed(url, allowedImageHosts)) {
                    DownloadedImage image = downloadImage(url, false);
                    if (image != null) {
                        images.add(image);
                    }
                } else {
                    log.warn("第 {} 行详情图 URL 不安全，已跳过: {}", rowIndex, url);
                }
            }
        }
        return images;
    }

    private boolean looksLikeUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("ftp://");
    }

    private DownloadedImage downloadImage(String url, boolean primary) {
        try {
            URLConnection connection = new URL(url).openConnection();
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
            }
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            String contentType = connection.getContentType();
            // Content-Length 预检 + 限量读取：先判大小再下载，避免超限图片占满内存（P2-8）
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_SIZE) {
                log.warn("图片超过大小上限（{} 字节），已跳过: {}", contentLength, url);
                if (connection instanceof HttpURLConnection httpConnection) {
                    httpConnection.disconnect();
                }
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                // 多读 1 字节用于判断是否超限，无需 readAllBytes 全量加载
                byte[] bytes = in.readNBytes(MAX_IMAGE_SIZE + 1);
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_SIZE) {
                    return null;
                }
                return new DownloadedImage(url, bytes, contentType, primary, false);
            }
        } catch (Exception e) {
            log.warn("下载图片失败: {}", url, e);
            return null;
        }
    }

    private List<DownloadedImage> extractEmbeddedImagesForRow(
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages, int rowIndex, Integer physicalRowIndex,
        PhysicalLayout physicalLayout, int sheetIndex, List<String> rowIssues) {
        // 物理行号（0-based）：优先取导入时重建的真实行号（双行表头/标题行/空行场景仍精确）；
        // 缺失时回退旧的「rowIndex - 1」换算（表头 1 行假设），保证行为不劣化。
        // ExcelImageExtractor 的 key 格式为 "sheetIndex,physicalRowIndex"，按批次 sheet_index 取本工作表的图。
        int physicalRow = physicalRowIndex != null ? physicalRowIndex : rowIndex - 1;
        String key = sheetIndex + "," + physicalRow;
        List<ExcelImageExtractor.EmbeddedImage> images = embeddedImages.get(key);
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<DownloadedImage> result = new ArrayList<>();
        // 同行图片列锚多张内嵌图时仅首个升主图，其余降为详情图，
        // 避免同一 RSPU 产生多条 is_primary=true（P2-13）
        boolean primaryAssigned = false;
        for (ExcelImageExtractor.EmbeddedImage img : images) {
            String extension = img.extension() != null ? img.extension().toLowerCase() : "jpg";
            // 非 web 图片格式（EMF/WMF/TIFF）浏览器无法渲染，跳过不入库并记入行失败明细
            // （提取器内已提前跳过同类格式，此处为双保险）
            if (extension.equals("emf") || extension.equals("wmf") || extension.equals("tiff")) {
                log.warn("第 {} 行内嵌图片格式不支持（{}），已跳过", rowIndex, extension);
                rowIssues.add("不支持的图片格式: " + extension);
                continue;
            }
            boolean primary = false;
            boolean variantLevel = false;
            if (physicalLayout != null && physicalLayout.maxDataColumn() >= 0) {
                // 数据区域之外的图（logo/二维码/装饰图）不视为产品图
                if (img.colIndex() < physicalLayout.minDataColumn() || img.colIndex() > physicalLayout.maxDataColumn()) {
                    log.debug("第 {} 行忽略数据区域外的内嵌图，colIndex={}", rowIndex, img.colIndex());
                    continue;
                }
                // 锚定在图片列的图是产品主图候选（每行仅首个升主图）；
                // 其他数据列（如规格/模块列）的图是模块样式示例图，归属本行变体
                boolean inImageColumn = physicalLayout.imageColumns().contains(img.colIndex());
                if (inImageColumn && !primaryAssigned) {
                    primary = true;
                    primaryAssigned = true;
                }
                variantLevel = !physicalLayout.imageColumns().isEmpty() && !inImageColumn;
            }
            String contentType = switch (extension) {
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                default -> "image/jpeg";
            };
            result.add(new DownloadedImage("embedded://" + img.rowIndex() + "-" + img.colIndex(),
                img.bytes(), contentType, primary, variantLevel));
        }
        return result;
    }

    private String createRspu(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        String rspuId = IdGenerator.rspuId();

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(trim(row.getExternalCode()));
        // 品名落 RSPU 产品名称（变体 displayName 保持不变，互不影响）
        rspu.setProductName(trim(row.getVariantDisplayName()));
        rspu.setDescription(trim(row.getDescription()));
        rspu.setRetailPrice(row.getRetailPrice());
        rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
        rspu.setCategoryPath(CategoryPaths.resolve(rspu.getCategoryCode()));
        // 多风格时主字段存第一个风格（主风格），其余进 rspu_style 辅风格
        String primaryStyleName = splitCsv(row.getPositioningLabel()).stream().findFirst().orElse(null);
        rspu.setPositioningLabel(StringUtils.hasText(primaryStyleName)
            ? normalizeDictCode(primaryStyleName, dictCache.get("style"))
            : "待识别");
        rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        rspu.setMaterialTags(toJson(splitCsv(row.getMaterialTags())));
        rspu.setSceneTags(toJson(splitCsv(row.getSceneTags())));
        rspu.setSixDimTags(trim(row.getSixDimTags()));
        rspu.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
            ? row.getReferencePriceBand().trim().toLowerCase()
            : null);
        rspu.setProductLevel(StringUtils.hasText(row.getProductLevel())
            ? normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"))
            : null);
        rspu.setWarrantyYears(row.getWarrantyYears());
        rspu.setKeySpecs(trim(row.getKeySpecs()));
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);
        auditLogService.logCreate("rspu_master", rspuId, rspu, SecurityOperatorContext.currentUsername());
        return rspuId;
    }

    private String createVariantIfNeeded(String rspuId, ProductImportRow row,
                                         Map<String, List<CategoryDict>> dictCache) {
        boolean hasVariantInfo = StringUtils.hasText(row.getVariantDisplayName())
            || StringUtils.hasText(row.getSizeCode()) || StringUtils.hasText(row.getSizeText())
            || StringUtils.hasText(row.getColorCode()) || StringUtils.hasText(row.getColorText())
            || StringUtils.hasText(row.getMaterialCode()) || StringUtils.hasText(row.getMaterialText());

        if (!hasVariantInfo) {
            return null;
        }

        String displayName = StringUtils.hasText(row.getVariantDisplayName())
            ? row.getVariantDisplayName().trim()
            : buildDefaultVariantName(row);

        // 行内三码已在 prepareRow 阶段完成"别名→字典"解析与降级：码直接用，原文走 *_text
        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName(displayName);
        request.setSizeCode(StringUtils.hasText(row.getSizeCode()) ? row.getSizeCode() : null);
        request.setSizeText(row.getSizeText());
        request.setColorCode(StringUtils.hasText(row.getColorCode()) ? row.getColorCode() : null);
        request.setColorText(row.getColorText());
        request.setMaterialCode(StringUtils.hasText(row.getMaterialCode()) ? row.getMaterialCode() : null);
        request.setMaterialText(row.getMaterialText());
        request.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
            ? row.getReferencePriceBand().trim().toLowerCase()
            : null);
        request.setProductLevel(StringUtils.hasText(row.getProductLevel())
            ? normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"))
            : null);

        var variantResponse = rspuVariantService.createVariant(rspuId, request);
        if (variantResponse == null || !StringUtils.hasText(variantResponse.getVariantId())) {
            throw new BusinessException("创建默认变体失败");
        }
        return variantResponse.getVariantId();
    }

    /**
     * 从 sales 角色价格列提取零售参考价（取首个可解析的价格值）。
     *
     * <p>sales 角色价格列（销售价/含税价等）不建变体/RSKU，价格值写本行 RSPU 的
     * retail_price；解析失败记入行失败明细（用户可见），不阻断导入。</p>
     *
     * @param dataRow      数据行（表头 → 值）
     * @param priceColumns 选中的价格列
     * @param rowIndex     行号（日志用）
     * @param rowIssues    行内问题收集器
     * @return 零售参考价；无 sales 列或无可解析值时为 null
     */
    private BigDecimal extractSalesRetailPrice(Map<String, String> dataRow, List<PriceColumnInfo> priceColumns,
                                               int rowIndex, List<String> rowIssues) {
        if (priceColumns == null || priceColumns.isEmpty()) {
            return null;
        }
        for (PriceColumnInfo priceColumn : priceColumns) {
            if (!PRICE_ROLE_SALES.equals(roleOf(priceColumn))) {
                continue;
            }
            String rawPrice = dataRow.get(priceColumn.getHeader());
            String priceText = rawPrice != null ? rawPrice.trim() : "";
            if (!StringUtils.hasText(priceText)) {
                continue;
            }
            BigDecimal price = parsePrice(priceText);
            if (price != null) {
                return price;
            }
            log.warn("销售价解析失败，rowIndex={}, header={}, value={}", rowIndex, priceColumn.getHeader(), priceText);
            String displayValue = priceText.replaceAll("\\R", " ");
            rowIssues.add("销售价列「" + priceColumn.getHeader() + "」价格解析失败："
                + (displayValue.length() > 30 ? displayValue.substring(0, 30) + "…" : displayValue));
        }
        return null;
    }

    private VariantRskuOutcome createRspuFactoryMappingAndVariants(String rspuId, Map<String, String> dataRow,
                                                        ProductImportRow baseRow,
                                                        List<PriceColumnInfo> priceColumns,
                                                        ExcelAiMappingRequest request,
                                                        Map<String, List<CategoryDict>> dictCache,
                                                        Long importRowId,
                                                        List<String> rowIssues) {
        String factoryCode = StringUtils.hasText(request.getDefaultFactoryCode())
            ? request.getDefaultFactoryCode()
            : null;
        String shippingFrom = StringUtils.hasText(request.getDefaultShippingFrom())
            ? request.getDefaultShippingFrom()
            : null;
        String shippingWarehouseId = StringUtils.hasText(request.getShippingWarehouseId())
            ? request.getShippingWarehouseId()
            : null;
        Integer moq = request.getDefaultMoq() != null ? request.getDefaultMoq() : 1;
        String categoryCode = baseRow.getCategoryCode();

        // 创建 RSPU-工厂映射（仅当指定工厂时）；交期行级 Excel 值优先于请求默认值
        if (factoryCode != null) {
            Integer mappingLeadTimeDays = baseRow.getLeadTimeDays() != null
                ? baseRow.getLeadTimeDays()
                : request.getDefaultLeadTimeDays();
            createRspuFactoryMapping(rspuId, factoryCode, shippingWarehouseId, moq,
                mappingLeadTimeDays, importRowId);
        }

        String firstVariantId = null;
        List<String> rskuIds = new ArrayList<>();
        // sales 角色价格列不建变体/RSKU（价格值已在 processRow 写入 RSPU 零售参考价 retail_price）；
        // 只有 factory 角色价格列走变体 + RSKU 链路
        List<PriceColumnInfo> factoryPriceColumns = priceColumns == null ? List.of()
            : priceColumns.stream().filter(p -> !PRICE_ROLE_SALES.equals(roleOf(p))).toList();
        // 为每个选中的 factory 价格列创建变体 + RSKU
        if (!factoryPriceColumns.isEmpty()) {
            for (PriceColumnInfo priceColumn : factoryPriceColumns) {
                // 注意：previewRows 中价格列可能 key 存在但 value 为 null，必须先判空再 trim
                String rawPrice = dataRow.get(priceColumn.getHeader());
                String priceText = rawPrice != null ? rawPrice.trim() : "";
                if (!StringUtils.hasText(priceText)) {
                    continue;
                }
                BigDecimal price = parsePrice(priceText);
                if (price == null) {
                    log.warn("价格解析失败，header={}, value={}", priceColumn.getHeader(), priceText);
                    // 行内部分失败对用户可见（多行单元格取首行数字后仍无法解析）
                    String displayValue = priceText.replaceAll("\\R", " ");
                    rowIssues.add("价格列「" + priceColumn.getHeader() + "」价格解析失败："
                        + (displayValue.length() > 30 ? displayValue.substring(0, 30) + "…" : displayValue));
                    continue;
                }

                String materialName = priceColumn.getMaterialName();
                String materialGradeCode = resolveMaterialGradeCode(materialName);
                // 材质码尽力解析（别名→字典）；未识别时降级为原文（material_text），不阻断变体/报价创建
                String materialCode = resolveMaterialCode(materialName, dictCache.get("material"));
                String materialText = null;
                if (materialCode == null && StringUtils.hasText(materialName)) {
                    materialText = materialName.trim();
                    rowIssues.add("材质码未识别: " + materialText + "，已按原文保留，待治理归一");
                    dictUnresolvedService.record("material", materialText, null, SecurityOperatorContext.currentUsername());
                }

                // 交期：行级 Excel 值优先，否则按工厂交期规则动态计算
                Integer leadTimeDays = baseRow.getLeadTimeDays() != null
                    ? baseRow.getLeadTimeDays()
                    : calculateLeadTime(factoryCode, categoryCode, materialGradeCode,
                        request.getDefaultLeadTimeDays());

                // 创建变体：同 RSPU 下"码或原文"组合相同的变体直接复用（如归组的连续模块行），
                // 避免变体属性组合唯一索引冲突导致整行回滚丢价格
                String variantId = findExistingVariantId(rspuId, null, null, null, null, materialCode, materialText);
                if (variantId == null) {
                    RspuVariantCreateRequest variantRequest = new RspuVariantCreateRequest();
                    variantRequest.setDisplayName(StringUtils.hasText(materialName) ? materialName : "默认变体");
                    variantRequest.setMaterialCode(materialCode);
                    variantRequest.setMaterialText(materialText);
                    variantRequest.setReferencePriceBand(resolvePriceBand(price));
                    variantRequest.setProductLevel(baseRow.getProductLevel());
                    var variantResponse = rspuVariantService.createVariant(rspuId, variantRequest);
                    if (variantResponse == null || !StringUtils.hasText(variantResponse.getVariantId())) {
                        log.warn("为价格列创建变体失败，header={}", priceColumn.getHeader());
                        rowIssues.add("创建变体失败: " + priceColumn.getHeader());
                        continue;
                    }
                    variantId = variantResponse.getVariantId();
                }
                if (firstVariantId == null) {
                    firstVariantId = variantId;
                }

                // 创建/更新 RSKU：按 (rspuId, variantId, factoryCode) upsert——
                // 重复导入（updateIfExists）或同组模块行重复报价时更新价格而非撞唯一索引（P1-2）
                if (factoryCode != null) {
                    RskuCreateRequest rskuRequest = new RskuCreateRequest();
                    rskuRequest.setRspuId(rspuId);
                    rskuRequest.setVariantId(variantId);
                    rskuRequest.setFactoryCode(factoryCode);
                    rskuRequest.setFactoryPrice(price);
                    rskuRequest.setMaterialCode(materialCode);
                    rskuRequest.setMaterialDescription(materialName);
                    rskuRequest.setLeadTimeDays(leadTimeDays);
                    rskuRequest.setMoq(moq);
                    rskuRequest.setShippingFrom(shippingFrom);
                    rskuRequest.setShippingWarehouseId(shippingWarehouseId);
                    rskuRequest.setProductLevel(baseRow.getProductLevel());
                    try {
                        String rskuId = rskuService.upsertRsku(rskuRequest);
                        if (StringUtils.hasText(rskuId)) {
                            rskuIds.add(rskuId);
                        }
                    } catch (Exception e) {
                        // 不再静默吞掉：记入批次失败明细，让用户感知报价未入库
                        log.warn("为价格列创建/更新 RSKU 失败，header={}", priceColumn.getHeader(), e);
                        rowIssues.add("工厂报价失败: " + priceColumn.getHeader() + " - " + e.getMessage());
                    }
                }
            }
        } else {
            // 没有价格列时，只创建默认变体（RSPU + 变体 + 图片，不建 RSKU）
            String variantId = createVariantIfNeeded(rspuId, baseRow, dictCache);
            firstVariantId = variantId;
        }

        return new VariantRskuOutcome(firstVariantId, rskuIds);
    }

    /**
     * 按"码或原文"组合查找已有变体（语义同 uk_variant_attrs 唯一索引与
     * RspuVariantService.assertNoDuplicateDimensions）：比较 COALESCE(code, text) 有效值。
     *
     * @param rspuId       RSPU ID
     * @param sizeCode     尺寸码（可空）
     * @param sizeText     尺寸原文（可空）
     * @param colorCode    颜色码（可空）
     * @param colorText    颜色原文（可空）
     * @param materialCode 材质码（可空）
     * @param materialText 材质原文（可空）
     * @return 已有变体 ID，不存在时为 null
     */
    private String findExistingVariantId(String rspuId, String sizeCode, String sizeText,
                                         String colorCode, String colorText,
                                         String materialCode, String materialText) {
        String effSize = effectiveOf(sizeCode, sizeText);
        String effColor = effectiveOf(colorCode, colorText);
        String effMaterial = effectiveOf(materialCode, materialText);
        List<RspuVariant> existing = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId));
        return existing.stream()
            .filter(v -> effSize.equals(effectiveOf(v.getSizeCode(), v.getSizeText()))
                && effColor.equals(effectiveOf(v.getColorCode(), v.getColorText()))
                && effMaterial.equals(effectiveOf(v.getMaterialCode(), v.getMaterialText())))
            .map(RspuVariant::getVariantId)
            .findFirst()
            .orElse(null);
    }

    /** 有效判重值：码优先，无码取原文，均无则为空串（与唯一索引 COALESCE 语义一致）。 */
    private String effectiveOf(String code, String text) {
        if (StringUtils.hasText(code)) {
            return code.trim();
        }
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    /**
     * 按外部编码查询未软删的 RSPU（@TableLogic 自动过滤已删除记录）。
     *
     * @param externalCode 外部编码
     * @return 已有 RSPU，不存在或编码为空时为 null
     */
    private RspuMaster findRspuByExternalCode(String externalCode) {
        if (!StringUtils.hasText(externalCode)) {
            return null;
        }
        List<RspuMaster> list = rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().eq("external_code", externalCode.trim()));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * updateIfExists=true 时复用已有 RSPU：以本行数据更新可变字段并记审计日志。
     *
     * @param rspu      已有 RSPU 实体
     * @param row       本行数据
     * @param dictCache 字典缓存
     */
    private void updateExistingRspu(RspuMaster rspu, ProductImportRow row,
                                    Map<String, List<CategoryDict>> dictCache) {
        RspuMaster oldSnapshot = snapshotRspu(rspu);
        if (StringUtils.hasText(row.getVariantDisplayName())) {
            rspu.setProductName(row.getVariantDisplayName().trim());
        }
        // description/retailPrice 只补空缺：人工已填的内容不被 Excel 导入覆盖
        if (!StringUtils.hasText(rspu.getDescription()) && StringUtils.hasText(row.getDescription())) {
            rspu.setDescription(row.getDescription().trim());
        }
        if (rspu.getRetailPrice() == null && row.getRetailPrice() != null) {
            rspu.setRetailPrice(row.getRetailPrice());
        }
        rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
        rspu.setCategoryPath(CategoryPaths.resolve(rspu.getCategoryCode()));
        String primaryStyleName = splitCsv(row.getPositioningLabel()).stream().findFirst().orElse(null);
        if (StringUtils.hasText(primaryStyleName)) {
            rspu.setPositioningLabel(normalizeDictCode(primaryStyleName, dictCache.get("style")));
        }
        rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        rspu.setMaterialTags(toJson(splitCsv(row.getMaterialTags())));
        rspu.setSceneTags(toJson(splitCsv(row.getSceneTags())));
        rspu.setSixDimTags(trim(row.getSixDimTags()));
        rspu.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
            ? row.getReferencePriceBand().trim().toLowerCase()
            : null);
        rspu.setProductLevel(StringUtils.hasText(row.getProductLevel())
            ? normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"))
            : null);
        rspu.setWarrantyYears(row.getWarrantyYears());
        rspu.setKeySpecs(trim(row.getKeySpecs()));
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logUpdate("rspu_master", rspu.getRspuId(), oldSnapshot, rspu,
            SecurityOperatorContext.currentUsername());
    }

    /**
     * 生成 RSPU 更新前快照（用于审计日志）。
     * 实体中 JSONB 字段以 String + @JsonRawValue 存储，无法做 Jackson 序列化/反序列化往返，
     * 因此使用浅拷贝（字段均为不可变类型 String/Integer/LocalDateTime 等，浅拷贝即安全）。
     */
    private RspuMaster snapshotRspu(RspuMaster source) {
        if (source == null) {
            return null;
        }
        RspuMaster snapshot = new RspuMaster();
        BeanUtils.copyProperties(source, snapshot);
        return snapshot;
    }

    private void createRspuFactoryMapping(String rspuId, String factoryCode, String shippingWarehouseId,
                                          Integer moq, Integer baseLeadTimeDays, Long importRowId) {
        try {
            RspuFactoryMappingRequest mappingRequest = new RspuFactoryMappingRequest();
            mappingRequest.setRspuId(rspuId);
            mappingRequest.setFactoryCode(factoryCode);
            mappingRequest.setIsPrimary(true);
            mappingRequest.setShippingWarehouseId(shippingWarehouseId);
            mappingRequest.setMoq(moq);
            mappingRequest.setBaseLeadTimeDays(baseLeadTimeDays);
            mappingRequest.setStatus("active");
            rspuFactoryMappingService.saveMapping(mappingRequest);
        } catch (BusinessException e) {
            // 已存在则不报错
            if (!e.getMessage().contains("已关联此工厂")) {
                log.warn("创建 RSPU-工厂映射失败，rspuId={}, factoryCode={}", rspuId, factoryCode, e);
            }
        }
    }

    private Integer calculateLeadTime(String factoryCode, String categoryCode, String materialGradeCode,
                                      Integer defaultLeadTimeDays) {
        if (factoryCode == null) {
            return defaultLeadTimeDays;
        }
        Integer ruleDays = factoryLeadTimeRuleService.calculateLeadTime(factoryCode, categoryCode, materialGradeCode,
            "standard", 1);
        return ruleDays != null ? ruleDays : defaultLeadTimeDays;
    }

    private String resolveMaterialGradeCode(String materialName) {
        if (!StringUtils.hasText(materialName)) {
            return null;
        }
        return switch (materialName.trim()) {
            case "A级布" -> "FABRIC_A";
            case "AA级布" -> "FABRIC_AA";
            case "S级布" -> "FABRIC_S";
            case "SS级进口布" -> "FABRIC_SS";
            case "半皮" -> "LEATHER_HALF";
            case "A级全皮" -> "LEATHER_A";
            case "AA级全皮" -> "LEATHER_AA";
            case "S级全皮" -> "LEATHER_S";
            case "SS级全皮" -> "LEATHER_SS";
            default -> null;
        };
    }

    /**
     * 解析价格文本。支持多行单元格（如「4700\n特惠价」「3000\n元/平方」）——
     * 取首行并提取第一个数字；首行无数字（如纯备注「元/平方」）返回 null。
     */
    private BigDecimal parsePrice(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String firstLine = text.split("\\R")[0]
            .replace(",", "")
            .replace("¥", "")
            .replace("$", "")
            .replace("￥", "")
            .trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(firstLine);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveMaterialCode(String materialName, List<CategoryDict> materials) {
        if (!StringUtils.hasText(materialName) || materials == null) {
            return null;
        }
        String byAlias = dictAliasService.resolveAlias("material", materialName.trim());
        if (StringUtils.hasText(byAlias)) {
            return byAlias;
        }
        for (CategoryDict dict : materials) {
            if (materialName.equals(dict.getDictName()) || materialName.equals(dict.getDictCode())) {
                return dict.getDictCode();
            }
        }
        return null;
    }

    private String resolvePriceBand(BigDecimal price) {
        if (price == null) {
            return null;
        }
        // 简单分档：<=3000 low, <=8000 mid, >8000 high
        if (price.compareTo(new BigDecimal("3000")) <= 0) {
            return "low";
        }
        if (price.compareTo(new BigDecimal("8000")) <= 0) {
            return "mid";
        }
        return "high";
    }

    private String buildDefaultVariantName(ProductImportRow row) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(row.getSizeCode())) {
            parts.add(row.getSizeCode());
        }
        if (StringUtils.hasText(row.getColorCode())) {
            parts.add(row.getColorCode());
        }
        if (StringUtils.hasText(row.getMaterialCode())) {
            parts.add(row.getMaterialCode());
        }
        return parts.isEmpty() ? "默认变体" : String.join("-", parts);
    }

    private void saveStylesAndScenes(String rspuId, ProductImportRow row,
                                     Map<String, List<CategoryDict>> dictCache) {
        rspuStyleMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuStyle>()
            .eq("rspu_id", rspuId));
        // 风格支持多值（「中古风,奶油风」「中古风/奶油风」等写法）：
        // 第一个值为主风格，其余为辅风格，均可被风格筛选命中
        List<String> styleNames = splitCsv(row.getPositioningLabel());
        java.util.Set<String> seenStyleCodes = new java.util.HashSet<>();
        boolean firstStyle = true;
        for (String styleName : styleNames) {
            String styleCode = normalizeDictCode(styleName, dictCache.get("style"));
            if (styleCode == null || !seenStyleCodes.add(styleCode)) {
                continue;
            }
            RspuStyle style = new RspuStyle();
            style.setRspuId(rspuId);
            style.setDictType("style");
            style.setStyleCode(styleCode);
            style.setIsPrimary(firstStyle);
            style.setCreatedAt(LocalDateTime.now());
            rspuStyleMapper.insert(style);
            firstStyle = false;
        }

        rspuSceneMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuScene>()
            .eq("rspu_id", rspuId));
        List<String> sceneCodes = splitCsv(row.getSceneTags());
        for (String code : sceneCodes) {
            String sceneCode = normalizeDictCode(code, dictCache.get("scene"));
            if (!StringUtils.hasText(sceneCode)) {
                continue;
            }
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(sceneCode);
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
        }
    }

    /**
     * 将下载的图片写入对象存储（事务外调用）。
     *
     * <p>MinIO 不参与 DB 事务，必须先于行事务执行；存储失败的图片记入 rowIssues
     * （用户可在批次失败明细中看到），不再静默降级。</p>
     *
     * @param images    已下载的图片
     * @param rowIssues 行级问题收集器
     * @return 存储成功的图片列表
     */
    private List<StoredImage> storeImages(List<DownloadedImage> images, List<String> rowIssues) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<StoredImage> stored = new ArrayList<>();
        for (DownloadedImage downloaded : images) {
            String imageId = IdGenerator.imageId();
            String extension = resolveExtension(downloaded.contentType());
            String objectKey = "images/" + imageId + "." + extension;
            try {
                storageService.store(new ByteArrayInputStream(downloaded.bytes()), objectKey,
                    downloaded.bytes().length, downloaded.contentType());
            } catch (IOException e) {
                log.error("存储图片失败，imageId={}, source={}", imageId, downloaded.source(), e);
                rowIssues.add("图片存储失败: " + downloaded.source());
                continue;
            }
            stored.add(new StoredImage(imageId, objectKey, extension, downloaded.contentType(),
                downloaded.bytes().length, downloaded.primary(), downloaded.variantLevel()));
        }
        return stored;
    }

    /**
     * 登记已存储图片的 image_assets 元数据（事务内调用，纯数据库写入）。
     *
     * @param rspuId        所属 RSPU
     * @param variantId     所属变体（可为 null）
     * @param images        已存储的图片列表
     * @param allowPrimary  是否允许产生主图；false 时全部登记为详情图
     *                      （同产品模块行的规格图不应覆盖组主图）
     * @param strictPrimary 严格主图模式（表格有明确图片列时启用）：
     *                      true 时只有标记为 primary 的图（产品图样列的图）才能成为主图，
     *                      规格模块示例图等不再兜底升主图，行内无产品图样图则该产品无主图；
     *                      false 时保持旧行为（无 primary 标记则首图升主图）
     * @return 主图 objectKey，未产生主图时为 null
     */
    private String registerImages(String rspuId, String variantId, List<StoredImage> images,
                                  boolean allowPrimary, boolean strictPrimary) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        String primaryObjectKey = null;
        boolean hasPrimary = images.stream().anyMatch(StoredImage::primary);
        for (int i = 0; i < images.size(); i++) {
            StoredImage stored = images.get(i);
            boolean isPrimary = allowPrimary
                && (stored.primary() || (!strictPrimary && !hasPrimary && i == 0));
            ImageAssets imageAsset = new ImageAssets();
            imageAsset.setImageId(stored.imageId());
            imageAsset.setRspuId(rspuId);
            imageAsset.setVariantId(variantId);
            imageAsset.setImageType(isPrimary ? "white_bg" : "detail");
            imageAsset.setStoragePath(stored.objectKey());
            imageAsset.setPrimary(isPrimary);
            imageAsset.setAiProcessed(false);
            imageAsset.setFileSize(stored.size());
            imageAsset.setFormat(stored.extension());
            imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
            imageAsset.setCreatedAt(LocalDateTime.now());
            imageAssetsMapper.insert(imageAsset);
            auditLogService.logCreate("image_assets", stored.imageId(), imageAsset, SecurityOperatorContext.currentUsername());

            if (isPrimary) {
                primaryObjectKey = stored.objectKey();
            }
        }
        return primaryObjectKey;
    }

    private String createAsyncTask(String rspuId, String primaryObjectKey) {
        String taskId = IdGenerator.taskId();
        String imageId = primaryObjectKey.substring(primaryObjectKey.lastIndexOf('/') + 1, primaryObjectKey.lastIndexOf('.'));
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setProgress(0);
        try {
            task.setInputData(objectMapper.writeValueAsString(Map.of(
                "rspuId", rspuId,
                "imageId", imageId,
                "objectKey", primaryObjectKey,
                "originalFilename", primaryObjectKey
            )));
        } catch (Exception e) {
            log.warn("序列化任务输入失败", e);
        }
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        triggerAsyncProcess(taskId, rspuId, imageId, primaryObjectKey);
        return taskId;
    }

    private void triggerAsyncProcess(String taskId, String rspuId, String imageId, String objectKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchSafely(taskId, rspuId, imageId, objectKey);
                }
            });
        } else {
            dispatchSafely(taskId, rspuId, imageId, objectKey);
        }
    }

    /**
     * 投递异步任务。线程池队列满（AbortPolicy）时把任务立即标记为 failed，
     * 避免任务永远停留在 pending（收割器 10 分钟兜底前的即时反馈）。
     */
    private void dispatchSafely(String taskId, String rspuId, String imageId, String objectKey) {
        try {
            asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.error("异步任务投递被拒绝（线程池已满），taskId={}", taskId, e);
            try {
                com.rsdp.entity.AsyncTask rejected = new com.rsdp.entity.AsyncTask();
                rejected.setTaskId(taskId);
                rejected.setStatus("failed");
                rejected.setProgress(100);
                rejected.setErrorMessage("系统繁忙，任务投递被拒绝，请稍后重试");
                rejected.setCompletedAt(LocalDateTime.now());
                asyncTaskMapper.updateById(rejected);
            } catch (Exception ex) {
                log.error("标记被拒绝任务失败，taskId={}", taskId, ex);
            }
        }
    }

    private void applyBatchFactoryInfo(ExcelImportBatch batch, ExcelAiMappingRequest request) {
        batch.setFactoryCode(request.getDefaultFactoryCode());
        batch.setShippingWarehouseId(request.getShippingWarehouseId());
        batch.setShippingFrom(request.getDefaultShippingFrom());
        batch.setDefaultLeadTimeDays(request.getDefaultLeadTimeDays());
        batch.setDefaultMoq(request.getDefaultMoq());
        batch.setCategoryHint(request.getCategoryHint());
        batch.setImportNote(request.getImportNote());
    }

    private void updateBatchResult(ExcelImportBatch batch, ExcelAiMappingRequest request, ExcelAiImportResult result) {
        applyBatchFactoryInfo(batch, request);
        batch.setStatus("done");
        batch.setSuccessCount(result.getSuccessCount());
        batch.setFailedCount(result.getFailedCount());
        batch.setProcessedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        try {
            batch.setFailures(objectMapper.writeValueAsString(result.getFailures()));
        } catch (Exception e) {
            log.warn("序列化失败明细失败，batchId={}", batch.getBatchId(), e);
        }
        batchMapper.updateById(batch);
    }

    private String resolveExtension(String contentType) {
        if (contentType == null) {
            return "jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "jpg";
        };
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        for (String part : value.split("[，,/、／]")) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "[]";
        }
    }

    private String trim(String value) {
        return value != null ? value.trim() : null;
    }

    private static class AiMappingResult {
        Map<String, String> mapping;
        List<PriceColumnInfo> priceColumns = new ArrayList<>();
        String categoryGuess;
        String notes;
    }

    private record ProcessedMapping(Map<String, String> mapping, List<PriceColumnInfo> priceColumns) {
    }

    private record RowResult(String rspuId, String variantId, List<String> rskuIds,
                              Integer imageCount, List<String> imageAssetIds,
                              String taskId, boolean skipped, String skipReason,
                              String groupKey, boolean createdNewRspu, boolean primaryImageSaved,
                              List<String> issues) {

        static RowResult success(String rspuId, String variantId, List<String> rskuIds,
                                 Integer imageCount, List<String> imageAssetIds, String taskId,
                                 String groupKey, boolean createdNewRspu, boolean primaryImageSaved,
                                 List<String> issues) {
            return new RowResult(rspuId, variantId, rskuIds, imageCount, imageAssetIds, taskId, false, null,
                groupKey, createdNewRspu, primaryImageSaved, issues);
        }

        static RowResult skipped(String reason) {
            return new RowResult(null, null, null, 0, null, null, true, reason, null, false, false, null);
        }
    }

    /**
     * 变体 + RSKU 创建结果：首个变体 ID + 实际创建成功的 RSKU ID 列表。
     */
    private record VariantRskuOutcome(String firstVariantId, List<String> rskuIds) {
    }

    /**
     * 导入循环内维护的「当前产品组」状态：同型号（合并单元格语义）的连续模块行
     * 归入同一 RSPU，组内共享主图与 AI 识别任务。
     */
    private static class ProductGroup {
        String externalCode;
        String rspuId;
        boolean hasPrimaryImage;
        boolean hasAiTask;
    }

    /**
     * 从原始文件重建的物理布局：数据行物理行号序列 + 图片列物理索引 + 数据列边界 + 工作表名。
     *
     * @param dataRowPhysicalIndexes 数据行物理行号（0-based，与 previewRows 一一对应）
     * @param imageColumns           图片列的物理列索引（表头含 图样/picture/image 等）
     * @param minDataColumn          数据区域最小列索引
     * @param maxDataColumn          数据区域最大列索引（界外视为 logo/装饰图）
     * @param sheetName              批次工作表名（品类线索，不可得为 null）
     */
    private record PhysicalLayout(List<Integer> dataRowPhysicalIndexes, Set<Integer> imageColumns,
                                  int minDataColumn, int maxDataColumn, String sheetName) {
    }

    private record DownloadedImage(String source, byte[] bytes, String contentType, boolean primary,
                                   boolean variantLevel) {
    }

    /** 已写入对象存储的图片（事务外产出，事务内只登记元数据）。 */
    private record StoredImage(String imageId, String objectKey, String extension, String contentType,
                               long size, boolean primary, boolean variantLevel) {
    }

    /**
     * 行预处理结果（事务外产出）。{@code earlyResult} 非空表示该行应直接跳过（说明行/重复表头）。
     */
    private record PreparedRow(ProductImportRow row, List<String> rowIssues,
                               List<DownloadedImage> productImages, List<DownloadedImage> variantImages,
                               String groupKey, boolean sameProduct, boolean allowPrimary, boolean strictPrimary,
                               int imageCount, RowResult earlyResult) {
        static PreparedRow skip(RowResult earlyResult) {
            return new PreparedRow(null, null, null, null, null, false, false, false, 0, earlyResult);
        }
    }

    /**
     * 将 byte[] 包装为 MultipartFile，以便复用 ExcelImageExtractor。
     */
    private static class MultipartFileAdapter implements MultipartFile {

        private final byte[] bytes;
        private final String filename;

        MultipartFileAdapter(byte[] bytes, String filename) {
            this.bytes = bytes;
            this.filename = filename;
        }

        @Override
        public String getName() {
            return filename;
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            return bytes == null || bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException();
        }
    }
}
