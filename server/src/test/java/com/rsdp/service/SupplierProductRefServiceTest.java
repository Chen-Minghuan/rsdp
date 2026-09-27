package com.rsdp.service;

import com.rsdp.entity.SupplierProductRef;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.SourceCodeReusedException;
import com.rsdp.mapper.SupplierProductRefMapper;
import com.rsdp.util.SourceCodeNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupplierProductRefService} 单元测试（设计文档 v3.3 §6 / §7 / §77.1 Supplier Identity）。
 */
@ExtendWith(MockitoExtension.class)
class SupplierProductRefServiceTest {

    @InjectMocks
    private SupplierProductRefService service;

    @Mock
    private SupplierProductRefMapper mapper;

    private static SupplierProductRefService.ResolveCommand command(String rspuId, String factoryCode,
                                                                    String namespace, String sourceSystem,
                                                                    String rawCode, String batchId) {
        return new SupplierProductRefService.ResolveCommand(
            rspuId, factoryCode, namespace, sourceSystem, rawCode, "源商品名称", batchId);
    }

    private static SupplierProductRef existingRef(String rspuId) {
        SupplierProductRef ref = new SupplierProductRef();
        ref.setRefId(1L);
        ref.setRspuId(rspuId);
        ref.setFactoryCode("FA");
        ref.setSourceNamespace("default");
        ref.setFirstSeenSourceSystem("excel");
        ref.setLastSeenSourceSystem("excel");
        ref.setSourceProductCode("MJ-S96");
        ref.setNormalizedSourceProductCode("MJ-S96");
        ref.setCodeNormalizerVersion("1");
        ref.setFirstImportBatchId("batch-1");
        ref.setLastImportBatchId("batch-1");
        return ref;
    }

    // ---- findByIdentity：身份解析 / 查重入口 ----

    @Test
    void findByIdentity_shouldNormalizeBeforeQuery() {
        SupplierProductRef ref = existingRef("RSPU-1");
        when(mapper.selectByIdentity(anyString(), anyString(), anyString())).thenReturn(ref);

        // §77.1「normalized code 等价输入」：" mj - s96 " 与 "MJ-S96" 归一等价
        Optional<SupplierProductRef> result = service.findByIdentity("FA", "default", " mj - s96 ");

        assertTrue(result.isPresent());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectByIdentity(anyString(), anyString(), codeCaptor.capture());
        assertEquals("MJ-S96", codeCaptor.getValue());
    }

    @Test
    void findByIdentity_shouldDefaultNamespaceAndReturnEmptyWhenMissing() {
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(null);

        assertTrue(service.findByIdentity("FA", null, "MJ-S96").isEmpty());
        assertTrue(service.findByIdentity("FA", "  ", "MJ-S96").isEmpty());
    }

    @Test
    void findByIdentity_shouldReturnEmptyOnBlankInput() {
        assertTrue(service.findByIdentity(null, "default", "MJ-S96").isEmpty());
        assertTrue(service.findByIdentity("FA", "default", " ").isEmpty());
    }

    @Test
    void findByIdentity_shouldRejectUnsupportedNormalizerVersion() {
        // §7：不支持的版本不允许静默回落，直接拒绝
        assertThrows(IllegalArgumentException.class,
            () -> service.findByIdentity("FA", "default", "MJ-S96", 99));
    }

    // ---- resolveOrCreate：创建路径 ----

    @Test
    void resolveOrCreate_shouldInsertWithCurrentNormalizerVersionWhenAbsent() {
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(null);

        SupplierProductRef created = service.resolveOrCreate(
            command("RSPU-1", "FA", null, "excel", " mj - s96 ", "batch-9"));

        ArgumentCaptor<SupplierProductRef> captor = ArgumentCaptor.forClass(SupplierProductRef.class);
        verify(mapper).insert(captor.capture());
        SupplierProductRef saved = captor.getValue();
        // §7：原文不修改；归一值与版本号同时持久化
        assertEquals(" mj - s96 ", saved.getSourceProductCode());
        assertEquals("MJ-S96", saved.getNormalizedSourceProductCode());
        assertEquals(String.valueOf(SourceCodeNormalizer.CURRENT_VERSION), saved.getCodeNormalizerVersion());
        // §6.1：namespace 缺省落 default
        assertEquals("default", saved.getSourceNamespace());
        assertEquals("RSPU-1", saved.getRspuId());
        assertEquals("excel", saved.getFirstSeenSourceSystem());
        assertEquals("excel", saved.getLastSeenSourceSystem());
        assertEquals("batch-9", saved.getFirstImportBatchId());
        assertEquals("batch-9", saved.getLastImportBatchId());
        assertSame(saved, created);
    }

    // ---- resolveOrCreate：复用路径 ----

