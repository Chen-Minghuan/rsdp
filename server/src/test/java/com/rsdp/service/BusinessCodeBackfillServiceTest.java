package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.BusinessCodeBackfillReport;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
 * {@link BusinessCodeBackfillService} 单元测试（存量业务编码回填）。
 *
 * <p>2026-10-14 编码规则改造后口径：款式编码不含尺寸段（变体/尺寸解析不再参与回填），
 * 风格未归一不再跳过（发号服务降级 TBD 段），RSKU 编码不含材质段（材质缺失不再跳过）。
 * 覆盖：NULL 才填、已有编码绝不重写、风格中文名归一、各类跳过原因、
 * 单条失败不中断批次、幂等重跑、dryRun 零写库、分批。</p>
 */
@ExtendWith(MockitoExtension.class)
class BusinessCodeBackfillServiceTest {

    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private RskuSupplyMapper rskuSupplyMapper;
    @Mock
    private DictService dictService;
    @Mock
    private RspuCodeService rspuCodeService;
    @Mock
    private RskuCodeService rskuCodeService;

    private BusinessCodeBackfillService service;

    @BeforeEach
    void setUp() {
        service = new BusinessCodeBackfillService(
            rspuMapper, rskuSupplyMapper, dictService, rspuCodeService, rskuCodeService);
    }

    // ---- 测试数据构造 ----

