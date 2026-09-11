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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DataRetentionCleanupService} 单元测试（阶段 3.3 批次/文件生命周期清理）。
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionCleanupServiceTest {

    @Mock
    private ExcelImportBatchMapper excelImportBatchMapper;

    @Mock
    private ExcelImportRowMapper excelImportRowMapper;

    @Mock
    private DocumentImportBatchMapper documentImportBatchMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    private DataRetentionProperties properties;

    private DataRetentionCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        properties = new DataRetentionProperties();
        cleanupService = new DataRetentionCleanupService(properties, excelImportBatchMapper,
            excelImportRowMapper, documentImportBatchMapper, imageAssetsMapper, storageService,
            auditLogService);
        // 默认无候选批次；个别测试覆盖
        lenient().when(excelImportBatchMapper.selectList(any())).thenReturn(List.of());
        lenient().when(documentImportBatchMapper.selectList(any())).thenReturn(List.of());
    }

    private ExcelImportBatch excelBatch(String batchId, String status) {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId(batchId);
        batch.setFileName("catalog.xlsx");
        batch.setStoragePath("excel-imports/" + batchId + ".xlsx");
        batch.setStatus(status);
        batch.setPreviewRows("[{\"a\":1}]");
        return batch;
    }

    @Test
    void cleanup_shouldFullyDeleteStalePendingExcelBatch() throws IOException {
        // pending 超 24h：整批删除（批次行 + 行记录 + 原始文件 + 预览临时图 + tmpdir 行图缓存）
        ExcelImportBatch batch = excelBatch("BATCH-P1", "pending");
        when(excelImportBatchMapper.selectList(any()))
            .thenReturn(List.of(batch))   // pending 候选
            .thenReturn(List.of());       // done/failed 候选为空
        when(excelImportBatchMapper.delete(any(QueryWrapper.class))).thenReturn(1);

        // 预置 tmpdir 行图缓存目录，断言被清理
        Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "rsdp-preview-images", "BATCH-P1");
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve("0,1,2,0.jpeg");
        Files.write(cacheFile, new byte[]{1, 2, 3});

        try {
            cleanupService.cleanupExpiredImportData();

            // 批次行删除带 pending 状态守卫（importing 绝不动）
            ArgumentCaptor<QueryWrapper<ExcelImportBatch>> deleteCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(excelImportBatchMapper).delete(deleteCaptor.capture());
            assertThat(deleteCaptor.getValue().getSqlSegment()).contains("batch_id").contains("status");
            assertThat(deleteCaptor.getValue().getParamNameValuePairs()).containsValue("BATCH-P1").containsValue("pending");

            verify(excelImportRowMapper).deleteByBatchId("BATCH-P1");
            verify(storageService).delete("excel-imports/BATCH-P1.xlsx");
            verify(storageService).deleteByPrefix("preview-images/BATCH-P1/");
            assertThat(cacheDir).doesNotExist();
            verify(auditLogService).logDelete(eq("excel_import_batch"), eq("BATCH-P1"), eq(batch), eq("system"));
        } finally {
            // 断言失败时兜底清理，避免污染后续测试
            Files.deleteIfExists(cacheFile);
            Files.deleteIfExists(cacheDir);
        }
    }

    @Test
    void cleanup_shouldSkipPendingBatchWhenConditionalDeleteMisses() throws IOException {
        // 批次删除未命中（状态已变化，如并发导入抢占为 importing）：不动行记录与文件
        ExcelImportBatch batch = excelBatch("BATCH-P2", "pending");
        when(excelImportBatchMapper.selectList(any()))
            .thenReturn(List.of(batch))
            .thenReturn(List.of());
        when(excelImportBatchMapper.delete(any(QueryWrapper.class))).thenReturn(0);

        cleanupService.cleanupExpiredImportData();

        verify(excelImportRowMapper, never()).deleteByBatchId(anyString());
        verify(storageService, never()).delete(anyString());
        verify(storageService, never()).deleteByPrefix(anyString());
        verify(auditLogService, never()).logDelete(anyString(), anyString(), any(), anyString());
    }

    @Test
    void cleanup_shouldNotTouchRecentOrImportingBatches() throws IOException {
        // 24h 内 pending / importing 批次不会被查询命中（状态 + 时间过滤在 SQL 层），候选为空时无任何删除
        cleanupService.cleanupExpiredImportData();

        verify(excelImportBatchMapper, never()).delete(any(QueryWrapper.class));
        verify(excelImportBatchMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(excelImportRowMapper, never()).deleteByBatchId(anyString());
        verify(documentImportBatchMapper, never()).delete(any(QueryWrapper.class));
        verifyNoInteractions(storageService, auditLogService);

        // 两次候选查询均带 status 过滤（pending 与 done/failed），importing 不在任何一侧
        ArgumentCaptor<QueryWrapper<ExcelImportBatch>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(excelImportBatchMapper, org.mockito.Mockito.times(2)).selectList(queryCaptor.capture());
        for (QueryWrapper<ExcelImportBatch> wrapper : queryCaptor.getAllValues()) {
            assertThat(wrapper.getSqlSegment()).contains("status");
        }
    }

    @Test
    void cleanup_shouldClearFileAndPreviewRowsForStaleDoneExcelBatchButKeepRow() throws IOException {
        // done 超 7 天：清 preview_rows + 删原始文件，批次行与行记录保留
        ExcelImportBatch batch = excelBatch("BATCH-D1", "done");
        when(excelImportBatchMapper.selectList(any()))
            .thenReturn(List.of())          // pending 候选为空
            .thenReturn(List.of(batch));    // done/failed 候选
        when(excelImportBatchMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        cleanupService.cleanupExpiredImportData();

        ArgumentCaptor<UpdateWrapper<ExcelImportBatch>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(excelImportBatchMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSegment()).contains("batch_id").contains("status");
        assertThat(updateCaptor.getValue().getSqlSet()).contains("preview_rows").contains("storage_path");

        verify(storageService).delete("excel-imports/BATCH-D1.xlsx");
        // 批次行不删、行记录不删（结果永久保留）
        verify(excelImportBatchMapper, never()).delete(any(QueryWrapper.class));
        verify(excelImportRowMapper, never()).deleteByBatchId(anyString());
        verify(auditLogService).logUpdate(eq("excel_import_batch"), eq("BATCH-D1"), eq(batch), any(), eq("system"));
    }

    @Test
    void cleanup_shouldSkipFileCleanupWhenConditionalUpdateMisses() throws IOException {
        // done/failed 清字段未命中（状态并发变化）：不删文件、不记审计
        ExcelImportBatch batch = excelBatch("BATCH-D2", "done");
        when(excelImportBatchMapper.selectList(any()))
            .thenReturn(List.of())
            .thenReturn(List.of(batch));
        when(excelImportBatchMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        cleanupService.cleanupExpiredImportData();

        verify(storageService, never()).delete(anyString());
        verify(auditLogService, never()).logUpdate(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void cleanup_shouldNotBlockOtherBatchesWhenSingleBatchFails() throws IOException {
        // 单条清理失败（DB 异常）不影响其余批次
        ExcelImportBatch failing = excelBatch("BATCH-F1", "pending");
        ExcelImportBatch normal = excelBatch("BATCH-F2", "pending");
        when(excelImportBatchMapper.selectList(any()))
            .thenReturn(List.of(failing, normal))
            .thenReturn(List.of());
        when(excelImportBatchMapper.delete(any(QueryWrapper.class)))
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(1);

        assertThatCode(() -> cleanupService.cleanupExpiredImportData()).doesNotThrowAnyException();

        verify(excelImportRowMapper).deleteByBatchId("BATCH-F2");
        verify(storageService).delete("excel-imports/BATCH-F2.xlsx");
        verify(auditLogService).logDelete(eq("excel_import_batch"), eq("BATCH-F2"), eq(normal), eq("system"));
    }

    @Test
    void cleanup_shouldSkipEverythingWhenDisabled() {
        properties.setEnabled(false);

        cleanupService.cleanupExpiredImportData();

        verifyNoInteractions(excelImportBatchMapper, excelImportRowMapper, documentImportBatchMapper,
            imageAssetsMapper, storageService, auditLogService);
    }

    @Test
    void cleanup_shouldFullyDeleteStalePendingDocumentBatch() throws IOException {
        // 文档批次 pending 超 24h：整批删除（批次行 + 原始 PDF 文件）
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("DOCB-P1");
        batch.setFileName("catalog.pdf");
        batch.setStoragePath("document-imports/DOCB-P1.pdf");
        batch.setStatus("pending");
        when(documentImportBatchMapper.selectList(any()))
            .thenReturn(List.of(batch))     // pending 候选
            .thenReturn(List.of());         // 终态候选为空
        when(documentImportBatchMapper.delete(any(QueryWrapper.class))).thenReturn(1);

        cleanupService.cleanupExpiredImportData();

        ArgumentCaptor<QueryWrapper<DocumentImportBatch>> deleteCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentImportBatchMapper).delete(deleteCaptor.capture());
        // 注意：MP 参数映射在 getSqlSegment() 触发后才填充，断言顺序不能颠倒
        assertThat(deleteCaptor.getValue().getSqlSegment()).contains("batch_id").contains("status");
        assertThat(deleteCaptor.getValue().getParamNameValuePairs())
            .containsValue("DOCB-P1").containsValue("pending");
        verify(storageService).delete("document-imports/DOCB-P1.pdf");
        verify(auditLogService).logDelete(eq("document_import_batch"), eq("DOCB-P1"), eq(batch), eq("system"));
    }

    @Test
    void cleanup_shouldDeleteFileForStaleCompletedDocumentBatchButKeepRowAndResults() throws IOException {
        // 文档批次 done 超 7 天：删原始文件 + storage_path 置空，批次行与 pageResults 结果保留
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("DOCB-D1");
        batch.setStoragePath("document-imports/DOCB-D1.pdf");
        batch.setStatus("done");
        batch.setPageResults("[{\"page\":1}]");
        when(documentImportBatchMapper.selectList(any()))
            .thenReturn(List.of())
            .thenReturn(List.of(batch));
        when(documentImportBatchMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        cleanupService.cleanupExpiredImportData();

        ArgumentCaptor<UpdateWrapper<DocumentImportBatch>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(documentImportBatchMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("storage_path");
        // page_results 不在清理范围内（结果永久保留）
        assertThat(updateCaptor.getValue().getSqlSet()).doesNotContain("page_results");

        verify(storageService).delete("document-imports/DOCB-D1.pdf");
        verify(documentImportBatchMapper, never()).delete(any(QueryWrapper.class));
        verify(auditLogService).logUpdate(eq("document_import_batch"), eq("DOCB-D1"), eq(batch), any(), eq("system"));
    }

    @Test
    void cleanup_shouldNotQueryOrphanImagesByDefault() {
        // 孤儿清理默认关闭：不查询、不删文件
        cleanupService.cleanupExpiredImportData();

        verifyNoInteractions(imageAssetsMapper);
        verifyNoInteractions(storageService);
    }

    @Test
    void cleanup_shouldDeleteOrphanFilesOnlyForLongSoftDeletedAssetsWhenEnabled() throws IOException {
        // 打开开关：软删超 30 天行的存储文件物理删除（阈值在 SQL 层），行保留不删；单条失败不阻断
        properties.setOrphanImageCleanupEnabled(true);
        ImageAssets stale = new ImageAssets();
        stale.setImageId("IMG-OLD1");
        stale.setStoragePath("images/IMG-OLD1.jpg");
        ImageAssets failing = new ImageAssets();
        failing.setImageId("IMG-OLD2");
        failing.setStoragePath("images/IMG-OLD2.jpg");
        when(imageAssetsMapper.selectSoftDeletedBefore(any(), anyInt()))
            .thenReturn(List.of(stale, failing));
        doThrow(new IOException("minio down")).when(storageService).delete("images/IMG-OLD2.jpg");

        assertThatCode(() -> cleanupService.cleanupExpiredImportData()).doesNotThrowAnyException();

        // 阈值 = 当前时间 - 30 天
        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(imageAssetsMapper).selectSoftDeletedBefore(thresholdCaptor.capture(), eq(200));
        assertThat(thresholdCaptor.getValue()).isBetween(
            LocalDateTime.now().minusDays(30).minusMinutes(1),
            LocalDateTime.now().minusDays(30).plusMinutes(1));

        verify(storageService).delete("images/IMG-OLD1.jpg");
        verify(storageService).delete("images/IMG-OLD2.jpg");
        // 软删行本身不物理删除
        verify(imageAssetsMapper, never()).deleteById(anyString());
        verify(imageAssetsMapper, never()).physicalDeleteByRspuId(anyString());
    }
}
