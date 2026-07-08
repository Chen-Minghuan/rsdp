package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.ExcelAiImportFailure;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
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
        你是家具产品目录结构化专家。请将用户提供的 Excel 表头映射到标准字段。
        标准字段列表及常见中文表头同义词：
        - categoryCode（品类码，如 FS/SF/DT/BS/OF；对应：品类、分类、产品类别、CATEGORY）
        - externalCode（外部编码/型号；对应：型号、型号品名、ITEM NO、型号/品名、编码、编号）
        - productName（产品名称；对应：品名、名称、产品名称、DESCRIPTION、型号品名）
        - positioningLabel（风格，如中古风/意式/奶油风；对应：风格、定位标签、STYLE）
        - colorPrimaryName（主色；对应：颜色、主色、COLOR、色系）
        - materialTags（材质标签，多个用逗号分隔；对应：材质、材质说明、面料、布料、皮质、MATERIAL、材质说明）
        - sceneTags（场景，多个用逗号分隔；对应：场景、空间、SCENE、ROOM）
        - productLevel（产品等级，如 S/A/B/C；对应：等级、产品等级、LEVEL、GRADE）
        - warrantyYears（保修年限，数字；对应：保修、保修年限、WARRANTY）
        - referencePriceBand（价格带 low/mid/high；对应：价格带、参考价格带、PRICE BAND）
        - sixDimTags（六维标签 JSON；对应：六维标签、标签、TAGS）
        - keySpecs（关键规格 JSON；对应：规格、关键规格、SPEC、参数）
        - primaryImageUrl（主图 URL；对应：主图、图片、IMAGE、产品图样）
        - detailImageUrls（详情图 URLs，多个用逗号分隔；对应：详情图、附图、DETAIL IMAGES）
        - variantDisplayName（变体显示名称；对应：变体、规格/模块、Modular Components、变体名称）
        - sizeCode（尺寸码，如 S/M/L/SINGLE；对应：尺寸码、尺码、SIZE CODE）
        - colorCode（颜色码；对应：颜色码、COLOR CODE）
        - materialCode（材质码，字典码如 WO/PE/FA；对应：材质码、面料码、MATERIAL CODE）
        - dimensions（尺寸文字，如 800*900*1000mm；对应：尺寸、产品尺寸、SIZE（CM）、DIMENSIONS）

        注意：
        - 材质说明/材质等中文描述性文字优先映射到 materialTags；如果是字典码（如 PE/WO/FA）则映射到 materialCode。
        - 产品图样/PICTURE 等含图片的列无需映射，系统会自动提取每行第一个内嵌图片作为主图。
        - 价格、出厂价、交期、MOQ、工厂编码属于 RSKU 供应信息，当前 MVP 导入时暂不写入 RSKU。

        输出 JSON：
        {
          "mapping": {"原始表头1": "标准字段", "原始表头2": "标准字段"},
          "categoryGuess": "FS",
          "notes": "说明"
        }
        约束：
        - 只能使用上面列出的标准字段作为 value。
        - 无法映射的表头 value 填 null。
        - 不要输出任何其他文字。
        """;

    private final ExcelImportBatchMapper batchMapper;
    private final VisionService visionService;
    private final RspuMapper rspuMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final RspuVariantService rspuVariantService;
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

        Map<Integer, String> headerMap = rawRows.get(0);
        List<Map<Integer, String>> dataRows = rawRows.subList(1, rawRows.size());

        String headersText = buildHeadersText(headerMap);
        String previewText = buildPreviewText(headerMap, dataRows);
        String imageHint = "如果 Excel 包含内嵌图片，主图无需映射，系统会自动将每行第一个内嵌图片作为主图。";

        String aiResponse = visionService.chatText(MAPPING_SYSTEM_PROMPT,
            "表头：\n" + headersText + "\n\n样例数据（前 " + Math.min(dataRows.size(), PREVIEW_ROW_COUNT) + " 行）：\n"
                + previewText + "\n\n" + imageHint);

        AiMappingResult mappingResult = parseMappingResponse(aiResponse);

        String storagePath = storeOriginalFile(fileBytes, file.getOriginalFilename());
        ExcelImportBatch batch = saveBatch(file.getOriginalFilename(), storagePath, mappingResult,
            headerMap, dataRows);

        return buildMappingResponse(batch, headerMap, mappingResult);
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

        Map<String, String> mapping = request.getMapping();
        if (mapping == null || mapping.isEmpty()) {
            throw new BusinessException("字段映射不能为空");
        }

        List<Map<String, String>> rawDataRows = loadRawDataRows(batch);
        if (rawDataRows.isEmpty()) {
            throw new BusinessException("Excel 数据为空，无法导入");
        }

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
                    embeddedImages, dictCache, rowIndex);
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
                                              Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages,
                                              Map<String, List<CategoryDict>> dictCache, int rowIndex) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            RowResult result = processRow(dataRow, mapping, categoryHint, embeddedImages, dictCache, rowIndex);
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
                                 Map<String, List<ExcelImageExtractor.EmbeddedImage>> embeddedImages,
                                 Map<String, List<CategoryDict>> dictCache, int rowIndex) {
        ProductImportRow row = buildProductImportRow(dataRow, mapping, categoryHint, dictCache);
        String error = validateRow(row, dictCache);
        if (error != null) {
            throw new BusinessException(error);
        }

        List<DownloadedImage> images = new ArrayList<>();
        images.addAll(downloadUrlImages(row, rowIndex));
        images.addAll(extractEmbeddedImagesForRow(embeddedImages, rowIndex));

        String rspuId = createRspu(row, dictCache);
        String variantId = createVariantIfNeeded(rspuId, row, dictCache);
        saveStylesAndScenes(rspuId, row, dictCache);
        String primaryObjectKey = saveImages(rspuId, variantId, images);

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
            if (StringUtils.hasText(standardField)) {
                standardValues.put(standardField, entry.getValue());
            }
        }

        row.setExternalCode(getValue(standardValues, "externalCode"));
        row.setVariantDisplayName(getValue(standardValues, "productName"));
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
            if (ImageUrlValidator.isAllowed(url, allowedImageHosts)) {
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
        String categoryGuess;
        String notes;
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