    @Test
    void resolveOrCreate_shouldReuseExistingRefAcrossSourceSystems() {
        // §77.1「不同 source_system 但相同 source_namespace 命中同一 Supplier Ref」
        SupplierProductRef existing = existingRef("RSPU-1");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(existing);

        SupplierProductRef result = service.resolveOrCreate(
            command("RSPU-1", "FA", "default", "erp", "MJ-S96", "batch-2"));

        assertSame(existing, result);
        // provenance 刷新，不产生新记录
        assertEquals("erp", existing.getLastSeenSourceSystem());
        assertEquals("batch-2", existing.getLastImportBatchId());
        assertEquals("excel", existing.getFirstSeenSourceSystem());
        assertEquals("batch-1", existing.getFirstImportBatchId());
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(SupplierProductRef.class));
    }

    @Test
    void resolveOrCreate_shouldNotUpdateWhenProvenanceUnchanged() {
        SupplierProductRef existing = existingRef("RSPU-1");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(existing);

        SupplierProductRef result = service.resolveOrCreate(
            command("RSPU-1", "FA", "default", "excel", "MJ-S96", "batch-1"));

        assertSame(existing, result);
        verify(mapper, never()).updateById(any(SupplierProductRef.class));
        verify(mapper, never()).insert(any(SupplierProductRef.class));
    }

    @Test
    void resolveOrCreate_shouldHitSameRefForNormalizedEquivalentInput() {
        SupplierProductRef existing = existingRef("RSPU-1");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(existing);

        SupplierProductRef result = service.resolveOrCreate(
            command("RSPU-1", "FA", null, "excel", "ＭＪ－Ｓ９６", "batch-1"));

        assertSame(existing, result);
        verify(mapper, never()).insert(any(SupplierProductRef.class));
    }

    // ---- resolveOrCreate：冲突路径（§6.2 SOURCE_CODE_REUSED） ----

    @Test
    void resolveOrCreate_shouldThrowSourceCodeReusedWhenBoundToOtherRspu() {
        SupplierProductRef existing = existingRef("RSPU-OLD");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(existing);

        SourceCodeReusedException ex = assertThrows(SourceCodeReusedException.class,
            () -> service.resolveOrCreate(command("RSPU-NEW", "FA", null, "excel", "MJ-S96", "batch-2")));

        // §6.2：blocker / product_blocking，进入人工治理，不自动改绑
        assertEquals("SOURCE_CODE_REUSED", ex.getIssueCode());
        assertEquals("blocker", ex.getSeverity());
        assertEquals("product_blocking", ex.getScope());
        assertSame(existing, ex.getExistingRef());
        assertEquals("RSPU-NEW", ex.getIncomingRspuId());
        assertEquals("RSPU-OLD", existing.getRspuId());
        verify(mapper, never()).updateById(any(SupplierProductRef.class));
        verify(mapper, never()).insert(any(SupplierProductRef.class));
    }

    // ---- resolveOrCreate：并发路径（§77.1「并发导入相同 Factory / S001」） ----

    @Test
    void resolveOrCreate_shouldRereadOnConcurrentUniqueViolation() {
        SupplierProductRef existing = existingRef("RSPU-1");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96"))
            .thenReturn(null)   // 首次查重：不存在
            .thenReturn(existing); // 撞唯一约束后回读：并发方已插入
        when(mapper.insert(any(SupplierProductRef.class))).thenThrow(new DuplicateKeyException("uk_supplier_product_ref"));

        SupplierProductRef result = service.resolveOrCreate(
            command("RSPU-1", "FA", null, "excel", "MJ-S96", "batch-1"));

        assertSame(existing, result);
    }

    @Test
    void resolveOrCreate_shouldThrowSourceCodeReusedOnConcurrentConflictWithOtherRspu() {
        SupplierProductRef existing = existingRef("RSPU-OLD");
        when(mapper.selectByIdentity("FA", "default", "MJ-S96"))
            .thenReturn(null)
            .thenReturn(existing);
        when(mapper.insert(any(SupplierProductRef.class))).thenThrow(new DuplicateKeyException("uk_supplier_product_ref"));

        assertThrows(SourceCodeReusedException.class,
            () -> service.resolveOrCreate(command("RSPU-NEW", "FA", null, "excel", "MJ-S96", "batch-2")));
    }

    @Test
    void resolveOrCreate_shouldRethrowWhenConcurrentRowDisappears() {
        when(mapper.selectByIdentity("FA", "default", "MJ-S96")).thenReturn(null);
        when(mapper.insert(any(SupplierProductRef.class))).thenThrow(new DuplicateKeyException("uk_supplier_product_ref"));

        assertThrows(DuplicateKeyException.class,
            () -> service.resolveOrCreate(command("RSPU-1", "FA", null, "excel", "MJ-S96", "batch-1")));
    }

    // ---- resolveOrCreate：入参校验 ----

    @Test
    void resolveOrCreate_shouldRejectMissingRequiredFields() {
        assertThrows(BusinessException.class,
            () -> service.resolveOrCreate(command(null, "FA", null, "excel", "MJ-S96", "b")));
        assertThrows(BusinessException.class,
            () -> service.resolveOrCreate(command("RSPU-1", " ", null, "excel", "MJ-S96", "b")));
        assertThrows(BusinessException.class,
            () -> service.resolveOrCreate(command("RSPU-1", "FA", null, "excel", "  ", "b")));
        assertThrows(BusinessException.class, () -> service.resolveOrCreate(null));
    }

    // ---- 迁移完成条件核验（§7 第五步 zero stale normalizer version） ----

    @Test
    void countStaleNormalizerVersion_shouldDelegateWithCurrentVersion() {
        when(mapper.countStaleOrMissingNormalizerVersion("1")).thenReturn(3L);

        assertEquals(3L, service.countStaleNormalizerVersion());
    }
}