    private static RspuMaster rspu(String rspuId, String categoryCode, String positioningLabel) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(categoryCode);
        rspu.setPositioningLabel(positioningLabel);
        return rspu;
    }

    private static RskuSupply rsku(String rskuId, String rspuId, String factoryCode) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        rsku.setRspuId(rspuId);
        rsku.setFactoryCode(factoryCode);
        return rsku;
    }

    private static CategoryDict dict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }

    /** 字典快照：category=FS；style=MC（中古风）；grade=EX（总裁级）。 */
    private void stubDicts() {
        lenient().when(dictService.listByType("category")).thenReturn(List.of(dict("FS", "座椅")));
        lenient().when(dictService.listByType("style")).thenReturn(List.of(dict("MC", "中古风")));
        lenient().when(dictService.listByType("grade")).thenReturn(List.of(dict("EX", "总裁级")));
    }

    private void stubRspuScan(List<RspuMaster> candidates) {
        when(rspuMapper.selectList(any())).thenReturn(candidates);
    }

    // ---- RSPU：正常发号 ----

    @Test
    void backfill_shouldAssignRspuCode_whenCategoryResolvable() {
        stubDicts();
        stubRspuScan(List.of(rspu("RSPU-1", "FS", "MC")));
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-0001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRspuScanned());
        assertEquals(1, report.getRspuAssigned());
        assertEquals(0, report.getRspuSkipped());
        verify(rspuCodeService).assignCode("RSPU-1", "FS", "MC",
            BusinessCodeBackfillService.DEFAULT_OPERATOR);
        BusinessCodeBackfillReport.Entry entry = report.getEntries().get(0);
        assertEquals(BusinessCodeBackfillReport.Entry.TYPE_RSPU, entry.getRecordType());
        assertEquals(BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED, entry.getOutcome());
        assertEquals("FS-MC-0001", entry.getCode());
    }

    @Test
    void backfill_shouldNormalizeChineseStyleAndGradeLabels() {
        stubDicts();
        stubRspuScan(List.of(
            rspu("RSPU-1", "FS", "中古风"),   // style 字典名归一
            rspu("RSPU-2", "FS", "总裁级"))); // grade 字典名归一
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-0001", "FS-EX-0001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(2, report.getRspuAssigned());
        verify(rspuCodeService).assignCode(eq("RSPU-1"), eq("FS"), eq("MC"), anyString());
        verify(rspuCodeService).assignCode(eq("RSPU-2"), eq("FS"), eq("EX"), anyString());
    }

    @Test
    void backfill_shouldAssignWithRawLabel_whenStyleUnresolved() {
        // 风格未归一不再跳过：传原文给发号服务，由其降级 TBD 段发号
        stubDicts();
        stubRspuScan(List.of(rspu("RSPU-1", "FS", "待识别")));
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-TBD-0001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRspuAssigned());
        assertEquals(0, report.getRspuSkipped());
        verify(rspuCodeService).assignCode(eq("RSPU-1"), eq("FS"), eq("待识别"), anyString());
    }

    // ---- RSPU：跳过口径 ----

    @Test
    void backfill_shouldSkipRspu_whenCategoryMissingOrNotInDict() {
        stubDicts();
        stubRspuScan(List.of(
            rspu("RSPU-1", null, "MC"),
            rspu("RSPU-2", "ZZ", "MC")));

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getRspuAssigned());
        assertEquals(1, report.getRspuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_CATEGORY_MISSING));
        assertEquals(1, report.getRspuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_CATEGORY_NOT_IN_DICT));
        verify(rspuCodeService, never()).assignCode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void backfill_shouldContinueBatch_whenSingleRspuAssignFails() {
        stubDicts();
        stubRspuScan(List.of(
            rspu("RSPU-1", "FS", "MC"),
            rspu("RSPU-2", "FS", "MC")));
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new BusinessException("流水号超限"))
            .thenReturn("FS-MC-0002");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRspuAssigned());
        assertEquals(1, report.getRspuSkipped());
        assertEquals(1, report.getRspuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_ASSIGN_FAILED));
        verify(rspuCodeService, times(2))
            .assignCode(anyString(), anyString(), anyString(), anyString());
    }

    // ---- 扫描口径：NULL 才填（已有编码绝不重写的第一道保证）----

    @Test
    void backfill_shouldScanOnlyNullRspuCodeAndNullRskuCode() {
        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getRspuScanned());
        ArgumentCaptor<QueryWrapper> rspuQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectList(rspuQuery.capture());
        assertTrue(rspuQuery.getValue().getSqlSegment().contains("rspu_code IS NULL"));

        ArgumentCaptor<QueryWrapper> rskuQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rskuSupplyMapper).selectList(rskuQuery.capture());
        assertTrue(rskuQuery.getValue().getSqlSegment().contains("rsku_code IS NULL"));
    }

    @Test
    void backfill_shouldBeIdempotent_whenRerunFindsNoNullCodes() {
        stubDicts();
        // 第一轮：1 条 NULL 候选，补号成功；第二轮：扫描为空（已补号不再命中）
        when(rspuMapper.selectList(any()))
            .thenReturn(List.of(rspu("RSPU-1", "FS", "MC")))
            .thenReturn(List.of());
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-0001");

        BusinessCodeBackfillReport first = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());
        BusinessCodeBackfillReport second = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, first.getRspuAssigned());
        assertEquals(0, second.getRspuScanned());
        assertEquals(0, second.getRspuAssigned());
        assertEquals(0, second.getRskuScanned());
        // 全程只发号一次：重跑不重复发号、不重写已有编码
        verify(rspuCodeService, times(1))
            .assignCode(anyString(), anyString(), anyString(), anyString());
    }

    // ---- RSKU 阶段 ----

    @Test
    void backfill_shouldAssignRskuCode_afterRspuStage() {
        stubRspuScan(List.of());
        when(rskuSupplyMapper.selectList(any()))
            .thenReturn(List.of(rsku("RSKU-1", "RSPU-1", "A004")));
        RspuMaster owner = rspu("RSPU-1", "FS", "MC");
        owner.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(owner));
        when(rskuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-001-M-A004-001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRskuScanned());
        assertEquals(1, report.getRskuAssigned());
        assertEquals(0, report.getRskuSkipped());
        verify(rskuCodeService).assignCode("RSKU-1", "RSPU-1", "A004",
            BusinessCodeBackfillService.DEFAULT_OPERATOR);
        assertEquals("FS-MC-001-M-A004-001", report.getEntries().get(0).getCode());
    }

    @Test
    void backfill_shouldAssignRskuCode_whenMaterialMissing() {
        // 编码不再含材质段：材质缺失不再构成跳过原因
        stubRspuScan(List.of());
        when(rskuSupplyMapper.selectList(any()))
            .thenReturn(List.of(rsku("RSKU-1", "RSPU-1", "A004")));
        RspuMaster owner = rspu("RSPU-1", "FS", "MC");
        owner.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(owner));
        when(rskuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-001-M-A004-001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRskuAssigned());
        assertEquals(0, report.getRskuSkipped());
    }

    @Test
    void backfill_shouldSkipRsku_whenOwnerHasNoCodeOrMissing() {
        stubRspuScan(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", "A004"),        // 所属 RSPU 无码
            rsku("RSKU-2", "RSPU-GONE", "A004")));   // 所属 RSPU 不存在/已软删
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu("RSPU-1", "FS", "MC")));

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getRskuAssigned());
        assertEquals(1, report.getRskuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_RSPU_CODE_MISSING));
        assertEquals(1, report.getRskuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_RSPU_MISSING));
        verify(rskuCodeService, never()).assignCode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void backfill_shouldSkipRsku_whenFactoryMissing() {
        stubRspuScan(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", null)));

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(0, report.getRskuAssigned());
        assertEquals(1, report.getRskuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_FACTORY_MISSING));
        verify(rskuCodeService, never()).assignCode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void backfill_shouldContinueBatch_whenSingleRskuAssignFails() {
        stubRspuScan(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", "A004"),
            rsku("RSKU-2", "RSPU-1", "A004")));
        RspuMaster owner = rspu("RSPU-1", "FS", "MC");
        owner.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(owner));
        when(rskuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new BusinessException("唯一冲突重试仍失败"))
            .thenReturn("FS-MC-001-M-A004-001");

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        assertEquals(1, report.getRskuAssigned());
        assertEquals(1, report.getRskuSkippedByReason()
            .get(BusinessCodeBackfillService.REASON_ASSIGN_FAILED));
        verify(rskuCodeService, times(2))
            .assignCode(anyString(), anyString(), anyString(), anyString());
    }

    // ---- dryRun ----

    @Test
    void backfill_dryRunShouldCountWithoutAnyWrite() {
        stubDicts();
        stubRspuScan(List.of(
            rspu("RSPU-1", "FS", "MC"),      // 会发号
            rspu("RSPU-2", "FS", "待识别"))); // 也会发号（风格段 TBD 降级）
        when(rskuSupplyMapper.selectList(any()))
            .thenReturn(List.of(rsku("RSKU-1", "RSPU-1", "A004")));
        RspuMaster owner = rspu("RSPU-1", "FS", "MC");
        owner.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(owner));

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.dryRun());

        assertTrue(report.isDryRun());
        assertEquals(2, report.getRspuScanned());
        assertEquals(2, report.getRspuAssigned());   // dryRun：两条都会发号
        assertEquals(0, report.getRspuSkipped());
        assertEquals(1, report.getRskuAssigned());
        // 零写库：连发号调用（含流水号计数器占用）都不发生
        verify(rspuCodeService, never()).assignCode(anyString(), anyString(), anyString(), anyString());
        verify(rskuCodeService, never()).assignCode(anyString(), anyString(), anyString(), anyString());
        assertTrue(report.getEntries().stream()
            .filter(e -> e.getOutcome().equals(BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED))
            .allMatch(e -> e.getCode() == null && e.getNote().startsWith("dryRun")));
    }

    // ---- 分批 ----

    @Test
    void backfill_shouldProcessInBatches() {
        stubDicts();
        stubRspuScan(List.of(
            rspu("RSPU-1", "FS", "MC"),
            rspu("RSPU-2", "FS", "MC"),
            rspu("RSPU-3", "FS", "MC")));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-9", "A004")));
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of());

        BusinessCodeBackfillReport report = service.backfill(
            new BusinessCodeBackfillService.BackfillOptions(true, 2, "tester"));

        // RSPU 3 条按 2/1 分两批 + RSKU 1 批
        assertEquals(3, report.getBatchesExecuted());
        assertEquals(3, report.getRspuAssigned());
    }

    @Test
    void backfill_shouldUseConfiguredOperatorForAudit() {
        stubDicts();
        stubRspuScan(List.of(rspu("RSPU-1", "FS", "MC")));
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-0001");

        service.backfill(new BusinessCodeBackfillService.BackfillOptions(false, 200, "ops-admin"));

        verify(rspuCodeService).assignCode("RSPU-1", "FS", "MC", "ops-admin");
    }

    @Test
    void backfill_emptyEntry_shouldHaveNullCodeAndReason() {
        // 报告条目字段口径：SKIPPED 无 code、ASSIGNED 无 reason
        stubDicts();
        stubRspuScan(List.of(rspu("RSPU-1", null, "MC")));

        BusinessCodeBackfillReport report = service.backfill(
            BusinessCodeBackfillService.BackfillOptions.defaults());

        BusinessCodeBackfillReport.Entry entry = report.getEntries().get(0);
        assertEquals(BusinessCodeBackfillReport.Entry.OUTCOME_SKIPPED, entry.getOutcome());
        assertNull(entry.getCode());
        assertEquals(BusinessCodeBackfillService.REASON_CATEGORY_MISSING, entry.getReason());
    }
}
