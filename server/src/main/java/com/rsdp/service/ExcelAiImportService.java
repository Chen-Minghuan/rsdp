package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.ExcelAiImportFailure;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
import com.rsdp.dto.response.PriceColumnInfo;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ExcelFileValidator;
import com.rsdp.util.ExcelHeaderNormalizer;
import com.rsdp.util.ExcelImageExtractor;
import com.rsdp.util.ImageUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        - sizeCode（尺寸码，如 S/M/L/SINGLE；对应：尺寸码、尺码、SIZE CODE）
        - colorCode（颜色码；对应：颜色码、COLOR CODE）
        - materialCode（材质码，字典码如 WO/PE/FA；对应：材质码、面料码、MATERIAL CODE）
        - dimensions（尺寸文字，如 800*900*1000mm；对应：尺寸、产品尺寸、SIZE CM、DIMENSIONS）

        复合表头处理规则：
        - 「型号品名」这类同时包含型号和名称的列，请映射为 "externalCode,productName"（用英文逗号分隔）。
        - 「规格/模块」映射为 variantDisplayName。
        - 「产品尺寸」映射为 dimensions。

        价格列处理规则（非常重要）：
        - 清洗后的表头可能是「价格-A级布」「价格-AA级布」「价格-S级布」「价格-半皮」等父子表头。
        - 每一列价格都对应一个材质版本的变体，请把这种列映射为特殊字段："__PRICE__:材质名"。
        - 例如：「价格-A级布」→ "__PRICE__:A级布"；「价格-半皮」→ "__PRICE__:半皮"。
        - 不要把价格列映射为 referencePriceBand。

        无需映射的列：
        - 产品图样/PICTURE/图片/IMAGE 等含图片列：value 填 null，系统会自动提取内嵌图片。
        - 交期/PRODUCTION CYCLE 等供应字段：当前作为整行共用属性处理，单独列 value 填 null。

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

    @Value("${rsdp.import.allowed-image-hosts:}")
    private Set<String> allowedImageHosts = Set.of();

    /**
     * 上传 Excel 并请求 AI 生成字段映射预览。
     *
     * @param file Excel 文件
     * @return 映射预览响应
     */
    @org.springframework.transaction.annotation.Transactional
    public ExcelAiMappingResponse previewMapping(MultipartFile file) {
        validateFile(file);

        byte[] fileBytes = readFileBytes(file);
        List<Map<Integer, String>> rawRows = parseExcelRaw(fileBytes);
        if (rawRows.isEmpty()) {
            throw new BusinessException("Excel 文件为空");
        }
        if (rawRows.size() > MAX_ROWS + 1) {
            throw new BusinessException("单次导入不能超过 " + MAX_ROWS + " 行数据");
        }

        // 检测并合并多行表头
        int headerRowCount = detectHeaderRowCount(rawRows);
        List<Map<Integer, String>> headerRows = rawRows.subList(0, headerRowCount);
        List<Map<Integer, String>> dataRows = rawRows.subList(headerRowCount, rawRows.size());

        Map<Integer, String> mergedHeaders = ExcelHeaderNormalizer.mergeHeaderRows(headerRows);
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
            mergedHeaders, dataRows);

        return buildMappingResponse(batch, mergedHeaders, mappingResult);
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
        if (!"pending".equals(batch.getStatus())) {
            throw new BusinessException("批次状态不允许重复导入: " + batch.getStatus());
        }

        Map<String, String> mapping = sanitizeMapping(request.getMapping());
        if (mapping == null || mapping.isEmpty()) {
            throw new BusinessException("字段映射不能为空");
        }

        List<Map<String, String>> rawDataRows = loadRawDataRows(batch);
        if (rawDataRows.isEmpty()) {
            throw new BusinessException("Excel 数据为空，无法导入");
        }

        List<PriceColumnInfo> priceColumns = loadPriceColumns(batch);
        List<PriceColumnInfo> selectedPriceColumns = filterSelectedPriceColumns(priceColumns, request.getSelectedPriceColumns());

        // 从 storage 读取原始 Excel，提取内嵌图片
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages = loadEmbeddedImages(batch);

        Map<String, List<CategoryDict>> dictCache = preloadDicts();
        ExcelAiImportResult result = new ExcelAiImportResult();
        result.setBatchId(batch.getBatchId());
        result.setTotalRows(rawDataRows.size());

        List<ExcelAiImportFailure> failures = new ArrayList<>();
        List<String> rspuIds = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();

        int rowIndex = 1; // 第 1 行为表头
        for (Map<String, String> dataRow : rawDataRows) {
            rowIndex++;
            try {
                RowResult rowResult = processRowInTransaction(dataRow, mapping, request.getCategoryHint(),
                    selectedPriceColumns, request, embeddedImages, dictCache, rowIndex);
                if (rowResult.rspuId != null) {
                    rspuIds.add(rowResult.rspuId);
                }
                if (rowResult.taskId != null) {
                    taskIds.add(rowResult.taskId);
                }
            } catch (BusinessException e) {
                failures.add(new ExcelAiImportFailure(rowIndex, e.getMessage()));
            } catch (Exception e) {
                log.error("Excel AI 导入行处理异常，rowIndex={}", rowIndex, e);
                failures.add(new ExcelAiImportFailure(rowIndex, "系统异常: " + e.getMessage()));
            }
        }

        result.setSuccessCount(rspuIds.size());
        result.setFailedCount(failures.size());
        result.setRspuIds(rspuIds);
        result.setTaskIds(taskIds);
        result.setFailures(failures);

        updateBatchResult(batch, result);
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
        return response;
    }

    // ==================== 私有方法 ====================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 10MB");
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

    private List<Map<Integer, String>> parseExcelRaw(byte[] fileBytes) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try (InputStream stream = new ByteArrayInputStream(fileBytes)) {
            EasyExcel.read(stream, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> row, AnalysisContext context) {
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            }).sheet().headRowNumber(0).doRead();
        } catch (Exception e) {
            log.error("解析 Excel 失败", e);
            throw new BusinessException("解析 Excel 失败，请检查文件格式");
        }
        return rows;
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

    private int detectHeaderRowCount(List<Map<Integer, String>> rawRows) {
        if (rawRows.size() >= 2 && ExcelHeaderNormalizer.looksLikeHeaderRow(rawRows.get(1))) {
            return 2;
        }
        return 1;
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
                priceColumns.add(info);
                continue;
            }

            result.put(mergedHeader, standardField);
        }
        return new ProcessedMapping(result, priceColumns);
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
            || h.contains("photo") || h.contains("产品图");
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
                || v.startsWith("由于") || v.contains("特别声明") || v.contains("免责声明")) {
                return true;
            }
        }
        return !hasAnyValue;
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
        if (h.contains("尺寸") || h.contains("规格") || h.contains("size") || h.contains("dimension")) {
            return "dimensions";
        }
        if (h.contains("尺寸码") || h.contains("尺码") || h.contains("size code")) {
            return "sizeCode";
        }
        if (h.contains("颜色码") || h.contains("color code")) {
            return "colorCode";
        }
        if (h.contains("变体") || h.contains("模块") || h.contains("module") || h.contains("component")) {
            return "variantDisplayName";
        }
        if (h.contains("价格") || h.contains("price") || h.contains("出厂价")) {
            return "referencePriceBand";
        }
        if (h.contains("图") || h.contains("picture") || h.contains("image") || h.contains("photo")) {
            return null; // 图片列不映射，由内嵌图片提取处理
        }
        return null;
    }

    private String storeOriginalFile(byte[] fileBytes, String originalFilename) {
        String batchId = "EXCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
                                       Map<Integer, String> headerMap, List<Map<Integer, String>> dataRows) {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId("BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        batch.setFileName(fileName);
        batch.setStoragePath(storagePath);
        batch.setStatus("pending");
        batch.setTotalRows(dataRows.size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setCreatedBy(SecurityOperatorContext.currentUserId());
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());

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

        batchMapper.insert(batch);
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
        if (selectedHeaders == null || selectedHeaders.isEmpty()) {
            return all;
        }
        return all.stream()
            .filter(p -> selectedHeaders.contains(p.getHeader()))
            .toList();
    }

    private Map<String, List<ExcelImageExtractor.EmbeddedImage>> loadEmbeddedImages(ExcelImportBatch batch) {
        if (!StringUtils.hasText(batch.getStoragePath())) {
            return Collections.emptyMap();
        }
        try (InputStream in = storageService.get(batch.getStoragePath())) {
            byte[] bytes = in.readAllBytes();
            return ExcelImageExtractor.extract(new MultipartFileAdapter(bytes, batch.getFileName()));
        } catch (Exception e) {
            log.warn("提取 Excel 内嵌图片失败，batchId={}", batch.getBatchId(), e);
            return Collections.emptyMap();
        }
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
                                              Map<String, List<CategoryDict>> dictCache, int rowIndex) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            RowResult result = processRow(dataRow, mapping, categoryHint, priceColumns, request,
                embeddedImages, dictCache, rowIndex);
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

    private RowResult processRow(Map<String, String> dataRow, Map<String, String> mapping, String categoryHint,
                                 List<PriceColumnInfo> priceColumns,
                                 ExcelAiMappingRequest request,
                                 Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages,
                                 Map<String, List<CategoryDict>> dictCache, int rowIndex) {
        if (isNoteOrEmptyRow(dataRow)) {
            log.debug("第 {} 行为说明或空行，已跳过", rowIndex);
            return new RowResult(null, null);
        }

        ProductImportRow row = buildProductImportRow(dataRow, mapping, categoryHint, dictCache);
        String error = validateRow(row, dictCache);
        if (error != null) {
            throw new BusinessException(error);
        }

        List<DownloadedImage> images = new ArrayList<>();
        images.addAll(downloadUrlImages(row, rowIndex));
        images.addAll(extractEmbeddedImagesForRow(embeddedImages, rowIndex));

        String rspuId = createRspu(row, dictCache);
        saveStylesAndScenes(rspuId, row, dictCache);
        String primaryObjectKey = saveImages(rspuId, null, images);

        // 为每个选中的价格列创建变体 + RSKU
        if (priceColumns != null && !priceColumns.isEmpty()) {
            createVariantsAndRskus(rspuId, dataRow, row, priceColumns, request, dictCache);
        } else {
            // 没有价格列时，创建默认变体
            String variantId = createVariantIfNeeded(rspuId, row, dictCache);
            saveImages(rspuId, variantId, List.of()); // 已保存过主图，这里只是占位
        }

        String taskId = null;
        if (primaryObjectKey != null) {
            taskId = createAsyncTask(rspuId, primaryObjectKey);
        }

        return new RowResult(rspuId, taskId);
    }

    private ProductImportRow buildProductImportRow(Map<String, String> dataRow, Map<String, String> mapping,
                                                   String categoryHint, Map<String, List<CategoryDict>> dictCache) {
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
        row.setVariantDisplayName(getValue(standardValues, "productName"));

        // 复合表头"型号品名"的值同时包含型号和品名时，尝试拆分
        splitExternalCodeAndProductName(row);
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

        String categoryCode = getValue(standardValues, "categoryCode");
        if (!StringUtils.hasText(categoryCode)) {
            categoryCode = categoryHint;
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

    private void splitExternalCodeAndProductName(ProductImportRow row) {
        String externalCode = row.getExternalCode();
        String productName = row.getVariantDisplayName();
        if (!StringUtils.hasText(externalCode) || !externalCode.equals(productName)) {
            return;
        }
        // 常见分隔：空格、斜杠、竖线、中文斜杠
        String combined = externalCode;
        int idx = findSplitIndex(combined);
        if (idx > 0) {
            row.setExternalCode(combined.substring(0, idx).trim());
            row.setVariantDisplayName(combined.substring(idx + 1).trim());
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
            String styleCode = normalizeDictCode(row.getPositioningLabel(), dictCache.get("style"));
            if (!isValidDictCode(styleCode, dictCache.get("style"))) {
                return "定位标签不存在: " + row.getPositioningLabel();
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
        if (StringUtils.hasText(row.getSizeCode())) {
            String sizeCode = normalizeDictCode(row.getSizeCode(), dictCache.get("size"));
            if (!isValidDictCode(sizeCode, dictCache.get("size"))) {
                return "尺寸码不存在: " + row.getSizeCode();
            }
        }
        if (StringUtils.hasText(row.getColorCode())) {
            String colorCode = normalizeDictCode(row.getColorCode(), dictCache.get("color"));
            if (!isValidDictCode(colorCode, dictCache.get("color"))) {
                return "颜色码不存在: " + row.getColorCode();
            }
        }
        if (StringUtils.hasText(row.getMaterialCode())) {
            String materialCode = normalizeDictCode(row.getMaterialCode(), dictCache.get("material"));
            if (!isValidDictCode(materialCode, dictCache.get("material"))) {
                return "材质码不存在: " + row.getMaterialCode();
            }
        }
        return null;
    }

    private boolean isValidDictCode(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equalsIgnoreCase(d.getDictCode()));
    }

    private String normalizeDictCode(String input, List<CategoryDict> dicts) {
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
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_SIZE) {
                    return null;
                }
                return new DownloadedImage(url, bytes, contentType, primary);
            }
        } catch (Exception e) {
            log.warn("下载图片失败: {}", url, e);
            return null;
        }
    }

    private List<DownloadedImage> extractEmbeddedImagesForRow(
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages, int rowIndex) {
        // rowIndex 是全局数据行号（从 2 开始，第 1 行为表头）
        // Excel 物理行号 = rowIndex - 1（因为表头占第 0 行）
        // ExcelImageExtractor 的 key 格式为 "sheetIndex,physicalRowIndex"
        // rowIndex 从表头开始为 1，表头对应 physicalRowIndex=0，所以 physicalRowIndex = rowIndex - 1
        // 这里只处理第一个 Sheet 的内嵌图片，后续可扩展
        int physicalRowIndex = rowIndex - 1;
        String key = "0," + physicalRowIndex;
        List<ExcelImageExtractor.EmbeddedImage> images = embeddedImages.get(key);
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<DownloadedImage> result = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ExcelImageExtractor.EmbeddedImage img = images.get(i);
            String contentType = switch (img.extension().toLowerCase()) {
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                default -> "image/jpeg";
            };
            result.add(new DownloadedImage("embedded://" + img.rowIndex() + "-" + img.colIndex(),
                img.bytes(), contentType, i == 0));
        }
        return result;
    }

    private String createRspu(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        String rspuId = "RSPU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
        rspu.setCategoryPath(resolveCategoryPath(rspu.getCategoryCode()));
        rspu.setPositioningLabel(StringUtils.hasText(row.getPositioningLabel())
            ? normalizeDictCode(row.getPositioningLabel(), dictCache.get("style"))
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
            || StringUtils.hasText(row.getSizeCode())
            || StringUtils.hasText(row.getColorCode())
            || StringUtils.hasText(row.getMaterialCode());

        if (!hasVariantInfo) {
            return null;
        }

        String displayName = StringUtils.hasText(row.getVariantDisplayName())
            ? row.getVariantDisplayName().trim()
            : buildDefaultVariantName(row);

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName(displayName);
        request.setSizeCode(StringUtils.hasText(row.getSizeCode())
            ? normalizeDictCode(row.getSizeCode(), dictCache.get("size"))
            : null);
        request.setColorCode(StringUtils.hasText(row.getColorCode())
            ? normalizeDictCode(row.getColorCode(), dictCache.get("color"))
            : null);
        request.setMaterialCode(StringUtils.hasText(row.getMaterialCode())
            ? normalizeDictCode(row.getMaterialCode(), dictCache.get("material"))
            : null);
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

    private void createVariantsAndRskus(String rspuId, Map<String, String> dataRow, ProductImportRow baseRow,
                                        List<PriceColumnInfo> priceColumns, ExcelAiMappingRequest request,
                                        Map<String, List<CategoryDict>> dictCache) {
        Integer leadTimeDays = parseLeadTimeDays(dataRow);
        Integer moq = request.getDefaultMoq() != null ? request.getDefaultMoq() : 1;
        String factoryCode = StringUtils.hasText(request.getDefaultFactoryCode())
            ? request.getDefaultFactoryCode()
            : null;
        String shippingFrom = StringUtils.hasText(request.getDefaultShippingFrom())
            ? request.getDefaultShippingFrom()
            : null;

        for (PriceColumnInfo priceColumn : priceColumns) {
            String priceText = dataRow.getOrDefault(priceColumn.getHeader(), "").trim();
            if (!StringUtils.hasText(priceText)) {
                continue;
            }
            BigDecimal price = parsePrice(priceText);
            if (price == null) {
                log.warn("价格解析失败，header={}, value={}", priceColumn.getHeader(), priceText);
                continue;
            }

            String materialName = priceColumn.getMaterialName();
            String materialCode = resolveMaterialCode(materialName, dictCache.get("material"));

            // 创建变体
            RspuVariantCreateRequest variantRequest = new RspuVariantCreateRequest();
            variantRequest.setDisplayName(StringUtils.hasText(materialName) ? materialName : "默认变体");
            variantRequest.setMaterialCode(materialCode);
            variantRequest.setReferencePriceBand(resolvePriceBand(price));
            variantRequest.setProductLevel(baseRow.getProductLevel());
            var variantResponse = rspuVariantService.createVariant(rspuId, variantRequest);
            if (variantResponse == null || !StringUtils.hasText(variantResponse.getVariantId())) {
                log.warn("为价格列创建变体失败，header={}", priceColumn.getHeader());
                continue;
            }

            // 创建 RSKU
            if (factoryCode != null) {
                RskuCreateRequest rskuRequest = new RskuCreateRequest();
                rskuRequest.setRspuId(rspuId);
                rskuRequest.setVariantId(variantResponse.getVariantId());
                rskuRequest.setFactoryCode(factoryCode);
                rskuRequest.setFactoryPrice(price);
                rskuRequest.setMaterialCode(materialCode);
                rskuRequest.setMaterialDescription(materialName);
                rskuRequest.setLeadTimeDays(leadTimeDays);
                rskuRequest.setMoq(moq);
                rskuRequest.setShippingFrom(shippingFrom);
                rskuRequest.setProductLevel(baseRow.getProductLevel());
                try {
                    rskuService.createRsku(rskuRequest);
                } catch (Exception e) {
                    log.warn("为价格列创建 RSKU 失败，header={}", priceColumn.getHeader(), e);
                }
            }
        }
    }

    private Integer parseLeadTimeDays(Map<String, String> dataRow) {
        for (Map.Entry<String, String> entry : dataRow.entrySet()) {
            String header = ExcelHeaderNormalizer.clean(entry.getKey());
            if (header.contains("交期") || header.contains("周期") || header.contains("cycle")) {
                return parseInt(entry.getValue());
            }
        }
        return null;
    }

    private BigDecimal parsePrice(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String cleaned = text.replace(",", "")
            .replace("¥", "")
            .replace("$", "")
            .replace("￥", "")
            .trim();
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveMaterialCode(String materialName, List<CategoryDict> materials) {
        if (!StringUtils.hasText(materialName) || materials == null) {
            return null;
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
        if (StringUtils.hasText(row.getPositioningLabel())) {
            String styleCode = normalizeDictCode(row.getPositioningLabel(), dictCache.get("style"));
            if (styleCode != null) {
                RspuStyle style = new RspuStyle();
                style.setRspuId(rspuId);
                style.setDictType("style");
                style.setStyleCode(styleCode);
                style.setIsPrimary(true);
                style.setCreatedAt(LocalDateTime.now());
                rspuStyleMapper.insert(style);
            }
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

    private String saveImages(String rspuId, String variantId, List<DownloadedImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        String primaryObjectKey = null;
        boolean hasPrimary = images.stream().anyMatch(DownloadedImage::primary);
        for (int i = 0; i < images.size(); i++) {
            DownloadedImage downloaded = images.get(i);
            String imageId = "IMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String extension = resolveExtension(downloaded.contentType);
            String objectKey = "images/" + imageId + "." + extension;

            try {
                storageService.store(new ByteArrayInputStream(downloaded.bytes), objectKey,
                    downloaded.bytes.length, downloaded.contentType);
            } catch (IOException e) {
                log.error("存储图片失败，rspuId={}, imageId={}", rspuId, imageId, e);
                continue;
            }

            boolean isPrimary = downloaded.primary || (!hasPrimary && i == 0);
            ImageAssets imageAsset = new ImageAssets();
            imageAsset.setImageId(imageId);
            imageAsset.setRspuId(rspuId);
            imageAsset.setVariantId(variantId);
            imageAsset.setImageType(isPrimary ? "white_bg" : "detail");
            imageAsset.setStoragePath(objectKey);
            imageAsset.setPrimary(isPrimary);
            imageAsset.setAiProcessed(false);
            imageAsset.setFileSize((long) downloaded.bytes.length);
            imageAsset.setFormat(extension);
            imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
            imageAsset.setCreatedAt(LocalDateTime.now());
            imageAssetsMapper.insert(imageAsset);
            auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());

            if (isPrimary) {
                primaryObjectKey = objectKey;
            }
        }
        return primaryObjectKey;
    }

    private String createAsyncTask(String rspuId, String primaryObjectKey) {
        String taskId = "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
                    asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);
                }
            });
        } else {
            asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);
        }
    }

    private void updateBatchResult(ExcelImportBatch batch, ExcelAiImportResult result) {
        batch.setStatus("done");
        batch.setSuccessCount(result.getSuccessCount());
        batch.setFailedCount(result.getFailedCount());
        batch.setUpdatedAt(LocalDateTime.now());
        try {
            batch.setFailures(objectMapper.writeValueAsString(result.getFailures()));
        } catch (Exception e) {
            log.warn("序列化失败明细失败，batchId={}", batch.getBatchId(), e);
        }
        batchMapper.updateById(batch);
    }

    private String resolveCategoryPath(String categoryCode) {
        return switch (categoryCode) {
            case "SF" -> "[\"家具\",\"沙发\"]";
            case "TB" -> "[\"家具\",\"茶几\"]";
            case "FC" -> "[\"家具\",\"柜类\"]";
            case "BS" -> "[\"家具\",\"吧椅\"]";
            case "DT" -> "[\"家具\",\"桌子\"]";
            case "CB" -> "[\"家具\",\"柜子\"]";
            case "BD" -> "[\"家具\",\"床\"]";
            case "OF" -> "[\"办公家具\"]";
            default -> "[\"家具\",\"座椅\",\"休闲椅\",\"单椅\"]";
        };
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
        for (String part : value.split("[，,]")) {
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

    private record RowResult(String rspuId, String taskId) {
    }

    private record DownloadedImage(String source, byte[] bytes, String contentType, boolean primary) {
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
