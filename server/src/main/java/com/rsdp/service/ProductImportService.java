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
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        Set<String> processedExternalCodes = new HashSet<>();
        Set<String> processedRspuIds = new HashSet<>();

        int rowIndex = 1; // Excel 行号，第 1 行为表头，数据从第 2 行开始
        for (ProductImportRow row : rows) {
            rowIndex++;
            normalizeRow(row);
            String error = validateRow(row, dictCache);
            if (error != null) {
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(), error));
                continue;
            }

            // Excel 内重复检测
            String duplicateKey = buildDuplicateKey(row);
            if (duplicateKey != null && !processedExternalCodes.add(duplicateKey)) {
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
            throw new BusinessException("读取 Excel 文件失败");
        } catch (Exception e) {
            log.error("解析 Excel 文件失败，请检查文件格式是否与模板一致", e);
            throw new BusinessException("解析 Excel 文件失败，请检查文件格式是否与模板一致");
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

    private String buildDuplicateKey(ProductImportRow row) {
        if (StringUtils.hasText(row.getRspuId())) {
            return row.getRspuId().trim();
        }
        if (StringUtils.hasText(row.getExternalCode())) {
            return "EXTERNAL:" + row.getExternalCode().trim();
        }
        return null;
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
                return new DownloadedImage(trimmedUrl, bytes, contentType, primary);
            }
        } catch (Exception e) {
            log.warn("下载图片失败: {}", trimmedUrl, e);
            return null;
        }
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
        if (StringUtils.hasText(rspuId)) {
            RspuMaster rspu = rspuMapper.selectById(rspuId.trim());
            if (rspu != null && rspu.getDeletedAt() == null) {
                return rspu;
            }
        }
        if (StringUtils.hasText(externalCode)) {
            List<RspuMaster> list = rspuMapper.selectList(
                new QueryWrapper<RspuMaster>().eq("external_code", externalCode.trim())
            );
            for (RspuMaster rspu : list) {
                if (rspu.getDeletedAt() == null) {
                    return rspu;
                }
            }
        }
        return null;
    }

    private RspuMaster createRspu(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        String rspuId = "RSPU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
        buildRspuFromRow(rspu, row, dictCache);
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
        rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
        rspu.setCategoryPath(resolveCategoryPath(row.getCategoryCode().trim().toUpperCase()));
        rspu.setPositioningLabel(StringUtils.hasText(row.getPositioningLabel())
            ? normalizeDictCode(row.getPositioningLabel(), dictCache.get("style"))
            : "待识别");
        rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        rspu.setMaterialTags(writeJson(splitCsv(row.getMaterialTags())));
        rspu.setSceneTags(writeJson(splitCsv(row.getSceneTags())));
        rspu.setSixDimTags(trim(row.getSixDimTags()));
        rspu.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
            ? row.getReferencePriceBand().trim().toLowerCase()
            : null);
        rspu.setProductLevel(StringUtils.hasText(row.getProductLevel())
            ? normalizeDictCode(row.getProductLevel(), dictCache.get("factory_level"))
            : null);
        rspu.setWarrantyYears(row.getWarrantyYears());
        rspu.setKeySpecs(trim(row.getKeySpecs()));
        return rspu;
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
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        if (StringUtils.hasText(positioningLabel)) {
            String code = normalizeDictCode(positioningLabel, styles);
            if (code != null) {
                insertStyle(rspuId, code, true);
            }
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
        // 根据尺寸+颜色+材质组合判断是否已存在
        List<RspuVariant> existing = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>()
                .eq("rspu_id", rspuId)
                .eq("size_code", trim(row.getSizeCode()))
                .eq("color_code", trim(row.getColorCode()))
                .eq("material_code", trim(row.getMaterialCode()))
        );
        if (existing != null && !existing.isEmpty()) {
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
        variant.setSizeCode(StringUtils.hasText(row.getSizeCode())
            ? normalizeDictCode(row.getSizeCode(), dictCache.get("size"))
            : null);
        variant.setColorCode(StringUtils.hasText(row.getColorCode())
            ? normalizeDictCode(row.getColorCode(), dictCache.get("color"))
            : null);
        variant.setMaterialCode(StringUtils.hasText(row.getMaterialCode())
            ? normalizeDictCode(row.getMaterialCode(), dictCache.get("material"))
            : null);
        variant.setReferencePriceBand(StringUtils.hasText(row.getVariantReferencePriceBand())
            ? row.getVariantReferencePriceBand().trim().toLowerCase()
            : null);
        variant.setProductLevel(StringUtils.hasText(row.getVariantProductLevel())
            ? normalizeDictCode(row.getVariantProductLevel(), dictCache.get("factory_level"))
            : null);
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        rspuVariantMapper.insert(variant);
        auditLogService.logCreate("rspu_variant", variantId, variant, SecurityOperatorContext.currentUsername());
    }

    private void saveImages(String rspuId, List<DownloadedImage> images, int rowIndex,
                            ProductImportRow row, ProductImportResult result) {
        if (images == null || images.isEmpty()) {
            return;
        }
        boolean hasPrimary = images.stream().anyMatch(DownloadedImage::primary);
        for (int i = 0; i < images.size(); i++) {
            DownloadedImage downloaded = images.get(i);
            String imageId = "IMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String extension = resolveExtension(downloaded.contentType);
            String objectKey = "images/" + imageId + "." + extension;

            try {
                storageService.store(
                    new ByteArrayInputStream(downloaded.bytes),
                    objectKey,
                    downloaded.bytes.length,
                    downloaded.contentType
                );
            } catch (IOException e) {
                log.error("存储图片失败，rspuId={}, imageId={}", rspuId, imageId, e);
                result.getFailures().add(new ProductImportFailure(rowIndex, row.getExternalCode(), row.getRspuId(),
                    "图片存储失败: " + downloaded.url));
                continue;
            }

            boolean isPrimary = downloaded.primary || (!hasPrimary && i == 0);
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

    private String resolveExtension(String contentType) {
        if (contentType == null) {
            return "jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/svg+xml" -> "svg";
            default -> "jpg";
        };
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

        // 变体字段校验
        if (StringUtils.hasText(row.getSizeCode())
            && !isValidDictCode(row.getSizeCode().trim(), dictCache.get("size"))) {
            return "尺寸码不存在: " + row.getSizeCode();
        }
        if (StringUtils.hasText(row.getColorCode())
            && !isValidDictCode(row.getColorCode().trim(), dictCache.get("color"))) {
            return "颜色码不存在: " + row.getColorCode();
        }
        if (StringUtils.hasText(row.getMaterialCode())
            && !isValidDictCode(row.getMaterialCode().trim(), dictCache.get("material"))) {
            return "材质码不存在: " + row.getMaterialCode();
        }
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

    private void normalizeRow(ProductImportRow row) {
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

    private record DownloadedImage(String url, byte[] bytes, String contentType, boolean primary) {
    }
}
