package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.config.properties.DataRetentionProperties;
import com.rsdp.entity.DocumentImportBatch;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ImageAssets;
import com.rsdp.mapper.DocumentImportBatchMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ExcelImportRowMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 数据保留清理任务（阶段 3.3：批次/文件生命周期管理）。
 *
 * <p>策略口径（用户拍板，严格执行）：</p>
 * <ul>
 *   <li><b>pending 批次（预览后放弃）</b>：超 {@code pendingBatchMaxAgeHours}（默认 24h）
 *   整批删除——批次行 + excel_import_row 行记录 + 原始文件 + preview-images 临时图 +
 *   tmpdir 行图缓存；</li>
 *   <li><b>done/failed 批次</b>：批次行与导入结果（行记录/计数/failures）永久保留，
 *   超 {@code completedBatchFileRetentionDays}（默认 7 天）清理 preview_rows 字段与原始文件
 *   （storage_path 置空防重复处理，file_name 保留可查）；</li>
 *   <li><b>importing/processing 中的批次绝不动</b>：查询按状态过滤 + 删除/清字段均带状态前置
 *   条件（防与并发导入竞态）；</li>
 *   <li><b>软删图片孤儿文件</b>：默认关闭，打开后仅物理删除软删超
 *   {@code orphanImageRetentionDays}（默认 30 天）的 image_assets 对应存储文件，行保留。</li>
 * </ul>
 *
 * <p>覆盖 excel_import_batch 与 document_import_batch 两类批次（同一策略）。
 * 单条清理失败只记日志不影响其他；调度线程自身异常不抛出，下个周期重试。
 * 删除/清字段操作记审计（操作人 system）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetentionCleanupService {

    /** 数据清洗页上传临时图存储前缀（与 ExcelAiImportService 一致） */
    private static final String PREVIEW_UPLOAD_IMAGE_PREFIX = "preview-images";
    /** 预览行图 tmpdir 缓存根目录（与 ExcelAiImportService 一致） */
    private static final String PREVIEW_IMAGE_CACHE_DIR = "rsdp-preview-images";
    /** 清理操作审计操作人 */
    private static final String OPERATOR = "system";

    private final DataRetentionProperties properties;
    private final ExcelImportBatchMapper excelImportBatchMapper;
    private final ExcelImportRowMapper excelImportRowMapper;
    private final DocumentImportBatchMapper documentImportBatchMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    /**
     * 定时清理过期导入数据（默认每 1 小时执行一次，启动 2 分钟后首次执行）。
     */
    @Scheduled(fixedDelayString = "${rsdp.data-retention.cleanup-interval-ms:3600000}",
        initialDelayString = "${rsdp.data-retention.cleanup-initial-delay-ms:120000}")
    public void cleanupExpiredImportData() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            cleanPendingExcelBatches(now);
            cleanCompletedExcelBatchFiles(now);
            cleanPendingDocumentBatches(now);
            cleanCompletedDocumentBatchFiles(now);
        } catch (Exception e) {
            // 清理任务自身失败（如 DB 短暂故障）不能影响调度线程，下个周期重试
            log.error("数据保留清理执行失败: {}", e.getMessage());
        }
        try {
            cleanOrphanImageFiles();
        } catch (Exception e) {
            log.error("孤儿图片文件清理执行失败: {}", e.getMessage());
        }
    }

    /**
     * 清理超时 pending 的 Excel 导入批次：整批删除（批次行 + 行记录 + 原始文件 + 预览临时图 + 行图缓存）。
     *
     * @param now 当前时间
     */
    private void cleanPendingExcelBatches(LocalDateTime now) {
        LocalDateTime threshold = now.minusHours(properties.getPendingBatchMaxAgeHours());
        List<ExcelImportBatch> batches = excelImportBatchMapper.selectList(new QueryWrapper<ExcelImportBatch>()
            .eq("status", "pending")
            .apply("COALESCE(updated_at, created_at) < {0}", threshold)
            .last("LIMIT " + properties.getBatchSize()));
        if (batches == null || batches.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (ExcelImportBatch batch : batches) {
            try {
                if (cleanPendingExcelBatch(batch, now)) {
                    cleaned++;
                }
            } catch (Exception e) {
                log.error("清理 pending Excel 导入批次失败，batchId={}: {}", batch.getBatchId(), e.getMessage());
            }
        }
        log.info("清理超时 pending Excel 导入批次 {} 个（候选 {} 个，阈值 {}h）",
            cleaned, batches.size(), properties.getPendingBatchMaxAgeHours());
    }

    /**
     * 单个 pending Excel 批次整批删除；批次行用状态前置条件删除防并发竞态，未命中说明
     * 批次已被并发导入抢占/状态变化，放弃本次清理。
     *
     * @param batch 候选批次
     * @param now   当前时间
     * @return true 表示完成整批删除
     */
    private boolean cleanPendingExcelBatch(ExcelImportBatch batch, LocalDateTime now) {
        int deleted = excelImportBatchMapper.delete(new QueryWrapper<ExcelImportBatch>()
            .eq("batch_id", batch.getBatchId())
            .eq("status", "pending"));
        if (deleted == 0) {
            log.info("pending Excel 批次清理跳过（状态已变化），batchId={}", batch.getBatchId());
            return false;
        }
        excelImportRowMapper.deleteByBatchId(batch.getBatchId());
        deleteStorageFileQuietly(batch.getStoragePath(), batch.getBatchId());
        deletePreviewUploadsQuietly(batch.getBatchId());
        deletePreviewCacheQuietly(batch.getBatchId());
        auditLogService.logDelete("excel_import_batch", batch.getBatchId(), batch, OPERATOR);
        return true;
    }

    /**
     * 清理超期 done/failed Excel 批次的原始文件与 preview_rows 字段（批次行与导入结果保留）。
     *
     * @param now 当前时间
     */
    private void cleanCompletedExcelBatchFiles(LocalDateTime now) {
        LocalDateTime threshold = now.minusDays(properties.getCompletedBatchFileRetentionDays());
        List<ExcelImportBatch> batches = excelImportBatchMapper.selectList(new QueryWrapper<ExcelImportBatch>()
            .in("status", "done", "failed")
            .apply("COALESCE(processed_at, updated_at, created_at) < {0}", threshold)
            .and(w -> w.isNotNull("preview_rows").or().isNotNull("storage_path"))
            .last("LIMIT " + properties.getBatchSize()));
        if (batches == null || batches.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (ExcelImportBatch batch : batches) {
            try {
                if (cleanCompletedExcelBatchFile(batch, now)) {
                    cleaned++;
                }
            } catch (Exception e) {
                log.error("清理 Excel 导入批次原始文件失败，batchId={}: {}", batch.getBatchId(), e.getMessage());
            }
        }
        log.info("清理超期 done/failed Excel 导入批次原始文件/preview_rows {} 个（候选 {} 个，阈值 {}d）",
            cleaned, batches.size(), properties.getCompletedBatchFileRetentionDays());
    }

    /**
     * 单个 done/failed Excel 批次清 preview_rows + storage_path（条件更新带状态守卫），
     * 更新命中后删除原始存储文件。
     *
     * @param batch 候选批次
     * @param now   当前时间
     * @return true 表示完成清理
     */
    private boolean cleanCompletedExcelBatchFile(ExcelImportBatch batch, LocalDateTime now) {
        int updated = excelImportBatchMapper.update(null, new UpdateWrapper<ExcelImportBatch>()
            .eq("batch_id", batch.getBatchId())
            .in("status", "done", "failed")
            .and(w -> w.isNotNull("preview_rows").or().isNotNull("storage_path"))
            .set("preview_rows", null)
            .set("storage_path", null)
            .set("updated_at", now));
        if (updated == 0) {
            return false;
        }
        deleteStorageFileQuietly(batch.getStoragePath(), batch.getBatchId());
        ExcelImportBatch newSnapshot = new ExcelImportBatch();
        BeanUtils.copyProperties(batch, newSnapshot);
        newSnapshot.setPreviewRows(null);
        newSnapshot.setStoragePath(null);
        newSnapshot.setUpdatedAt(now);
        auditLogService.logUpdate("excel_import_batch", batch.getBatchId(), batch, newSnapshot, OPERATOR);
        return true;
    }

    /**
     * 清理超时 pending 的文档导入批次：整批删除（批次行 + 原始 PDF 文件）。
     *
     * @param now 当前时间
     */
    private void cleanPendingDocumentBatches(LocalDateTime now) {
        LocalDateTime threshold = now.minusHours(properties.getPendingBatchMaxAgeHours());
        List<DocumentImportBatch> batches = documentImportBatchMapper.selectList(new QueryWrapper<DocumentImportBatch>()
            .eq("status", "pending")
            .apply("COALESCE(updated_at, created_at) < {0}", threshold)
            .last("LIMIT " + properties.getBatchSize()));
        if (batches == null || batches.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (DocumentImportBatch batch : batches) {
            try {
                int deleted = documentImportBatchMapper.delete(new QueryWrapper<DocumentImportBatch>()
                    .eq("batch_id", batch.getBatchId())
                    .eq("status", "pending"));
                if (deleted == 0) {
                    log.info("pending 文档导入批次清理跳过（状态已变化），batchId={}", batch.getBatchId());
                    continue;
                }
                deleteStorageFileQuietly(batch.getStoragePath(), batch.getBatchId());
                auditLogService.logDelete("document_import_batch", batch.getBatchId(), batch, OPERATOR);
                cleaned++;
            } catch (Exception e) {
                log.error("清理 pending 文档导入批次失败，batchId={}: {}", batch.getBatchId(), e.getMessage());
            }
        }
        log.info("清理超时 pending 文档导入批次 {} 个（候选 {} 个，阈值 {}h）",
            cleaned, batches.size(), properties.getPendingBatchMaxAgeHours());
    }

    /**
     * 清理超期终态（done/partial_success/failed）文档导入批次的原始 PDF 文件
     * （批次行与 pageResults 等结果永久保留）。
     *
     * @param now 当前时间
     */
    private void cleanCompletedDocumentBatchFiles(LocalDateTime now) {
        LocalDateTime threshold = now.minusDays(properties.getCompletedBatchFileRetentionDays());
        List<DocumentImportBatch> batches = documentImportBatchMapper.selectList(new QueryWrapper<DocumentImportBatch>()
            .in("status", "done", "partial_success", "failed")
            .apply("COALESCE(completed_at, updated_at, created_at) < {0}", threshold)
            .isNotNull("storage_path")
            .last("LIMIT " + properties.getBatchSize()));
        if (batches == null || batches.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (DocumentImportBatch batch : batches) {
            try {
                int updated = documentImportBatchMapper.update(null, new UpdateWrapper<DocumentImportBatch>()
                    .eq("batch_id", batch.getBatchId())
                    .in("status", "done", "partial_success", "failed")
                    .isNotNull("storage_path")
                    .set("storage_path", null)
                    .set("updated_at", now));
                if (updated == 0) {
                    continue;
                }
                deleteStorageFileQuietly(batch.getStoragePath(), batch.getBatchId());
                DocumentImportBatch newSnapshot = new DocumentImportBatch();
                BeanUtils.copyProperties(batch, newSnapshot);
                newSnapshot.setStoragePath(null);
                newSnapshot.setUpdatedAt(now);
                auditLogService.logUpdate("document_import_batch", batch.getBatchId(), batch, newSnapshot, OPERATOR);
                cleaned++;
            } catch (Exception e) {
                log.error("清理文档导入批次原始文件失败，batchId={}: {}", batch.getBatchId(), e.getMessage());
            }
        }
        log.info("清理超期终态文档导入批次原始文件 {} 个（候选 {} 个，阈值 {}d）",
            cleaned, batches.size(), properties.getCompletedBatchFileRetentionDays());
    }

    /**
     * 清理软删超期 image_assets 对应的孤儿存储文件（默认关闭；只删存储文件，行保留不删）。
     */
    private void cleanOrphanImageFiles() {
        if (!properties.isOrphanImageCleanupEnabled()) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(properties.getOrphanImageRetentionDays());
        List<ImageAssets> assets = imageAssetsMapper.selectSoftDeletedBefore(
            threshold, properties.getOrphanImageBatchSize());
        if (assets == null || assets.isEmpty()) {
            return;
        }
        int deletedCount = 0;
        int failedCount = 0;
        for (ImageAssets asset : assets) {
            if (!StringUtils.hasText(asset.getStoragePath())) {
                continue;
            }
            try {
                storageService.delete(asset.getStoragePath());
                deletedCount++;
                log.info("清理软删图片孤儿文件，imageId={}，storagePath={}", asset.getImageId(), asset.getStoragePath());
            } catch (Exception e) {
                failedCount++;
                log.warn("清理软删图片孤儿文件失败，imageId={}，storagePath={}: {}",
                    asset.getImageId(), asset.getStoragePath(), e.getMessage());
            }
        }
        log.info("软删图片孤儿文件清理完成：删除 {} 个，失败 {} 个（阈值 {}d，行保留不删）",
            deletedCount, failedCount, properties.getOrphanImageRetentionDays());
    }

    /**
     * 删除原始上传文件（失败只记日志，不阻断批次清理主流程）。
     *
     * @param storagePath 存储对象键
     * @param batchId     批次 ID（日志用）
     */
    private void deleteStorageFileQuietly(String storagePath, String batchId) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }
        try {
            storageService.delete(storagePath);
        } catch (Exception e) {
            log.warn("删除批次原始文件失败，batchId={}，storagePath={}: {}", batchId, storagePath, e.getMessage());
        }
    }

    /**
     * 删除批次 preview-images/{batchId}/ 前缀下的全部预览上传临时图（失败只记日志）。
     *
     * @param batchId 批次 ID
     */
    private void deletePreviewUploadsQuietly(String batchId) {
        try {
            int deleted = storageService.deleteByPrefix(
                PREVIEW_UPLOAD_IMAGE_PREFIX + "/" + sanitizeFileName(batchId) + "/");
            if (deleted > 0) {
                log.info("清理批次预览上传临时图 {} 个，batchId={}", deleted, batchId);
            }
        } catch (Exception e) {
            log.warn("清理批次预览上传临时图失败，batchId={}: {}", batchId, e.getMessage());
        }
    }

    /**
     * 删除批次 tmpdir 行图缓存目录（失败只记日志；写法对齐 ExcelAiImportService 启动清理）。
     *
     * @param batchId 批次 ID
     */
    private void deletePreviewCacheQuietly(String batchId) {
        try {
            Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), PREVIEW_IMAGE_CACHE_DIR,
                sanitizeFileName(batchId));
            if (!Files.exists(cacheDir)) {
                return;
            }
            try (var stream = Files.walk(cacheDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        log.warn("删除预览行图缓存文件失败: {}", path, e);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("清理批次预览行图缓存失败，batchId={}: {}", batchId, e.getMessage());
        }
    }

    /**
     * 文件名净化（与 ExcelAiImportService.sanitizeFileName 同口径，保证缓存/前缀目录名一致）。
     *
     * @param name 原始名称
     * @return 净化后的名称
     */
    private String sanitizeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9,\\-_]", "_");
    }
}
