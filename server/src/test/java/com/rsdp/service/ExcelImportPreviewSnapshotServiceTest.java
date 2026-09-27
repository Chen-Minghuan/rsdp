package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.CategoryMode;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportPreviewRevision;
import com.rsdp.entity.ExcelImportProductGroup;
import com.rsdp.entity.ExcelImportSensitiveValue;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.PreviewRevisionConflictException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ExcelImportPreviewRevisionMapper;
import com.rsdp.mapper.ExcelImportProductGroupMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelImportPreviewSnapshotService} 单元测试
 * （设计文档 v3.3 §20 / §21 / §45 / §46 / §47 / §77.3 Preview Revision）。
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportPreviewSnapshotServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BATCH_ID = "EXCEL-TEST-0001";
    private static final String MAPPING_HASH = "a".repeat(64);
    private static final String NORMALIZER_VERSION = "excel-normalizer/1";

    @InjectMocks
    private ExcelImportPreviewSnapshotService service;

    @Mock
    private ExcelImportBatchMapper batchMapper;
    @Mock
    private ExcelImportPreviewRevisionMapper revisionMapper;
    @Mock
    private ExcelImportProductGroupMapper groupMapper;
    @Mock
    private ExcelImportSensitiveValueService sensitiveValueService;

    private static JsonNode payload(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExcelImportPreviewSnapshotService.GroupInput group(String key, String payloadJson) {
        return new ExcelImportPreviewSnapshotService.GroupInput(key, payload(payloadJson), List.of());
    }

    private static ExcelImportPreviewSnapshotService.CreateRevisionCommand command(
        Long expectedRevision, CategoryMode mode, String hint, List<String> candidates,
        List<ExcelImportPreviewSnapshotService.GroupInput> groups) {
        return new ExcelImportPreviewSnapshotService.CreateRevisionCommand(
            BATCH_ID, expectedRevision, 0, "沙发", mode, hint, candidates,
            MAPPING_HASH, NORMALIZER_VERSION, groups, "tester");
    }

    private static ExcelImportBatch batchWithRevision(Long currentRevision) {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId(BATCH_ID);
        batch.setPreviewRevision(currentRevision);
        return batch;
    }

    private void stubLockAndCas(ExcelImportBatch batch) {
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(batch);
        lenient().when(batchMapper.advancePreviewRevision(
            eq(BATCH_ID), any(), anyLong(), eq(MAPPING_HASH), eq(NORMALIZER_VERSION))).thenReturn(1);
    }

    // ---- 创建：首版与递增（§45 / §46）----

    @Test
    void createFirstRevision_shouldInsertRevisionOneAndAdvancePointerFromNull() {
        stubLockAndCas(batchWithRevision(null));

        ExcelImportPreviewRevision revision = service.createNextRevision(command(
            null, CategoryMode.MIXED, null, List.of("DINING_TABLE", "DINING_CHAIR"),
            List.of(group("G1", "{\"rows\":[{\"sourceRowIndex\":27,\"finalCategoryCode\":\"DINING_CHAIR\"}]}"))));

        assertEquals(1L, revision.getPreviewRevision());
        assertEquals(BATCH_ID, revision.getBatchId());
        assertEquals("MIXED", revision.getCategoryMode());
        assertEquals(ExcelImportPreviewSnapshotService.HASH_VERSION, revision.getHashVersion());
        assertTrue(revision.getSnapshotHash().matches("[0-9a-f]{64}"));

        ArgumentCaptor<ExcelImportProductGroup> groupCaptor = ArgumentCaptor.forClass(ExcelImportProductGroup.class);
        verify(groupMapper, times(1)).insert(groupCaptor.capture());
        ExcelImportProductGroup stored = groupCaptor.getValue();
        assertEquals(1L, stored.getPreviewRevision());
        assertEquals("G1", stored.getProductGroupKey());
        assertTrue(stored.getPayloadHash().matches("[0-9a-f]{64}"));
        assertTrue(stored.getGroupId().startsWith("PGROUP-"));

        // §45：指针 CAS 推进，expected=null ↔ 尚无快照
        verify(batchMapper).advancePreviewRevision(BATCH_ID, null, 1L, MAPPING_HASH, NORMALIZER_VERSION);
    }

    @Test
    void sourceRows_areSealedOutsidePublicPayloadAndVerifiedBeforeUse() {
        stubLockAndCas(batchWithRevision(null));
        ExcelImportSensitiveValue source = new ExcelImportSensitiveValue();
        source.setSensitiveValueId("SENS-1");
        source.setValuePath("/sourceRows");
        source.setValueHmac("a".repeat(64));
        source.setHmacKeyVersion(1);
        when(sensitiveValueService.prepareGroupSourceRows(eq(BATCH_ID), eq(1L), anyString(), anyString()))
            .thenAnswer(inv -> {
                source.setBatchId(BATCH_ID);
                source.setPreviewRevision(1L);
                source.setGroupId(inv.getArgument(2));
                return source;
            });
        JsonNode rows = payload("[{\"__rowIndex__\":\"2\",\"出厂价\":\"123.45\"}]");
        service.createNextRevision(command(null, CategoryMode.SINGLE, "SOFA", null,
            List.of(new ExcelImportPreviewSnapshotService.GroupInput("G1", payload("{\"rows\":[]}"),
                List.of(), rows))));

        ArgumentCaptor<ExcelImportProductGroup> groupCaptor = ArgumentCaptor.forClass(ExcelImportProductGroup.class);
        verify(groupMapper).insert(groupCaptor.capture());
        ExcelImportProductGroup stored = groupCaptor.getValue();
        assertTrue(stored.getNormalizedPayload().contains("SENS-1"));
        assertFalse(stored.getNormalizedPayload().contains("123.45"));
        verify(sensitiveValueService).insertPrepared(source);

        ArgumentCaptor<ExcelImportPreviewRevision> revisionCaptor =
            ArgumentCaptor.forClass(ExcelImportPreviewRevision.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        when(revisionMapper.selectByRevision(BATCH_ID, 1L)).thenReturn(revisionCaptor.getValue());
        when(groupMapper.listByRevision(BATCH_ID, 1L)).thenReturn(List.of(stored));
        when(sensitiveValueService.listGroupValues(BATCH_ID, 1L)).thenReturn(List.of(source));
        when(sensitiveValueService.readGroupSourceRows(BATCH_ID, 1L, stored.getGroupId(), "SENS-1"))
            .thenReturn(rows.toString());
        assertTrue(service.verifySnapshotHash(BATCH_ID, 1L));
        assertEquals("123.45", service.loadSourceRows(service.getSnapshot(BATCH_ID, 1L).orElseThrow())
            .get(0).get("出厂价"));
        stored.setNormalizedPayload("{\"rows\":[],\"sourceRowsRef\":\"SENS-other\"}");
        assertFalse(service.verifySnapshotHash(BATCH_ID, 1L));
    }

    @Test
    void createNextRevision_shouldIncrementFromExpectedRevision() {
        stubLockAndCas(batchWithRevision(7L));

        ExcelImportPreviewRevision revision = service.createNextRevision(command(
            7L, CategoryMode.SINGLE, "SOFA", null,
            List.of(group("G1", "{}"))));

        assertEquals(8L, revision.getPreviewRevision());
        assertEquals("SINGLE", revision.getCategoryMode());
        verify(batchMapper).advancePreviewRevision(BATCH_ID, 7L, 8L, MAPPING_HASH, NORMALIZER_VERSION);
    }

    @Test
    void createRevision_shouldAllowEmptyGroups() {
        stubLockAndCas(batchWithRevision(null));

        ExcelImportPreviewRevision revision = service.createNextRevision(command(
            null, null, null, null, List.of()));

        assertEquals(1L, revision.getPreviewRevision());
        verify(groupMapper, never()).insert(any(ExcelImportProductGroup.class));
    }

    // ---- Optimistic Lock 冲突（§47）----

    @Test
    void createRevision_shouldRejectStaleExpectedRevisionWith409() {
        // §47：expectedRevision=7 但当前已是 8 → 409 preview_revision_conflict，零写入
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(batchWithRevision(8L));

        PreviewRevisionConflictException e = assertThrows(PreviewRevisionConflictException.class,
            () -> service.createNextRevision(command(7L, null, null, null, List.of())));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().startsWith("preview_revision_conflict"));
        verify(revisionMapper, never()).insert(any());
        verify(groupMapper, never()).insert(any(ExcelImportProductGroup.class));
        verify(batchMapper, never()).advancePreviewRevision(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void createFirstRevision_shouldConflictWhenSnapshotAlreadyExists() {
        // expected=null（期望尚无快照）但当前已推进到 3 → 409
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(batchWithRevision(3L));

        assertThrows(PreviewRevisionConflictException.class,
            () -> service.createNextRevision(command(null, null, null, null, List.of())));
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void createRevision_shouldTranslateConcurrentDuplicateKeyToConflict() {
        // 防御：行锁之外仍撞 (batch_id, preview_revision) 复合主键 → 409（§47）
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(batchWithRevision(null));
        when(revisionMapper.insert(any())).thenThrow(new DuplicateKeyException("pk_import_preview_revision"));

        assertThrows(PreviewRevisionConflictException.class,
            () -> service.createNextRevision(command(null, null, null, null, List.of())));
        verify(batchMapper, never()).advancePreviewRevision(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void createRevision_shouldRollbackWhenCasAdvanceFails() {
        // CAS 推进 0 行（期间被其他编辑推进）→ 409，事务回滚新 Revision
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(batchWithRevision(null));
        when(batchMapper.advancePreviewRevision(eq(BATCH_ID), isNull(), eq(1L), anyString(), anyString()))
            .thenReturn(0);

        assertThrows(PreviewRevisionConflictException.class,
            () -> service.createNextRevision(command(null, null, null, null, List.of())));
    }

    // ---- 入参与品类策略校验（§20.1 ck_import_preview_category_policy）----

    @Test
    void createRevision_shouldRejectMissingBatch() {
        when(batchMapper.selectForUpdate(BATCH_ID)).thenReturn(null);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
            () -> service.createNextRevision(command(null, null, null, null, List.of())));
        assertEquals(404, e.getCode());
    }

    @Test
    void createRevision_shouldValidateCategoryPolicyBeforeTouchingDb() {
        // SINGLE 缺 hint / SINGLE 带候选集 / MIXED 带 hint / MIXED 候选集 < 2 → 400
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, CategoryMode.SINGLE, null, null, List.of())));
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, CategoryMode.SINGLE, "SOFA", List.of("SOFA"), List.of())));
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, CategoryMode.MIXED, "SOFA", List.of("SOFA", "CHAIR"), List.of())));
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, CategoryMode.MIXED, null, List.of("SOFA"), List.of())));
        verifyNoInteractions(batchMapper, revisionMapper, groupMapper);
    }

    @Test
    void createRevision_shouldRejectDuplicateGroupKeyAndInvalidGroups() {
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, null, null, null, List.of(group("G1", "{}"), group("G1", "{\"a\":1}")))));
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, null, null, null, List.of(group(" ", "{}")))));
        assertThrows(BusinessException.class, () -> service.createNextRevision(command(
            null, null, null, null, null)));
        verifyNoInteractions(batchMapper, revisionMapper, groupMapper);
    }

    // ---- 快照哈希（§47.1.3 / §77.3 confirmed snapshot hash 校验）----

    @Test
    void snapshotHash_shouldBeDeterministicAndIndependentOfGroupOrder() {
        // 同一业务输入（组传入顺序不同）→ 相同 snapshot_hash（groups 按 productGroupKey 排序，§47.1.3）
        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.MIXED, null,
            List.of("SOFA", "CHAIR"),
            List.of(group("G2", "{\"b\":2}"), group("G1", "{\"a\":1}"))));

        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.MIXED, null,
            List.of("SOFA", "CHAIR"),
            List.of(group("G1", "{\"a\":1}"), group("G2", "{\"b\":2}"))));

        ArgumentCaptor<ExcelImportPreviewRevision> captor = ArgumentCaptor.forClass(ExcelImportPreviewRevision.class);
        verify(revisionMapper, times(2)).insert(captor.capture());
        assertEquals(captor.getAllValues().get(0).getSnapshotHash(),
            captor.getAllValues().get(1).getSnapshotHash());
    }

    @Test
    void snapshotHash_shouldChangeWhenAnyGroupPayloadOrCategoryPolicyChanges() {
        // §20.1：品类策略或任一行最终品类变化都会改变整体快照哈希
        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.MIXED, null,
            List.of("SOFA", "CHAIR"), List.of(group("G1", "{\"finalCategoryCode\":\"CHAIR\"}"))));
        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.MIXED, null,
            List.of("SOFA", "CHAIR"), List.of(group("G1", "{\"finalCategoryCode\":\"SOFA\"}"))));
        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.SINGLE, "SOFA",
            null, List.of(group("G1", "{\"finalCategoryCode\":\"CHAIR\"}"))));

        ArgumentCaptor<ExcelImportPreviewRevision> captor = ArgumentCaptor.forClass(ExcelImportPreviewRevision.class);
        verify(revisionMapper, times(3)).insert(captor.capture());
        String h1 = captor.getAllValues().get(0).getSnapshotHash();
        String h2 = captor.getAllValues().get(1).getSnapshotHash();
        String h3 = captor.getAllValues().get(2).getSnapshotHash();
        assertNotEquals(h1, h2);
        assertNotEquals(h1, h3);
    }

    @Test
    void verifySnapshotHash_shouldRoundTripAndDetectTamper() {
        // 创建 → 回读（模拟 DB 行）→ 重放校验通过；篡改任一 payload_hash 后失配（§77.3）
        stubLockAndCas(batchWithRevision(null));
        service.createNextRevision(command(null, CategoryMode.MIXED, null,
            List.of("SOFA", "CHAIR"),
            List.of(group("G1", "{\"a\":1}"), group("G2", "{\"b\":2}"))));

        ArgumentCaptor<ExcelImportPreviewRevision> revisionCaptor =
            ArgumentCaptor.forClass(ExcelImportPreviewRevision.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        ArgumentCaptor<ExcelImportProductGroup> groupCaptor = ArgumentCaptor.forClass(ExcelImportProductGroup.class);
        verify(groupMapper, times(2)).insert(groupCaptor.capture());

        ExcelImportPreviewRevision stored = revisionCaptor.getValue();
        List<ExcelImportProductGroup> storedGroups = groupCaptor.getAllValues();
        when(revisionMapper.selectByRevision(BATCH_ID, 1L)).thenReturn(stored);
        when(groupMapper.listByRevision(BATCH_ID, 1L)).thenReturn(storedGroups);

        assertTrue(service.verifySnapshotHash(BATCH_ID, 1L));

        storedGroups.get(0).setPayloadHash("f".repeat(64));
        assertFalse(service.verifySnapshotHash(BATCH_ID, 1L));

        when(revisionMapper.selectByRevision(BATCH_ID, 99L)).thenReturn(null);
        assertFalse(service.verifySnapshotHash(BATCH_ID, 99L));
    }

    // ---- 读取入口（§22 LOAD）----

    @Test
    void getCurrentSnapshot_shouldFollowBatchPointer() {
        ExcelImportBatch batch = batchWithRevision(2L);
        when(batchMapper.selectById(BATCH_ID)).thenReturn(batch);
        ExcelImportPreviewRevision revision = new ExcelImportPreviewRevision();
        revision.setBatchId(BATCH_ID);
        revision.setPreviewRevision(2L);
        when(revisionMapper.selectByRevision(BATCH_ID, 2L)).thenReturn(revision);
        when(groupMapper.listByRevision(BATCH_ID, 2L)).thenReturn(List.of());

        assertTrue(service.getCurrentSnapshot(BATCH_ID).isPresent());
        assertEquals(2L, service.getCurrentSnapshot(BATCH_ID).get().getRevision().getPreviewRevision());
    }

    @Test
    void getCurrentSnapshot_shouldReturnEmptyWhenNoSnapshotOrNoBatch() {
        when(batchMapper.selectById(BATCH_ID)).thenReturn(batchWithRevision(null));
        assertTrue(service.getCurrentSnapshot(BATCH_ID).isEmpty());

        when(batchMapper.selectById(BATCH_ID)).thenReturn(null);
        assertTrue(service.getCurrentSnapshot(BATCH_ID).isEmpty());
    }
}
