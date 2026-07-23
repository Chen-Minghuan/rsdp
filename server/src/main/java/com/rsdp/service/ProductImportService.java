package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.response.ProductImportFailure;
import com.rsdp.dto.response.ProductImportResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.util.CategoryPaths;
import com.rsdp.util.ExcelFileValidator;
import com.rsdp.util.ImageUrlValidator;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.rsdp.util.IdGenerator;

/**
 * 产品（RSPU）Excel 批量导入服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final int MAX_ROWS = 500;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_IMAGE_SIZE = 20 * 1024 * 1024; // 20 MB

    private final RspuMapper rspuMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final VariantCodeMapper variantCodeMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final DictService dictService;
    private final AuditLogService auditLogService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final DictAliasService dictAliasService;
    private final DictUnresolvedService dictUnresolvedService;

    private java.util.Set<String> allowedImageHosts = java.util.Set.of();

    @org.springframework.beans.factory.annotation.Value("${rsdp.import.allowed-image-hosts:}")
    public void setAllowedImageHosts(java.util.Set<String> allowedImageHosts) {
        this.allowedImageHosts = allowedImageHosts == null ? java.util.Set.of() : allowedImageHosts;
    }

    /**
     * 批量导入产品。
     *
     * @param file           Excel 文件
     * @param updateIfExists 当 RSPU ID 或外部编码已存在时，是否更新；false 则跳过
     * @return 导入结果
     */
    public ProductImportResult importProducts(MultipartFile file, boolean updateIfExists) {
        validateFile(file);

        List<ProductImportRow> rows = parseExcel(file);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException("单次导入不能超过 " + MAX_ROWS + " 行");
        }

        ProductImportResult result = new ProductImportResult();
        result.setTotalRows(rows.size());

        // 预加载字典，避免每行重复查询
        Map<String, List<CategoryDict>> dictCache = preloadDicts();
        // 预加载 Excel 内「外部编码 → RSPU ID」映射，使同一产品无论用哪种编码引用都能识别为重复
        Map<String, String> externalCodeToRspuId = preloadExternalCodeMapping(rows);
        Set<String> processedExternalCodes = new HashSet<>();
        Set<String> processedRspuIds = new HashSet<>();

        int rowIndex = 1; // Excel 行号，第 1 行为表头，数据从第 2 行开始
        for (ProductImportRow row : rows) {
            rowIndex++;
            normalizeRow(row, dictCache);
            String error = validateRow(row, dictCache);
            if (error != null) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(), error));
                continue;
            }

            // Excel 内重复检测：同一产品分别用 rspuId 和 externalCode 引用时也识别为重复
            String rowRspuId = StringUtils.hasText(row.getRspuId()) ? row.getRspuId().trim() : null;
            String rowExternalCode = StringUtils.hasText(row.getExternalCode()) ? row.getExternalCode().trim() : null;
            String effectiveRspuId = rowRspuId != null
                ? rowRspuId
                : (rowExternalCode != null ? externalCodeToRspuId.get(rowExternalCode) : null);
            boolean duplicated = (effectiveRspuId != null && !processedRspuIds.add(effectiveRspuId))
                || (rowExternalCode != null && !processedExternalCodes.add(rowExternalCode));
            if (duplicated) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "Excel 中存在重复产品行（同一 RSPU ID 或外部编码）"));
                continue;
            }

            List<DownloadedImage> images = downloadImages(row, rowIndex, result);

            try {
                String rspuId = processRowInTransaction(row, images, updateIfExists, dictCache, rowIndex, result);
                result.setSuccessCount(result.getSuccessCount() + 1);
                processedRspuIds.add(rspuId);
            } catch (BusinessException e) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(), e.getMessage()));
            } catch (Exception e) {
                log.error("导入产品失败，rowIndex={}", rowIndex, e);
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "系统异常: " + e.getMessage()));
            }
        }

        result.setFailedCount(result.getFailures().size());
        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 10MB");
        }
        if (!ExcelFileValidator.isExcelOrCsv(file)) {
            throw new BusinessException("仅支持 Excel (.xlsx/.xls) 或 CSV 文件，请检查文件内容是否被篡改");
        }
    }

    private List<ProductImportRow> parseExcel(MultipartFile file) {
        List<ProductImportRow> rows = new ArrayList<>();
        try (InputStream stream = file.getInputStream()) {
            EasyExcel.read(stream, ProductImportRow.class, new ReadListener<ProductImportRow>() {
                @Override
                public void invoke(ProductImportRow row, AnalysisContext context) {
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().doRead();
        } catch (IOException e) {
            log.error("读取 Excel 文件失败", e);
            throw new BusinessException("读取 Excel 文件失败", e);
        } catch (Exception e) {
            log.error("解析 Excel 文件失败，请检查文件格式是否与模板一致", e);
            throw new BusinessException("解析 Excel 文件失败，请检查文件格式是否与模板一致", e);
        }
        return rows;
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

    /**
     * 预加载 Excel 中出现的「外部编码 → RSPU ID」映射。
     *
     * <p>同一产品可能在不同行分别用 rspuId 和 externalCode 引用，提前解析映射
     * 才能在行级重复检测中识别为同一产品。已软删除的产品由 @TableLogic 自动排除。</p>
     *
     * @param rows 导入行
     * @return 外部编码 → RSPU ID 映射
     */
    private Map<String, String> preloadExternalCodeMapping(List<ProductImportRow> rows) {
        List<String> codes = rows.stream()
            .map(ProductImportRow::getExternalCode)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(
                new QueryWrapper<RspuMaster>()
                    .select("rspu_id", "external_code")
                    .in("external_code", codes)
            ).stream()
            .filter(r -> StringUtils.hasText(r.getExternalCode()))
            .collect(Collectors.toMap(
                r -> r.getExternalCode().trim(),
                RspuMaster::getRspuId,
                (a, b) -> a
            ));
    }

    /**
     * 下载图片，返回下载成功的图片列表；图片下载失败作为失败明细记录。
     */
    private List<DownloadedImage> downloadImages(ProductImportRow row, int rowIndex, ProductImportResult result) {
        List<DownloadedImage> images = new ArrayList<>();

        String primaryUrl = trim(row.getPrimaryImageUrl());
        if (StringUtils.hasText(primaryUrl)) {
            if (!ImageUrlValidator.isAllowed(primaryUrl, allowedImageHosts)) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "不安全的图片 URL: " + primaryUrl));
            } else {
                DownloadedImage image = downloadSingleImage(primaryUrl, true);
                if (image != null) {
                    images.add(image);
                } else {
                    result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                        "主图下载失败: " + primaryUrl));
                }
            }
        }

        List<String> detailUrls = splitCsv(row.getDetailImageUrls());
        for (String url : detailUrls) {
            if (!ImageUrlValidator.isAllowed(url, allowedImageHosts)) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "不安全的图片 URL: " + url));
            } else {
                DownloadedImage image = downloadSingleImage(url, false);
                if (image != null) {
                    images.add(image);
                } else {
                    result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                        "详情图下载失败: " + url));
                }
            }
        }

        // 图片下载失败不影响产品数据导入，只记录失败明细
        return images;
    }

    private DownloadedImage downloadSingleImage(String url, boolean primary) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String trimmedUrl = url.trim();
        if (!ImageUrlValidator.isAllowed(trimmedUrl, allowedImageHosts)) {
            log.warn("不安全的图片 URL: {}", trimmedUrl);
            return null;
        }
        try {
            URLConnection connection = new URL(trimmedUrl).openConnection();
            // 关闭自动重定向，防止 SSRF 通过 302 跳转到内网地址
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
            }
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            String contentType = connection.getContentType();
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length == 0) {
                    log.warn("图片 URL 返回空内容: {}", trimmedUrl);
                    return null;
                }
                if (bytes.length > MAX_IMAGE_SIZE) {
                    log.warn("图片超过最大限制 {}MB: {}", MAX_IMAGE_SIZE / 1024 / 1024, trimmedUrl);
                    return null;
                }
                // 不只信 Content-Type：按首字节魔数嗅探真实格式，拒绝 SVG 与非图片内容（防存储型 XSS）
                String format = sniffImageFormat(bytes);
                if (format == null) {
                    log.warn("图片内容嗅探失败（非支持的位图格式或 SVG），已拒绝: {}, Content-Type={}", trimmedUrl, contentType);
                    return null;
                }
                if (contentType != null && contentType.toLowerCase().startsWith("image/")
                    && !isContentTypeConsistent(contentType, format)) {
                    log.warn("图片 Content-Type 与内容嗅探结果不一致，以嗅探结果为准: {}, Content-Type={}, sniffed={}",
                        trimmedUrl, contentType, format);
                }
                return new DownloadedImage(trimmedUrl, bytes, format, primary);
            }
        } catch (Exception e) {
            log.warn("下载图片失败: {}", trimmedUrl, e);
            return null;
        }
    }

    /**
     * 按文件头魔数嗅探真实图片格式，仅支持 PNG/JPEG/GIF/WEBP/BMP 位图格式。
     *
     * <p>SVG 等文本型内容（存储型 XSS 风险）与无法识别的内容一律返回 null，由调用方拒绝。</p>
     *
     * @param bytes 下载内容
     * @return 图片格式扩展名（png/jpg/gif/webp/bmp），无法识别时返回 null
     */
    private String sniffImageFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        // PNG: 89 50 4E 47
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "png";
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // GIF: "GIF8"
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "gif";
        }
        // BMP: "BM"
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return "bmp";
        }
        // WEBP: "RIFF"...."WEBP"
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * 交叉验证 Content-Type 与嗅探格式是否一致（仅用于告警，最终以嗅探结果为准）。
     *
     * @param contentType 响应 Content-Type
     * @param format      嗅探出的格式
     * @return true 表示一致
     */
    private boolean isContentTypeConsistent(String contentType, String format) {
        return contentType.toLowerCase().startsWith(resolveMimeType(format));
    }

    /**
     * 由嗅探格式推导存储用 MIME 类型。
     *
     * @param format 嗅探出的格式
     * @return MIME 类型
     */
    private String resolveMimeType(String format) {
        return switch (format) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
    }

    /**
     * 在独立事务中处理单行导入。
     */
    private String processRowInTransaction(ProductImportRow row, List<DownloadedImage> images, boolean updateIfExists,
                                           Map<String, List<CategoryDict>> dictCache,
                                           int rowIndex, ProductImportResult result) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            String rspuId = processRow(row, images, updateIfExists, dictCache, rowIndex, result);
            transactionManager.commit(status);
            return rspuId;
        } catch (DataIntegrityViolationException e) {
            transactionManager.rollback(status);
            log.warn("导入产品触发数据库唯一约束冲突，rowExternalCode={}", row.getExternalCode(), e);
            throw new BusinessException("产品编码或外部编码已存在，请检查是否重复导入");
        } catch (BusinessException e) {
            transactionManager.rollback(status);
            throw e;
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    /**
     * 处理单行导入：保存/更新 RSPU、关联表、变体、图片。
     */
    private String processRow(ProductImportRow row, List<DownloadedImage> images, boolean updateIfExists,
                              Map<String, List<CategoryDict>> dictCache,
                              int rowIndex, ProductImportResult result) {
        RspuMaster existing = resolveExistingRspu(row.getRspuId(), row.getExternalCode());

        if (existing != null && !updateIfExists) {
            throw new BusinessException("产品已存在，已跳过");
        }

        RspuMaster rspu;
        if (existing != null) {
            rspu = updateRspu(existing, row, dictCache);
            // 更新时重新建立风格和场景
            updateStyles(existing.getRspuId(), row.getPositioningLabel(), dictCache.get("style"));
            updateScenes(existing.getRspuId(), splitCsv(row.getSceneTags()));
        } else {
            rspu = createRspu(row, dictCache);
            saveStyles(rspu.getRspuId(), row.getPositioningLabel(), splitCsv(row.getMaterialTags()), dictCache.get("style"));
            saveScenes(rspu.getRspuId(), splitCsv(row.getSceneTags()));
        }

        if (shouldCreateVariant(row)) {
            createVariantIfNotExists(rspu.getRspuId(), row, dictCache);
        }

        saveImages(rspu.getRspuId(), images, rowIndex, row, result);

        return rspu.getRspuId();
    }

    private RspuMaster resolveExistingRspu(String rspuId, String externalCode) {
        RspuMaster byRspuId = null;
        if (StringUtils.hasText(rspuId)) {
            byRspuId = rspuMapper.selectById(rspuId.trim());
        }
        RspuMaster byExternalCode = null;
        if (StringUtils.hasText(externalCode)) {
            List<RspuMaster> list = rspuMapper.selectList(
                new QueryWrapper<RspuMaster>().eq("external_code", externalCode.trim())
            );
            if (!list.isEmpty()) {
                byExternalCode = list.get(0);
            }
        }
        // 行内 rspuId 与 externalCode 分别指向不同产品时显式报错，避免静默选其一导致误更新
        if (byRspuId != null && byExternalCode != null
            && !byRspuId.getRspuId().equals(byExternalCode.getRspuId())) {
            throw new BusinessException("RSPU ID 与外部编码指向不同产品，请检查输入");
        }
        return byRspuId != null ? byRspuId : byExternalCode;
    }

    private RspuMaster createRspu(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        String rspuId = IdGenerator.rspuId();
        RspuMaster rspu = buildRspuFromRow(new RspuMaster(), row, dictCache);
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setStatus("active");
        rspu.setReviewStatus("待复核");
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.insert(rspu);
        auditLogService.logCreate("rspu_master", rspuId, rspu, SecurityOperatorContext.currentUsername());
        return rspu;
    }

    private RspuMaster updateRspu(RspuMaster rspu, ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        RspuMaster oldSnapshot = snapshot(rspu);
        buildRspuFromRow(rspu, row, dictCache, true);
        if (StringUtils.hasText(row.getExternalCode())) {
            rspu.setExternalCode(row.getExternalCode().trim());
        }
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logUpdate("rspu_master", rspu.getRspuId(), oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
        return rspu;
    }

    private RspuMaster buildRspuFromRow(RspuMaster rspu, ProductImportRow row,
                                       Map<String, List<CategoryDict>> dictCache) {
        return buildRspuFromRow(rspu, row, dictCache, false);
    }

    /**
     * 将导入行字段写入 RSPU 实体。
     *
     * <p>新建模式（isUpdate=false）：无条件赋值，空字段写入默认值/null。
     * 更新模式（isUpdate=true）：仅更新 Excel 中显式有值的字段，空单元格不覆盖已有数据，
     * 与 {@link RskuImportService} 更新模式的「空单元格不覆盖」设计对齐。</p>
     *
     * @param rspu      目标实体（新建时为新建对象，更新时为已有记录）
     * @param row       导入行（已归一化）
     * @param dictCache 字典缓存
     * @param isUpdate  是否为更新模式
     * @return 写入后的实体
     */
    private RspuMaster buildRspuFromRow(RspuMaster rspu, ProductImportRow row,
                                       Map<String, List<CategoryDict>> dictCache, boolean isUpdate) {
        rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
        rspu.setCategoryPath(CategoryPaths.resolve(row.getCategoryCode().trim().toUpperCase()));
        if (!isUpdate || StringUtils.hasText(row.getPositioningLabel())) {
            rspu.setPositioningLabel(StringUtils.hasText(row.getPositioningLabel())
                ? normalizeDictCode(row.getPositioningLabel(), dictCache.get("style"))
                : "待识别");
        }
        if (!isUpdate || StringUtils.hasText(row.getColorPrimaryName())) {
            rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        }
        if (!isUpdate || StringUtils.hasText(row.getMaterialTags())) {
            rspu.setMaterialTags(writeJson(splitCsv(row.getMaterialTags())));
        }
        if (!isUpdate || StringUtils.hasText(row.getSceneTags())) {
            rspu.setSceneTags(writeJson(splitCsv(row.getSceneTags())));
        }
        if (!isUpdate || StringUtils.hasText(row.getSixDimTags())) {
            rspu.setSixDimTags(trim(row.getSixDimTags()));
        }
        if (!isUpdate || StringUtils.hasText(row.getReferencePriceBand())) {
            rspu.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
                ? row.getReferencePriceBand().trim().toLowerCase()
                : null);
        }
        if (!isUpdate || StringUtils.hasText(row.getProductLevel())) {
            rspu.setProductLevel(StringUtils.hasText(row.getProductLevel())
                ? normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"))
                : null);
        }
        if (!isUpdate || row.getWarrantyYears() != null) {
            rspu.setWarrantyYears(row.getWarrantyYears());
        }
        if (!isUpdate || StringUtils.hasText(row.getKeySpecs())) {
            rspu.setKeySpecs(trim(row.getKeySpecs()));
        }
        // 品名：显式提供时更新（空单元格不覆盖已有值）
        if (StringUtils.hasText(row.getVariantDisplayName())) {
            rspu.setProductName(row.getVariantDisplayName().trim());
        }
        return rspu;
    }

    private void saveStyles(String rspuId, String positioningLabel, List<String> materialTags,
                            List<CategoryDict> styles) {
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));

        Set<String> styleCodes = new HashSet<>();
        if (StringUtils.hasText(positioningLabel)) {
            String code = normalizeDictCode(positioningLabel, styles);
            if (code != null) {
                styleCodes.add(code);
                insertStyle(rspuId, code, true);
            }
        }
        // 材质标签不再作为风格处理，避免语义混乱
    }

    private void updateStyles(String rspuId, String positioningLabel, List<CategoryDict> styles) {
        // 更新模式下定位标签留空表示不调整风格关联，跳过 delete+重建，避免清空已有数据
        if (!StringUtils.hasText(positioningLabel)) {
            return;
        }
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        String code = normalizeDictCode(positioningLabel, styles);
        if (code != null) {
            insertStyle(rspuId, code, true);
        }
    }

    private void insertStyle(String rspuId, String styleCode, boolean primary) {
        RspuStyle style = new RspuStyle();
        style.setRspuId(rspuId);
        style.setDictType("style");
        style.setStyleCode(styleCode);
        style.setIsPrimary(primary);
        style.setCreatedAt(LocalDateTime.now());
        rspuStyleMapper.insert(style);
    }

    private void saveScenes(String rspuId, List<String> sceneCodes) {
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        if (sceneCodes == null || sceneCodes.isEmpty()) {
            return;
        }
        for (String code : sceneCodes) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(code.trim());
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
        }
    }

    private void updateScenes(String rspuId, List<String> sceneCodes) {
        // 更新模式下场景标签留空表示不调整场景关联，跳过 delete+重建，避免清空已有数据
        if (sceneCodes == null || sceneCodes.isEmpty()) {
            return;
        }
        saveScenes(rspuId, sceneCodes);
    }

    private boolean shouldCreateVariant(ProductImportRow row) {
        return StringUtils.hasText(row.getVariantDisplayName())
            || StringUtils.hasText(row.getSizeCode())
            || StringUtils.hasText(row.getColorCode())
            || StringUtils.hasText(row.getMaterialCode());
    }

    private void createVariantIfNotExists(String rspuId, ProductImportRow row,
                                          Map<String, List<CategoryDict>> dictCache) {
        // V19"原文为主、码为辅"：三码尽力归一（别名→字典），未识别降级为原文（*_text），不阻断导入
        String sizeCode = resolveCodeOrNull("size", row.getSizeCode(), dictCache.get("size"), row::setSizeText);
        String colorCode = resolveCodeOrNull("color", row.getColorCode(), dictCache.get("color"), row::setColorText);
        String materialCode = resolveCodeOrNull("material", row.getMaterialCode(), dictCache.get("material"), row::setMaterialText);

        // 判重与 uk_variant_attrs 唯一索引语义一致：比较 COALESCE(code, text) 有效值
        String effSize = effectiveOf(sizeCode, row.getSizeText());
        String effColor = effectiveOf(colorCode, row.getColorText());
        String effMaterial = effectiveOf(materialCode, row.getMaterialText());
        List<RspuVariant> existing = rspuVariantMapper.selectList(new QueryWrapper<RspuVariant>()
            .eq("rspu_id", rspuId));
        boolean duplicate = existing.stream().anyMatch(v ->
            effSize.equals(effectiveOf(v.getSizeCode(), v.getSizeText()))
                && effColor.equals(effectiveOf(v.getColorCode(), v.getColorText()))
                && effMaterial.equals(effectiveOf(v.getMaterialCode(), v.getMaterialText())));
        if (duplicate) {
            return;
        }

        Long nextSeq = variantCodeMapper.allocateSequence(rspuId);
        if (nextSeq == null) {
            throw new BusinessException("无法生成变体编码流水号: " + rspuId);
        }
        String variantId = String.format("%s-V%03d", rspuId, nextSeq);

        RspuVariant variant = new RspuVariant();
        variant.setVariantId(variantId);
        variant.setRspuId(rspuId);
        variant.setDisplayName(StringUtils.hasText(row.getVariantDisplayName())
            ? row.getVariantDisplayName().trim()
            : "默认变体");
        variant.setSizeCode(sizeCode);
        variant.setSizeText(row.getSizeText());
        variant.setColorCode(colorCode);
        variant.setColorText(row.getColorText());
        variant.setMaterialCode(materialCode);
        variant.setMaterialText(row.getMaterialText());
        variant.setReferencePriceBand(StringUtils.hasText(row.getVariantReferencePriceBand())
            ? row.getVariantReferencePriceBand().trim().toLowerCase()
            : null);
        variant.setProductLevel(StringUtils.hasText(row.getVariantProductLevel())
            ? normalizeDictCode(row.getVariantProductLevel(), dictCache.get("factory_level"))
            : null);
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        try {
            rspuVariantMapper.insert(variant);
        } catch (DataIntegrityViolationException e) {
            // 命中 uk_variant_attrs 部分唯一索引（并发导入同维度变体），给出准确的冲突文案
            log.warn("导入变体触发维度唯一约束冲突，rspuId={}", rspuId, e);
            throw new BusinessException("相同尺寸/颜色/材质的变体已存在，请检查是否重复导入");
        }
        auditLogService.logCreate("rspu_variant", variantId, variant, SecurityOperatorContext.currentUsername());
    }

    /**
     * 尽力将输入归一为字典码（别名 → 字典码 → 字典名）；未命中时原文写入 textSetter
     * 并采集到 dict_unresolved_value 待治理，返回 null。
     */
    private String resolveCodeOrNull(String dictType, String raw, List<CategoryDict> dicts,
                                     java.util.function.Consumer<String> textSetter) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        String byAlias = dictAliasService.resolveAlias(dictType, trimmed);
        if (StringUtils.hasText(byAlias)) {
            return byAlias;
        }
        String normalized = normalizeDictCode(trimmed, dicts);
        if (isValidDictCode(normalized, dicts)) {
            return normalized;
        }
        textSetter.accept(trimmed);
        dictUnresolvedService.record(dictType, trimmed, null, SecurityOperatorContext.currentUsername());
        return null;
    }

    /** 有效判重值：码优先，无码取原文，均无则为空串（与唯一索引 COALESCE 语义一致）。 */
    private String effectiveOf(String code, String text) {
        if (StringUtils.hasText(code)) {
            return code.trim();
        }
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private void saveImages(String rspuId, List<DownloadedImage> images, int rowIndex,
                            ProductImportRow row, ProductImportResult result) {
        if (images == null || images.isEmpty()) {
            return;
        }
        // 更新模式追加图片时，若该 RSPU 已有主图，新图一律不作为主图，避免同一 RSPU 出现多条主图
        boolean hasExistingPrimary = imageAssetsMapper.selectCount(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).eq("is_primary", true)) > 0;
        boolean hasPrimary = images.stream().anyMatch(DownloadedImage::primary);
        for (int i = 0; i < images.size(); i++) {
            DownloadedImage downloaded = images.get(i);
            String imageId = IdGenerator.imageId();
            String extension = downloaded.format;
            String objectKey = "images/" + imageId + "." + extension;

            try {
                storageService.store(
                    new ByteArrayInputStream(downloaded.bytes),
                    objectKey,
                    downloaded.bytes.length,
                    resolveMimeType(downloaded.format)
                );
            } catch (IOException e) {
                log.error("存储图片失败，rspuId={}, imageId={}", rspuId, imageId, e);
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "图片存储失败: " + downloaded.url));
                continue;
            }

            boolean isPrimary = !hasExistingPrimary && (downloaded.primary || (!hasPrimary && i == 0));
            ImageAssets imageAsset = new ImageAssets();
            imageAsset.setImageId(imageId);
            imageAsset.setRspuId(rspuId);
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
        }
    }

    private String validateRow(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        if (!StringUtils.hasText(row.getCategoryCode())) {
            return "品类码 不能为空";
        }
        String categoryCode = row.getCategoryCode().trim().toUpperCase();
        if (categoryCode.length() > 16) {
            return "品类码 长度不能超过 16";
        }
        if (!isValidDictCode(categoryCode, dictCache.get("category"))) {
            return "品类码不存在: " + categoryCode;
        }

        if (StringUtils.hasText(row.getRspuId()) && row.getRspuId().trim().length() > 64) {
            return "RSPU ID 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getExternalCode()) && row.getExternalCode().trim().length() > 64) {
            return "外部编码 长度不能超过 64";
        }

        // 关键字段长度校验（与 DB 列宽一致），避免超长触发数据库约束异常被误报
        if (StringUtils.hasText(row.getPositioningLabel()) && row.getPositioningLabel().trim().length() > 64) {
            return "定位标签 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getColorPrimaryName()) && row.getColorPrimaryName().trim().length() > 64) {
            return "主色 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getProductLevel()) && row.getProductLevel().trim().length() > 16) {
            return "产品等级 长度不能超过 16";
        }
        if (StringUtils.hasText(row.getVariantDisplayName()) && row.getVariantDisplayName().trim().length() > 128) {
            return "变体显示名称 长度不能超过 128";
        }
        // 变体字段长度校验（原文列宽：size/color 64、material 128；码或原文均走此上限）
        if (StringUtils.hasText(row.getSizeCode()) && row.getSizeCode().trim().length() > 64) {
            return "尺寸码 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getColorCode()) && row.getColorCode().trim().length() > 64) {
            return "颜色码 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getMaterialCode()) && row.getMaterialCode().trim().length() > 128) {
            return "材质码 长度不能超过 128";
        }
        if (StringUtils.hasText(row.getVariantProductLevel()) && row.getVariantProductLevel().trim().length() > 8) {
            return "变体产品等级 长度不能超过 8";
        }

        if (StringUtils.hasText(row.getPositioningLabel())
            && !isValidDictCode(row.getPositioningLabel().trim(), dictCache.get("style"))) {
            return "定位标签不存在: " + row.getPositioningLabel();
        }

        if (StringUtils.hasText(row.getProductLevel())
            && !isValidDictCode(row.getProductLevel().trim(), dictCache.get("factory_level"))) {
            return "产品等级不存在: " + row.getProductLevel();
        }

        if (StringUtils.hasText(row.getReferencePriceBand())) {
            String band = row.getReferencePriceBand().trim().toLowerCase();
            if (!List.of("low", "mid", "high").contains(band)) {
                return "参考价格带必须是 low/mid/high 之一";
            }
        }

        if (row.getWarrantyYears() != null && (row.getWarrantyYears() < 0 || row.getWarrantyYears() > 100)) {
            return "保修年限 必须在 0~100 之间";
        }

        // 变体三码不再强制字典校验（V19：原文为主、码为辅）——
        // 由 createVariantIfNotExists 做"别名→字典"尽力解析，未识别降级为原文保留并采集待治理
        if (StringUtils.hasText(row.getVariantReferencePriceBand())) {
            String band = row.getVariantReferencePriceBand().trim().toLowerCase();
            if (!List.of("low", "mid", "high").contains(band)) {
                return "变体参考价格带必须是 low/mid/high 之一";
            }
        }
        if (StringUtils.hasText(row.getVariantProductLevel())
            && !isValidDictCode(row.getVariantProductLevel().trim(), dictCache.get("factory_level"))) {
            return "变体产品等级不存在: " + row.getVariantProductLevel();
        }

        // JSON 格式校验
        String sixDimError = validateJson(row.getSixDimTags(), "六维标签");
        if (sixDimError != null) {
            return sixDimError;
        }
        String keySpecsError = validateJson(row.getKeySpecs(), "关键规格");
        if (keySpecsError != null) {
            return keySpecsError;
        }

        return null;
    }

    private String validateJson(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            objectMapper.readTree(json.trim());
            return null;
        } catch (Exception e) {
            return fieldName + " 不是有效的 JSON 字符串";
        }
    }

    private void normalizeRow(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        row.setRspuId(trim(row.getRspuId()));
        row.setExternalCode(trim(row.getExternalCode()));
        row.setCategoryCode(trim(row.getCategoryCode()));
        row.setPositioningLabel(trim(row.getPositioningLabel()));
        row.setColorPrimaryName(trim(row.getColorPrimaryName()));
        row.setMaterialTags(trim(row.getMaterialTags()));
        row.setSceneTags(trim(row.getSceneTags()));
        row.setProductLevel(trim(row.getProductLevel()));
        row.setReferencePriceBand(trim(row.getReferencePriceBand()));
        row.setSixDimTags(trim(row.getSixDimTags()));
        row.setKeySpecs(trim(row.getKeySpecs()));
        row.setPrimaryImageUrl(trim(row.getPrimaryImageUrl()));
        row.setDetailImageUrls(trim(row.getDetailImageUrls()));
        row.setVariantDisplayName(trim(row.getVariantDisplayName()));
        row.setSizeCode(trim(row.getSizeCode()));
        row.setColorCode(trim(row.getColorCode()));
        row.setMaterialCode(trim(row.getMaterialCode()));
        row.setVariantReferencePriceBand(trim(row.getVariantReferencePriceBand()));
        row.setVariantProductLevel(trim(row.getVariantProductLevel()));

        // 字典字段先归一（支持中文名→字典码）再进入校验，避免中文名被误判为非法字典码
        if (StringUtils.hasText(row.getPositioningLabel())) {
            row.setPositioningLabel(normalizeDictCode(row.getPositioningLabel(), dictCache.get("style")));
        }
        if (StringUtils.hasText(row.getProductLevel())) {
            row.setProductLevel(normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level")));
        }
        if (StringUtils.hasText(row.getSizeCode())) {
            row.setSizeCode(normalizeDictCode(row.getSizeCode(), dictCache.get("size")));
        }
        if (StringUtils.hasText(row.getColorCode())) {
            row.setColorCode(normalizeDictCode(row.getColorCode(), dictCache.get("color")));
        }
        if (StringUtils.hasText(row.getMaterialCode())) {
            row.setMaterialCode(normalizeDictCode(row.getMaterialCode(), dictCache.get("material")));
        }
        if (StringUtils.hasText(row.getVariantProductLevel())) {
            row.setVariantProductLevel(normalizeDictCode(row.getVariantProductLevel(), dictCache.get("factory_level")));
        }
    }

    private boolean isValidDictCode(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equals(d.getDictCode()));
    }

    private String normalizeDictCode(String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        // 先按 dictCode 精确匹配（不区分大小写）
        for (CategoryDict d : dicts) {
            if (trimmed.equalsIgnoreCase(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        // 再按 dictName 精确匹配
        for (CategoryDict d : dicts) {
            if (StringUtils.hasText(d.getDictName()) && trimmed.equals(d.getDictName())) {
                return d.getDictCode();
            }
        }
        // 未匹配时保留原始输入，由上层校验决定是否报错
        return trimmed;
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        for (String part : value.split("[,，]")) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String writeJson(Object value) {
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

    private RspuMaster snapshot(RspuMaster source) {
        RspuMaster copy = new RspuMaster();
        copy.setRspuId(source.getRspuId());
        copy.setExternalCode(source.getExternalCode());
        copy.setCategoryCode(source.getCategoryCode());
        copy.setCategoryPath(source.getCategoryPath());
        copy.setPositioningLabel(source.getPositioningLabel());
        copy.setSixDimTags(source.getSixDimTags());
        copy.setStyleVector(source.getStyleVector());
        copy.setColorPrimaryName(source.getColorPrimaryName());
        copy.setColorPrimaryHsv(source.getColorPrimaryHsv());
        copy.setColorSecondary(source.getColorSecondary());
        copy.setMaterialTags(source.getMaterialTags());
        copy.setSceneTags(source.getSceneTags());
        copy.setReferencePriceBand(source.getReferencePriceBand());
        copy.setProductLevel(source.getProductLevel());
        copy.setBudgetRange(source.getBudgetRange());
        copy.setWarrantyYears(source.getWarrantyYears());
        copy.setKeySpecs(source.getKeySpecs());
        copy.setStatus(source.getStatus());
        copy.setReviewStatus(source.getReviewStatus());
        copy.setReviewComment(source.getReviewComment());
        copy.setAestheticsConfidence(source.getAestheticsConfidence());
        copy.setSourceAgentVersion(source.getSourceAgentVersion());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private record DownloadedImage(String url, byte[] bytes, String format, boolean primary) {
    }
}
