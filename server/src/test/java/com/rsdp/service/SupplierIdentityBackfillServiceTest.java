package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.SupplierIdentityBackfillReport;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.exception.SourceCodeReusedException;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupplierIdentityBackfillService} 单元测试
 * （设计文档 v3.3 §9 Phase 2 Backfill / §77.1 Supplier Identity）。
 *
 * <p>覆盖：自动回填（新建/复用/跨工厂同码）、四类列入待人工治理清单（报告制，不持久化）、
     * 可重跑、dryRun 不写库、分批。</p>
 */
@ExtendWith(MockitoExtension.class)
class SupplierIdentityBackfillServiceTest {

    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private RskuSupplyMapper rskuSupplyMapper;
    @Mock
    private SupplierProductRefService supplierProductRefService;
    private SupplierIdentityBackfillService service;

    @BeforeEach
    void setUp() {
        service = new SupplierIdentityBackfillService(
            rspuMapper, rskuSupplyMapper, supplierProductRefService, new ObjectMapper());
    }

    private static RspuMaster rspu(String rspuId, String externalCode) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(externalCode);
        rspu.setProductName("样品-" + rspuId);
        return rspu;
    }

    private static RskuSupply supply(String rskuId, String rspuId, String factoryCode) {
        RskuSupply supply = new RskuSupply();
        supply.setRskuId(rskuId);
        supply.setRspuId(rspuId);
        supply.setFactoryCode(factoryCode);
        return supply;
    }

    private static SupplierProductRef ref(String rspuId, String factoryCode, String code) {
        SupplierProductRef ref = new SupplierProductRef();
        ref.setRefId(100L);
        ref.setRspuId(rspuId);
        ref.setFactoryCode(factoryCode);
        ref.setSourceNamespace("default");
        ref.setSourceProductCode(code);
        ref.setNormalizedSourceProductCode(code);
        ref.setCodeNormalizerVersion("1");
        return ref;
    }

    private void stubScan(List<RspuMaster> candidates, List<RskuSupply> supplies) {
        when(rspuMapper.selectList(any())).thenReturn(candidates);
        lenient().when(rskuSupplyMapper.selectList(any())).thenReturn(supplies);
    }

    // ---- 自动回填 ----

    @Test
    void backfill_shouldCreateRefWithDefaultNamespaceAndProvenance() {
        stubScan(List.of(rspu("RSPU-1", " mj - s96 ")), List.of(supply("RSKU-1", "RSPU-1", "FA")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.resolveOrCreate(any())).thenReturn(ref("RSPU-1", "FA", "MJ-S96"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getCreated());
        assertEquals(0, report.getReused());
        assertEquals(0, report.getQueued());
        assertEquals(1, report.getProcessed());

        ArgumentCaptor<SupplierProductRefService.ResolveCommand> captor =
            ArgumentCaptor.forClass(SupplierProductRefService.ResolveCommand.class);
        verify(supplierProductRefService).resolveOrCreate(captor.capture());
        SupplierProductRefService.ResolveCommand command = captor.getValue();
        assertEquals("RSPU-1", command.getRspuId());
        assertEquals("FA", command.getFactoryCode());
        assertEquals("default", command.getSourceNamespace());
        assertEquals("legacy-backfill", command.getSourceSystem());
        // 原文原样传递（归一在 SupplierProductRefService 内部按 CURRENT_VERSION 完成）
        assertEquals(" mj - s96 ", command.getSourceProductCode());
        assertNull(command.getImportBatchId());
    }

    @Test
    void backfill_shouldReuseExistingRefForSameRspu() {
        stubScan(List.of(rspu("RSPU-1", "S001")), List.of(supply("RSKU-1", "RSPU-1", "FA")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(ref("RSPU-1", "FA", "S001")));
        when(supplierProductRefService.resolveOrCreate(any())).thenReturn(ref("RSPU-1", "FA", "S001"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getCreated());
        assertEquals(1, report.getReused());
        assertEquals(0, report.getQueued());
    }

    @Test
    void backfill_shouldAllowSameCodeInDifferentFactories() {
        // §77.1：Factory A / S001 与 Factory B / S001 是不同身份键，允许并存
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S001")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-2", "RSPU-2", "FB")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.resolveOrCreate(any())).thenAnswer(inv -> ref(
            ((SupplierProductRefService.ResolveCommand) inv.getArgument(0)).getRspuId(),
            ((SupplierProductRefService.ResolveCommand) inv.getArgument(0)).getFactoryCode(), "S001"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(2, report.getCreated());
        assertEquals(0, report.getQueued());
        verify(supplierProductRefService, times(2)).resolveOrCreate(any());
    }

    @Test
    void backfill_shouldQueueConflictWhenSameFactoryCodeBoundToSiblingRspu() {
        // 同一编码跨两个 RSPU 且共享工厂 FA：按 rspu_id 序首个自动回填，第二个撞 SOURCE_CODE_REUSED
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S001")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-2", "RSPU-2", "FA")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(ref("RSPU-1", "FA", "S001")));
        when(supplierProductRefService.resolveOrCreate(any())).thenReturn(ref("RSPU-1", "FA", "S001"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getCreated());
        assertEquals(1, report.getQueued());
        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_CONFLICT));

        SupplierIdentityBackfillReport.Entry entry = queuedEntry(report, "RSPU-2");
        assertEquals("FA", entry.getFactoryCode());
        assertTrue(entry.getNote().contains("SOURCE_CODE_REUSED"));
        assertTrue(entry.getNote().contains("RSPU-1"));
    }

    @Test
    void backfill_shouldQueueConflictAndContinueWhenResolveThrowsSourceCodeReused() {
        // resolveOrCreate 抛 SOURCE_CODE_REUSED（预检与写入间的并发兜底）：入队且不中断批次
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S002")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-2", "RSPU-2", "FB")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.resolveOrCreate(any()))
            .thenThrow(new SourceCodeReusedException(ref("RSPU-9", "FA", "S001"), "RSPU-1"))
            .thenReturn(ref("RSPU-2", "FB", "S002"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getQueued());
        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_CONFLICT));
        // 批次不中断：第二个成员照常自动回填
        assertEquals(1, report.getCreated());
        assertEquals(2, report.getProcessed());
    }

    // ---- 四类不自动处理数据入队 ----

    @Test
    void backfill_shouldQueueUnresolvedFactoryWhenNoSupplyRow() {
        stubScan(List.of(rspu("RSPU-1", "S001")), List.of());

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getCreated());
        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_UNRESOLVED_FACTORY));
        verify(supplierProductRefService, never()).resolveOrCreate(any());

        SupplierIdentityBackfillReport.Entry entry = queuedEntry(report, "RSPU-1");
        assertEquals(SupplierIdentityBackfillReport.TYPE_UNRESOLVED_FACTORY, entry.getItemType());
        assertNull(entry.getFactoryCode());
    }

    @Test
    void backfill_shouldQueueMultipleFactoryCandidates() {
        stubScan(List.of(rspu("RSPU-1", "S001")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-2", "RSPU-1", "FB")));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getQueuedByType()
            .get(SupplierIdentityBackfillReport.TYPE_MULTIPLE_FACTORY_CANDIDATES));
        verify(supplierProductRefService, never()).resolveOrCreate(any());

        SupplierIdentityBackfillReport.Entry entry = queuedEntry(report, "RSPU-1");
        assertTrue(entry.getNote().contains("FA"));
        assertTrue(entry.getNote().contains("FB"));
    }

    @Test
    void backfill_shouldQueueUnclearMultiFactoryHistoryForAmbiguousSharedCode() {
        // 同一编码跨 RSPU 且组内存在工厂归属歧义（RSPU-1 有两个候选工厂）→ 全组 UNCLEAR
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S001")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-2", "RSPU-1", "FB"),
                supply("RSKU-3", "RSPU-2", "FC")));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getCreated());
        assertEquals(2, report.getQueuedByType()
            .get(SupplierIdentityBackfillReport.TYPE_UNCLEAR_MULTI_FACTORY_HISTORY));
        verify(supplierProductRefService, never()).resolveOrCreate(any());
    }

    @Test
    void backfill_shouldQueueConflictForWhitespaceOnlyCode() {
        stubScan(List.of(rspu("RSPU-1", "   ")), List.of(supply("RSKU-1", "RSPU-1", "FA")));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_CONFLICT));
        verify(supplierProductRefService, never()).resolveOrCreate(any());
        assertTrue(queuedEntry(report, "RSPU-1").getNote().contains("INVALID_SOURCE_CODE"));
    }

    // ---- 幂等可重跑 ----

    @Test
    void backfill_shouldBeIdempotentAcrossReruns() {
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S002")),
            List.of(supply("RSKU-1", "RSPU-1", "FA")));
        // 第一轮：S001 新建；RSPU-2 无工厂列入待治理。第二轮：S001 命中既有 ref 复用
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(ref("RSPU-1", "FA", "S001")));
        when(supplierProductRefService.resolveOrCreate(any())).thenReturn(ref("RSPU-1", "FA", "S001"));
        SupplierIdentityBackfillReport first = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());
        SupplierIdentityBackfillReport second = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        assertEquals(1, first.getCreated());
        assertEquals(1, first.getQueued());
        // 重跑：不产生重复 ref（复用）；待治理清单为报告制（不持久化），按当轮重新统计
        assertEquals(0, second.getCreated());
        assertEquals(1, second.getReused());
        assertEquals(1, second.getQueued());
    }

    // ---- dryRun 不写库 ----

    @Test
    void dryRun_shouldOnlyCountWithoutAnyWrites() {
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S002"), rspu("RSPU-3", "S003")),
            List.of(supply("RSKU-1", "RSPU-1", "FA"), supply("RSKU-3", "RSPU-3", "FC")));
        // RSPU-1：无既有 ref → 会新建；RSPU-2：无工厂 → 会入队；RSPU-3：同键绑定他人 → 会入 CONFLICT
        when(supplierProductRefService.findByIdentity(eq("FA"), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.findByIdentity(eq("FC"), anyString(), anyString()))
            .thenReturn(Optional.of(ref("RSPU-9", "FC", "S003")));

        SupplierIdentityBackfillReport report = service.backfill(
            new SupplierIdentityBackfillService.BackfillOptions(true, 200, "legacy-backfill"));

        assertTrue(report.isDryRun());
        assertEquals(1, report.getCreated());
        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_UNRESOLVED_FACTORY));
        assertEquals(1, report.getQueuedByType().get(SupplierIdentityBackfillReport.TYPE_CONFLICT));
        assertEquals(3, report.getProcessed());
        assertEquals(3, report.getEntries().size());
        // 不写库：不走 resolveOrCreate
        verify(supplierProductRefService, never()).resolveOrCreate(any());
    }

    // ---- 分批 ----

    @Test
    void backfill_shouldProcessInBatchesAndKeepGroupCorrectnessAcrossBatchBoundary() {
        // 批大小 2：R1(A)/R2(X)/R3(B)/R4(C)/R5(X)；X 组成员 R2/R5 落在不同批，
        // 预分组必须跨批正确合并 → 全组 UNCLEAR（R2 有两个候选工厂）
        List<RspuMaster> candidates = List.of(
            rspu("RSPU-1", "A"), rspu("RSPU-2", "X"), rspu("RSPU-3", "B"),
            rspu("RSPU-4", "C"), rspu("RSPU-5", "X"));
        List<RskuSupply> supplies = List.of(
            supply("RSKU-1", "RSPU-1", "FA"),
            supply("RSKU-2", "RSPU-2", "FA"), supply("RSKU-3", "RSPU-2", "FB"),
            supply("RSKU-4", "RSPU-3", "FB"),
            supply("RSKU-5", "RSPU-4", "FC"),
            supply("RSKU-6", "RSPU-5", "FD"));
        stubScan(candidates, supplies);
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.resolveOrCreate(any())).thenAnswer(inv -> {
            SupplierProductRefService.ResolveCommand command = inv.getArgument(0);
            return ref(command.getRspuId(), command.getFactoryCode(), "X");
        });

        SupplierIdentityBackfillReport report = service.backfill(
            new SupplierIdentityBackfillService.BackfillOptions(false, 2, "legacy-backfill"));

        assertTrue(report.getBatchesExecuted() >= 2);
        // A/B/C 各自自动回填
        assertEquals(3, report.getCreated());
        // X 组跨批仍被正确识别为多厂历史不清，两个成员都入队
        assertEquals(2, report.getQueuedByType()
            .get(SupplierIdentityBackfillReport.TYPE_UNCLEAR_MULTI_FACTORY_HISTORY));
        assertEquals(5, report.getProcessed());
        assertEquals(5, report.getEntries().size());
    }

    @Test
    void backfill_shouldCountEntriesByOutcome() {
        stubScan(
            List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S002")),
            List.of(supply("RSKU-1", "RSPU-1", "FA")));
        when(supplierProductRefService.findByIdentity(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(supplierProductRefService.resolveOrCreate(any())).thenReturn(ref("RSPU-1", "FA", "S001"));

        SupplierIdentityBackfillReport report = service.backfill(
            SupplierIdentityBackfillService.BackfillOptions.defaults());

        List<String> outcomes = new ArrayList<>();
        report.getEntries().forEach(e -> outcomes.add(e.getOutcome()));
        assertTrue(outcomes.contains(SupplierIdentityBackfillReport.Entry.OUTCOME_CREATED));
        assertTrue(outcomes.contains(SupplierIdentityBackfillReport.Entry.OUTCOME_QUEUED));
        assertEquals(2, report.getScanned());
    }

    /** 取报告中指定 RSPU 的 QUEUED 明细条目（断言辅助）。 */
    private static SupplierIdentityBackfillReport.Entry queuedEntry(SupplierIdentityBackfillReport report,
                                                                    String rspuId) {
        return report.getEntries().stream()
            .filter(e -> rspuId.equals(e.getRspuId())
                && SupplierIdentityBackfillReport.Entry.OUTCOME_QUEUED.equals(e.getOutcome()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("报告中不存在该 RSPU 的 QUEUED 明细: " + rspuId));
    }
}
