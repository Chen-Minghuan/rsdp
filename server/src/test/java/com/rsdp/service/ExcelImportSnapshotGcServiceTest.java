package com.rsdp.service;

import com.rsdp.config.properties.ExcelImportGovernanceProperties;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportPreviewRevision;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ExcelImportPreviewRevisionMapper;
import com.rsdp.mapper.ExcelImportSnapshotGcMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B3 受限 Snapshot GC 单元测试（设计文档 v3.3 §44 / §44.2）。
 *
 * <p>覆盖：保留期判定（未 Confirm 180 天 / 已 Confirm 365 天）、retention_hold /
 * 导入中 / 指针已空三类守护条件跳过、归档摘要内容边界（只含 revision 号 / hash /
 * confirm 元数据 / 计数，不含 payload / 敏感值）、指针归档 CAS 冲突放弃、
 * 单批失败计数与幂等续跑。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportSnapshotGcServiceTest {

    private static final String BATCH = "B-GC-1";

    @InjectMocks
    private ExcelImportSnapshotGcService gcService;

    @Spy
    private ExcelImportGovernanceProperties properties = new ExcelImportGovernanceProperties();
    @Mock
    private ExcelImportSnapshotGcMapper gcMapper;
    @Mock
    private ExcelImportBatchMapper batchMapper;
    @Mock
    private ExcelImportPreviewRevisionMapper revisionMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void runOnce_unconfirmedExpired_archivesPointersAndDeletesRevisions() {
        ExcelImportBatch batch = batch(null, 2L, false, "pending");
        stubCandidate(batch);
        // 未 Confirm：按当前指针 Revision 的 created_at 起算 180 天
        when(revisionMapper.selectByRevision(BATCH, 2L))
            .thenReturn(revision(2L, LocalDateTime.now().minusDays(200)));
        when(revisionMapper.listByBatch(BATCH))
            .thenReturn(List.of(revision(1L, LocalDateTime.now().minusDays(210)),
                revision(2L, LocalDateTime.now().minusDays(200))));
        when(gcMapper.archiveSnapshotPointers(eq(BATCH), anyString())).thenReturn(1);
        when(gcMapper.deleteRevisionsByBatch(BATCH)).thenReturn(2);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(1, result.get("archived"));
        assertEquals(0, result.get("failed"));
        // 归档摘要内容边界（§44.2）：revision 号 / hash / 计数可留，payload / 敏感值禁止
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(gcMapper).archiveSnapshotPointers(eq(BATCH), summaryCaptor.capture());
        String summary = summaryCaptor.getValue();
        assertTrue(summary.contains("retention_expired"), summary);
        assertTrue(summary.contains("\"revisionCount\":2"), summary);
        assertTrue(summary.contains("snapshotHash"), summary);
        assertFalse(summary.contains("normalizedPayload"), summary);
        assertFalse(summary.contains("ciphertext"), summary);
        verify(gcMapper).deleteRevisionsByBatch(BATCH);
        verify(auditLogService).logUpdate(eq("excel_import_batch"), eq(BATCH), any(), any(), eq("system"));
    }

    @Test
    void runOnce_confirmedRetentionCountsFromConfirmedAt() {
        // 已 Confirm 365 天：confirmed_at 400 天前 → 过期
        ExcelImportBatch batch = batch(3L, 2L, false, "done");
        batch.setConfirmedAt(LocalDateTime.now().minusDays(400));
        stubCandidate(batch);
        when(revisionMapper.listByBatch(BATCH)).thenReturn(List.of(revision(2L, null)));
        when(gcMapper.archiveSnapshotPointers(eq(BATCH), anyString())).thenReturn(1);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(1, result.get("archived"));
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(gcMapper).archiveSnapshotPointers(eq(BATCH), summaryCaptor.capture());
        assertTrue(summaryCaptor.getValue().contains("\"confirmedRevision\":3"),
            summaryCaptor.getValue());
    }

    @Test
    void runOnce_confirmedWithinRetention_skipped() {
        // 已 Confirm 但 confirmed_at 仅 10 天前 → 保留
        ExcelImportBatch batch = batch(3L, 2L, false, "done");
        batch.setConfirmedAt(LocalDateTime.now().minusDays(10));
        stubCandidate(batch);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(0, result.get("archived"));
        verify(gcMapper, never()).archiveSnapshotPointers(anyString(), anyString());
        verify(gcMapper, never()).deleteRevisionsByBatch(anyString());
    }

    @Test
    void runOnce_retentionHold_skipped() {
        ExcelImportBatch batch = batch(null, 2L, true, "pending");
        stubCandidate(batch);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(0, result.get("archived"));
        verify(gcMapper, never()).archiveSnapshotPointers(anyString(), anyString());
    }

    @Test
    void runOnce_importing_skipped() {
        ExcelImportBatch batch = batch(null, 2L, false, "importing");
        stubCandidate(batch);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(0, result.get("archived"));
        verify(gcMapper, never()).archiveSnapshotPointers(anyString(), anyString());
    }

    @Test
    void runOnce_pointerAlreadyArchived_skipped() {
        // 指针已空（上一轮已归档或并发归档）：整批保留
        ExcelImportBatch batch = batch(null, null, false, "pending");
        stubCandidate(batch);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(0, result.get("archived"));
        verify(gcMapper, never()).archiveSnapshotPointers(anyString(), anyString());
    }

    @Test
    void runOnce_archiveCasConflict_abortsBatchWithoutDelete() {
        ExcelImportBatch batch = batch(null, 2L, false, "pending");
        stubCandidate(batch);
        when(revisionMapper.selectByRevision(BATCH, 2L))
            .thenReturn(revision(2L, LocalDateTime.now().minusDays(200)));
        when(revisionMapper.listByBatch(BATCH)).thenReturn(List.of(revision(2L, null)));
        // 并发确认/导入/置 hold：CAS 守护失败 → 不删底表
        when(gcMapper.archiveSnapshotPointers(eq(BATCH), anyString())).thenReturn(0);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(0, result.get("archived"));
        verify(gcMapper, never()).deleteRevisionsByBatch(anyString());
    }

    @Test
    void runOnce_batchFailure_countedAndContinues() {
        ExcelImportBatch broken = batch(null, 2L, false, "pending");
        ExcelImportBatch healthy = batch(null, 5L, false, "pending");
        healthy.setBatchId("B-GC-2");
        when(gcMapper.selectGcCandidates(any(), any(), anyInt()))
            .thenReturn(List.of(broken, healthy));
        when(batchMapper.selectForUpdate(BATCH)).thenThrow(new RuntimeException("db down"));
        when(batchMapper.selectForUpdate("B-GC-2")).thenReturn(healthy);
        when(revisionMapper.selectByRevision("B-GC-2", 5L))
            .thenReturn(revision(5L, LocalDateTime.now().minusDays(400)));
        when(revisionMapper.listByBatch("B-GC-2")).thenReturn(List.of(revision(5L, null)));
        when(gcMapper.archiveSnapshotPointers(eq("B-GC-2"), anyString())).thenReturn(1);

        Map<String, Integer> result = gcService.runOnce();

        assertEquals(2, result.get("scanned"));
        assertEquals(1, result.get("archived"));
        assertEquals(1, result.get("failed"));
    }

    @Test
    void scheduledGc_disabled_noop() {
        properties.getSnapshotGc().setEnabled(false);

        gcService.scheduledGc();

        verify(gcMapper, never()).selectGcCandidates(any(), any(), anyInt());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void stubCandidate(ExcelImportBatch batch) {
        when(gcMapper.selectGcCandidates(any(), any(), anyInt())).thenReturn(List.of(batch));
        when(batchMapper.selectForUpdate(batch.getBatchId())).thenReturn(batch);
    }

    private ExcelImportBatch batch(Long confirmedRevision, Long previewRevision,
                                   boolean retentionHold, String status) {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId(BATCH);
        batch.setStatus(status);
        batch.setPreviewRevision(previewRevision);
        batch.setConfirmedRevision(confirmedRevision);
        batch.setRetentionHold(retentionHold);
        batch.setSuccessCount(3);
        batch.setFailedCount(1);
        return batch;
    }

    private ExcelImportPreviewRevision revision(long revision, LocalDateTime createdAt) {
        ExcelImportPreviewRevision header = new ExcelImportPreviewRevision();
        header.setBatchId(BATCH);
        header.setPreviewRevision(revision);
        header.setSnapshotHash("hash-" + revision);
        header.setHashVersion(1);
        header.setCreatedAt(createdAt);
        return header;
    }
}
