package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.CategoryMode;
import com.rsdp.dto.request.ExcelAiCategoryRevisionRequest;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiCategoryRevisionResponse;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportSubmitResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportPreviewRevision;
import com.rsdp.entity.ExcelImportProductGroup;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.PreviewRevisionConflictException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.datascope.DataScope;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.storage.StorageService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B2 品类 Preview Revision 链路单元测试
 * （09 方案 v2.3 §16/§17/§20 + 设计文档 v3.3 §4.1/§46/§47）。
 *
 * <p>覆盖：品类编辑命令物化为新 Revision（含首版兼容旧批次）、SINGLE/MIXED 策略校验、
 * §26.1 收敛兜底链固化、确认导入的 Snapshot 接管（乐观锁 409 / 410 / 哈希校验 /
 * 快照值覆盖请求行级选择 / 完整性与唯一性门禁）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExcelAiImportCategoryRevisionTest {

    /** 餐椅 / 餐桌 / 茶几（测试字典码） */
    private static final String CY = "CY";
    private static final String CZ = "CZ";
    private static final String CJ = "CJ";

    @Mock
    private com.rsdp.service.RspuAssociationHelper associationHelper;

    @InjectMocks
    private ExcelAiImportService excelAiImportService;

    @Mock
    private ExcelImportBatchMapper batchMapper;
    @Mock
    private RspuPriceSummaryService rspuPriceSummaryService;
    @Mock
    private VisionService visionService;
    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private RspuStyleMapper rspuStyleMapper;
    @Mock
    private RspuSceneMapper rspuSceneMapper;
    @Mock
    private RspuVariantMapper rspuVariantMapper;
    @Mock
    private RspuVariantService rspuVariantService;
    @Mock
    private RskuService rskuService;
    @Mock
    private ImageAssetsMapper imageAssetsMapper;
    @Mock
    private AsyncTaskMapper asyncTaskMapper;
    @Mock
    private AsyncTaskProcessor asyncTaskProcessor;
    @Mock
    private StorageService storageService;
    @Mock
    private DictService dictService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private VariantCodeMapper variantCodeMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RspuFactoryMappingService rspuFactoryMappingService;
    @Mock
    private FactoryLeadTimeRuleService factoryLeadTimeRuleService;
    @Mock
    private ExcelImportRowService excelImportRowService;
    @Mock
    private DictResolverService dictResolverService;
    @Mock
    private DictAliasService dictAliasService;
    @Mock
    private DictUnresolvedService dictUnresolvedService;
    @Mock
    private RspuCodeService rspuCodeService;
    @Mock
    private DataScopeHelper dataScopeHelper;
    @Mock
    private com.rsdp.mapper.FactoryMasterMapper factoryMasterMapper;
    @Mock
    private com.rsdp.security.datascope.DataScopeContext dataScopeContext;
    @Mock
    private SupplierIdentityResolutionService supplierIdentityResolutionService;
    @Mock
    private SupplierProductRefService supplierProductRefService;
    @Mock
    private ExcelImportPreviewSnapshotService previewSnapshotService;
    @Mock
    private ExcelImportAttemptService attemptService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(excelImportRowService.initRow(anyString(), anyInt(), anyString(), anyMap(), any()))
            .thenAnswer(inv -> System.nanoTime());
        lenient().when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-001-M");
        lenient().when(factoryLeadTimeRuleService.calculateLeadTime(anyString(), any(), any(), anyString(), anyInt()))
            .thenReturn(null);
        lenient().when(batchMapper.claimForImport(anyString())).thenReturn(1);
        // P0-B3：confirm 成功写 confirmed_revision 指针（CAS 成功；模拟写库回写，
        // executeImport 重选批次后 revalidate 可见 confirmed_revision）
        lenient().when(batchMapper.confirmRevision(anyString(), anyLong(), any())).thenAnswer(inv -> {
            ExcelImportBatch selected = batchMapper.selectById(inv.getArgument(0));
            if (selected != null) {
                selected.setConfirmedRevision(inv.getArgument(1));
                selected.setConfirmedBy(inv.getArgument(2));
            }
            return 1;
        });
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(dataScopeHelper.canAccessFactory(anyString())).thenReturn(true);
        lenient().when(dataScopeHelper.currentDataScope()).thenReturn(DataScope.ALL);
        lenient().when(rspuVariantService.createVariantForEntry(anyString(), any()))
            .thenAnswer(inv -> rspuVariantService.createVariant(inv.getArgument(0), inv.getArgument(1)));
        lenient().when(factoryMasterMapper.selectById(anyString()))
            .thenReturn(new com.rsdp.entity.FactoryMaster());
        // 编辑命令物化：Revision 号 = expected == null ? 1 : expected + 1
        lenient().when(previewSnapshotService.createNextRevision(any())).thenAnswer(inv -> {
            ExcelImportPreviewSnapshotService.CreateRevisionCommand command = inv.getArgument(0);
            ExcelImportPreviewRevision revision = new ExcelImportPreviewRevision();
            revision.setBatchId(command.getBatchId());
            revision.setPreviewRevision(command.getExpectedRevision() == null
                ? 1L : command.getExpectedRevision() + 1);
            return revision;
        });
        lenient().when(previewSnapshotService.verifySnapshotHash(anyString(), anyLong())).thenReturn(true);
        // P0-F：Attempt 执行内核桩——confirm 创建 pending Attempt（携带物化 Policy 正文+Hash），
        // executeImport 校验/抢占/心跳/组执行全部放行（Attempt 语义由 ExcelImportAttemptServiceTest 覆盖）
        java.util.Map<String, com.rsdp.entity.ExcelImportAttempt> attempts = new HashMap<>();
        lenient().when(attemptService.createAttemptWithItems(anyString(), anyLong(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                com.rsdp.supplychain.policy.ImportPolicyMaterializer.MaterializedPolicy mp = inv.getArgument(2);
                com.rsdp.entity.ExcelImportAttempt attempt = new com.rsdp.entity.ExcelImportAttempt();
                attempt.setAttemptId("IATT-" + attempts.size());
                attempt.setBatchId(inv.getArgument(0));
                attempt.setPreviewRevision(inv.getArgument(1));
                attempt.setStatus(com.rsdp.entity.ExcelImportAttempt.STATUS_PENDING);
                attempt.setPolicyPayload(mp.policyPayload());
                attempt.setPolicyHash(mp.policyHash());
                attempt.setPolicyHashVersion(mp.policyHashVersion());
                attempt.setPolicySchemaVersion(com.rsdp.supplychain.policy.ImportPolicy.SCHEMA_VERSION);
                attempts.put(attempt.getAttemptId(), attempt);
                return attempt;
            });
        lenient().when(attemptService.getAttempt(anyString()))
            .thenAnswer(inv -> attempts.get(inv.getArgument(0)));
        lenient().when(attemptService.claimForExecution(anyString(), anyString()))
            .thenAnswer(inv -> {
                com.rsdp.entity.ExcelImportAttempt attempt = attempts.get(inv.getArgument(0));
                if (attempt == null) {
                    return Optional.empty();
                }
                attempt.setStatus(com.rsdp.entity.ExcelImportAttempt.STATUS_RUNNING);
                return Optional.of(attempt);
            });
        lenient().when(attemptService.heartbeat(anyString(), anyString())).thenReturn(true);
        lenient().when(attemptService.finalizeAttemptIfComplete(anyString(), anyString()))
            .thenAnswer(inv -> {
                com.rsdp.entity.ExcelImportAttempt attempt = attempts.get(inv.getArgument(0));
                if (attempt == null) {
                    return java.util.Optional.empty();
                }
                // 执行完成：聚合口径由 AttemptItem 终态汇总（测试桩以 {} 回退行循环本地计数）
                attempt.setStatus(com.rsdp.entity.ExcelImportAttempt.STATUS_DONE);
                attempt.setOutcome(com.rsdp.entity.ExcelImportAttempt.OUTCOME_SUCCESS);
                if (attempt.getResultSummary() == null) {
                    attempt.setResultSummary("{}");
                }
                return java.util.Optional.of(attempt);
            });
        lenient().when(attemptService.beginGroup(anyString(), anyString(), anyString())).thenReturn(true);
        lenient().when(attemptService.getItem(anyString(), anyString()))
            .thenAnswer(inv -> {
                com.rsdp.entity.ExcelImportAttemptItem item = new com.rsdp.entity.ExcelImportAttemptItem();
                item.setAttemptId(inv.getArgument(0));
                item.setGroupId(inv.getArgument(1));
                item.setStatus(com.rsdp.entity.ExcelImportAttemptItem.STATUS_RUNNING);
                item.setVariantSuccessCount(0);
                item.setVariantFailedCount(0);
                item.setQuoteSuccessCount(0);
                item.setQuoteFailedCount(0);
                item.setResultSummary("{}");
                return item;
            });
    }

    // ------------------------------------------------------------------
    // 品类编辑命令 → 新 Revision（§46/§47）
    // ------------------------------------------------------------------

    @Test
    void applyCategoryRevision_single_materializesDefaultAndDictHit_createsNextRevision() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-R1", List.of(
            row("型号", "A-001", "名称", "无类别行"),
            row("型号", "B-001", "名称", "有类别行", "类别", CY)
        ));
        batch.setPreviewRevision(1L); // 预览已生成 revision=1
        when(batchMapper.selectById("B-R1")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setExpectedRevision(1L);
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint("cz"); // 小写应归一为大写
        request.setMapping(basicMapping());

        ExcelAiCategoryRevisionResponse response = excelAiImportService.applyCategoryRevision("B-R1", request);

        assertEquals(2L, response.getPreviewRevision());
        assertEquals(2, response.getRows().size());
        // 空值行落默认品类；行内类别列确定性命中优先（§18）
        assertEquals(CZ, response.getRows().get(0).getFinalCategoryCode());
        assertEquals(CY, response.getRows().get(1).getFinalCategoryCode());

        ArgumentCaptor<ExcelImportPreviewSnapshotService.CreateRevisionCommand> captor =
            ArgumentCaptor.forClass(ExcelImportPreviewSnapshotService.CreateRevisionCommand.class);
        verify(previewSnapshotService).createNextRevision(captor.capture());
        ExcelImportPreviewSnapshotService.CreateRevisionCommand command = captor.getValue();
        assertEquals(1L, command.getExpectedRevision());
        assertEquals(CategoryMode.SINGLE, command.getCategoryMode());
        assertEquals(CZ, command.getCategoryHint(), "默认品类应归一为大写");
        assertTrue(command.getCandidateCategoryCodes() == null || command.getCandidateCategoryCodes().isEmpty());
        assertEquals(ExcelAiImportService.EXCEL_NORMALIZER_VERSION, command.getNormalizerVersion());
        assertTrue(command.getMappingHash() != null && command.getMappingHash().matches("[0-9a-f]{64}"));
        // 两组（两个型号）固化进 Snapshot
        assertEquals(2, command.getGroups().size());
        assertTrue(command.getGroups().get(0).getNormalizedPayload().toString().contains("\"sourceRowIndex\":2"));
    }

    @Test
    void applyCategoryRevision_firstRevisionOnLegacyBatch_expectedNull() {
        stubCategoryDicts();
        // B2 前创建的旧批次：无快照指针，expectedRevision=null 创建 revision=1
        ExcelImportBatch batch = batchWithRows("B-R2", List.of(row("型号", "A-001", "名称", "a")));
        when(batchMapper.selectById("B-R2")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setExpectedRevision(null);
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setMapping(basicMapping());

        ExcelAiCategoryRevisionResponse response = excelAiImportService.applyCategoryRevision("B-R2", request);

        assertEquals(1L, response.getPreviewRevision());
        ArgumentCaptor<ExcelImportPreviewSnapshotService.CreateRevisionCommand> captor =
            ArgumentCaptor.forClass(ExcelImportPreviewSnapshotService.CreateRevisionCommand.class);
        verify(previewSnapshotService).createNextRevision(captor.capture());
        assertNull(captor.getValue().getExpectedRevision());
    }

    @Test
    void applyCategoryRevision_mixed_dictHitAdopted_unrecognizedNullified() {
        stubCategoryDicts();
        // 无类别行在前：forward-fill 合并单元格语义下，后续空类别行会继承上行值
        ExcelImportBatch batch = batchWithRows("B-R3", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "C-1", "名称", "c"),
            row("型号", "B-1", "名称", "b", "类别", CY)
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-R3")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setExpectedRevision(2L);
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setMapping(basicMapping());
        // 行 2 人工选择茶几（候选集内）；行 4 走行内确定性命中；行 3 未确定 → null 固化
        request.setRowCategorySelections(Map.of(2, "cj"));

        ExcelAiCategoryRevisionResponse response = excelAiImportService.applyCategoryRevision("B-R3", request);

        assertEquals(3L, response.getPreviewRevision());
        assertEquals(3, response.getRows().size());
        assertEquals(CJ, response.getRows().get(0).getFinalCategoryCode(), "行级选择应归一为大写");
        assertNull(response.getRows().get(1).getFinalCategoryCode(), "MIXED 未确定行以 null 固化");
        assertEquals(CY, response.getRows().get(2).getFinalCategoryCode(), "行内类别列确定性命中直接采用（§9，不受候选集限制）");

        ArgumentCaptor<ExcelImportPreviewSnapshotService.CreateRevisionCommand> captor =
            ArgumentCaptor.forClass(ExcelImportPreviewSnapshotService.CreateRevisionCommand.class);
        verify(previewSnapshotService).createNextRevision(captor.capture());
        assertEquals(CategoryMode.MIXED, captor.getValue().getCategoryMode());
        assertNull(captor.getValue().getCategoryHint(), "MIXED 不允许携带 category_hint（§20.1）");
        assertEquals(List.of(CY, CZ), captor.getValue().getCandidateCategoryCodes());
    }

    @Test
    void applyCategoryRevision_invalidPolicy_rejected() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-R4")).thenReturn(batchWithRows("B-R4", List.of(row("型号", "A-1", "名称", "a"))));

        ExcelAiCategoryRevisionRequest noHint = new ExcelAiCategoryRevisionRequest();
        noHint.setCategoryMode(CategoryMode.SINGLE);
        noHint.setMapping(basicMapping());
        BusinessException e1 = assertThrows(BusinessException.class,
            () -> excelAiImportService.applyCategoryRevision("B-R4", noHint));
        assertTrue(e1.getMessage().contains("请选择默认商品品类"), "异常信息: " + e1.getMessage());

        ExcelAiCategoryRevisionRequest badHint = new ExcelAiCategoryRevisionRequest();
        badHint.setCategoryMode(CategoryMode.SINGLE);
        badHint.setCategoryHint("NOPE");
        badHint.setMapping(basicMapping());
        BusinessException e2 = assertThrows(BusinessException.class,
            () -> excelAiImportService.applyCategoryRevision("B-R4", badHint));
        assertTrue(e2.getMessage().contains("不是合法品类码"), "异常信息: " + e2.getMessage());

        ExcelAiCategoryRevisionRequest tooFew = new ExcelAiCategoryRevisionRequest();
        tooFew.setCategoryMode(CategoryMode.MIXED);
        tooFew.setCandidateCategoryCodes(List.of(CY));
        tooFew.setMapping(basicMapping());
        BusinessException e3 = assertThrows(BusinessException.class,
            () -> excelAiImportService.applyCategoryRevision("B-R4", tooFew));
        assertTrue(e3.getMessage().contains("至少选择两个"), "异常信息: " + e3.getMessage());

        ExcelAiCategoryRevisionRequest badSelection = new ExcelAiCategoryRevisionRequest();
        badSelection.setCategoryMode(CategoryMode.MIXED);
        badSelection.setCandidateCategoryCodes(List.of(CY, CZ));
        badSelection.setMapping(basicMapping());
        badSelection.setRowCategorySelections(Map.of(2, "NOPE"));
        BusinessException e4 = assertThrows(BusinessException.class,
            () -> excelAiImportService.applyCategoryRevision("B-R4", badSelection));
        assertTrue(e4.getMessage().contains("非法品类码") && e4.getMessage().contains("行号 2"),
            "异常信息: " + e4.getMessage());

        // 校验失败不产生新 Revision
        verify(previewSnapshotService, never()).createNextRevision(any());
    }

    @Test
    void applyCategoryRevision_nonPendingBatch_rejected() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-R5", List.of(row("型号", "A-1", "名称", "a")));
        batch.setStatus("importing");
        when(batchMapper.selectById("B-R5")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setMapping(basicMapping());

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.applyCategoryRevision("B-R5", request));
        assertTrue(e.getMessage().contains("不能再编辑"), "异常信息: " + e.getMessage());
        verify(previewSnapshotService, never()).createNextRevision(any());
    }

    @Test
    void applyCategoryRevision_sameGroupVariants_shareMaterializedRows() {
        stubCategoryDicts();
        // r3 型号空 → forward-fill 继承 X-1，与 r2 同一逻辑产品（§14 复用 RSPU 归组规则）
        ExcelImportBatch batch = batchWithRows("B-R6", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("名称", "餐椅-模块行"),
            row("型号", "Y-1", "名称", "茶几")
        ));
        when(batchMapper.selectById("B-R6")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setMapping(basicMapping());

        excelAiImportService.applyCategoryRevision("B-R6", request);

        ArgumentCaptor<ExcelImportPreviewSnapshotService.CreateRevisionCommand> captor =
            ArgumentCaptor.forClass(ExcelImportPreviewSnapshotService.CreateRevisionCommand.class);
        verify(previewSnapshotService).createNextRevision(captor.capture());
        // 两个逻辑产品 → 两个 Product Group；第一组含 2/3 两行
        assertEquals(2, captor.getValue().getGroups().size());
        String firstPayload = captor.getValue().getGroups().get(0).getNormalizedPayload().toString();
        assertTrue(firstPayload.contains("\"sourceRowIndex\":2") && firstPayload.contains("\"sourceRowIndex\":3"),
            "同一逻辑产品的变体行应归并到同一组: " + firstPayload);
    }

    // ------------------------------------------------------------------
    // 确认导入的 Snapshot 接管（§4.1 规则 3/4 + §47 乐观锁）
    // ------------------------------------------------------------------

    @Test
    void confirm_withExpectedRevision_conflict409_beforeClaim() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C1", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(3L); // 当前指针已推进到 3
        when(batchMapper.selectById("B-C1")).thenReturn(batch);

        ExcelAiMappingRequest request = baseConfirmRequest("B-C1", CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setExpectedRevision(2L); // 旧页面携带过期 Revision

        PreviewRevisionConflictException e = assertThrows(PreviewRevisionConflictException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("preview_revision_conflict"), "异常信息: " + e.getMessage());
        assertEquals(409, e.getCode());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_snapshotMissing_410() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C2", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C2")).thenReturn(batch);
        when(previewSnapshotService.getSnapshot("B-C2", 2L)).thenReturn(Optional.empty());

        ExcelAiMappingRequest request = baseConfirmRequest("B-C2", CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertEquals(410, e.getCode());
        assertTrue(e.getMessage().contains("snapshot_expired"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_hashMismatch_rejected() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C3", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C3")).thenReturn(batch);
        stubSnapshot("B-C3", 2L, "SINGLE", CY, null, List.of(groupPayload("G0", "A-1", 2, CY)));
        when(previewSnapshotService.verifySnapshotHash("B-C3", 2L)).thenReturn(false);

        ExcelAiMappingRequest request = baseConfirmRequest("B-C3", CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("完整性校验失败"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_policyMismatch_rejected409() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C4", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C4")).thenReturn(batch);
        stubSnapshot("B-C4", 2L, "SINGLE", CY, null, List.of(groupPayload("G0", "A-1", 2, CY)));

        ExcelAiMappingRequest request = baseConfirmRequest("B-C4", CategoryMode.SINGLE);
        request.setCategoryHint(CZ); // 与快照固化的默认品类不一致
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("品类策略与当前 Snapshot 不一致"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_undeterminedRows_rejectedWithRowList() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C5", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C5")).thenReturn(batch);
        // 行 3 在快照中 finalCategoryCode=null（MIXED 未识别）
        stubSnapshot("B-C5", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY),
            groupPayload("G1", "B-1", 3, null)
        ));

        ExcelAiMappingRequest request = baseConfirmRequest("B-C5", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("未确定") && e.getMessage().contains("3"),
            "应返回未确定行号清单: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_skipRowsExcludedFromPrecheck() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-C6", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C6")).thenReturn(batch);
        stubSnapshot("B-C6", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)
        ));
        when(previewSnapshotService.loadSourceRows(any())).thenReturn(List.of(
            Map.of("型号", "A-1", "名称", "a", "__rowIndex__", "2")));

        ExcelAiMappingRequest request = baseConfirmRequest("B-C6", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        request.setSkipRows(List.of(3)); // 跳过行已在 Revision 中剔除，请求本身不再改变执行输入

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(captor.capture());
        assertEquals(CY, captor.getValue().getCategoryCode());
    }

    @Test
    void confirm_requestSkipRowsCannotBypassUnresolvedRowInSnapshot() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C6-BYPASS", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C6-BYPASS")).thenReturn(batch);
        stubSnapshot("B-C6-BYPASS", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, null)));
        ExcelAiMappingRequest request = baseConfirmRequest("B-C6-BYPASS", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        request.setSkipRows(List.of(2));

        BusinessException error = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(error.getMessage().contains("未确定"));
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_oldRevisionWithoutSealedRows_requiresNewPreviewRevision() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-OLD-REV", List.of(row("型号", "A-1")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-OLD-REV")).thenReturn(batch);
        ExcelImportProductGroup group = groupPayload("G0", "A-1", 2, CY);
        group.setNormalizedPayload(group.getNormalizedPayload().replace(
            "\"sourceRowsRef\":\"SENS-TEST\",", ""));
        stubSnapshot("B-OLD-REV", 2L, "SINGLE", CY, null, List.of(group));
        ExcelAiMappingRequest request = baseConfirmRequest("B-OLD-REV", CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setExpectedRevision(2L);

        BusinessException error = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(error.getMessage().contains("未固化源行"));
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_sameSupplierModelWithDifferentProductNames_rejectedBeforeImport() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-MODEL-CONFLICT", List.of(
            row("型号", "MJ-S101", "名称", "索伦托沙发"),
            row("型号", "MJ-S101", "名称", "山野沙发")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-MODEL-CONFLICT")).thenReturn(batch);
        ExcelImportProductGroup first = groupPayload("G0", "MJ-S101", 52, CZ);
        first.setNormalizedPayload(first.getNormalizedPayload().replace(
            "\"externalCode\":\"MJ-S101\"", "\"externalCode\":\"MJ-S101\",\"productName\":\"索伦托沙发\""));
        ExcelImportProductGroup second = groupPayload("G1", "MJ-S101", 127, CZ);
        second.setNormalizedPayload(second.getNormalizedPayload().replace(
            "\"externalCode\":\"MJ-S101\"", "\"externalCode\":\"MJ-S101\",\"productName\":\"山野沙发\""));
        stubSnapshot("B-MODEL-CONFLICT", 2L, "SINGLE", CZ, null, List.of(first, second));
        ExcelAiMappingRequest request = baseConfirmRequest("B-MODEL-CONFLICT", CategoryMode.SINGLE);
        request.setCategoryHint(CZ);
        request.setExpectedRevision(2L);

        BusinessException error = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(error.getMessage().contains("MJ-S101")
            && error.getMessage().contains("52") && error.getMessage().contains("127"));
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_groupConflict_rejectedWithRowList() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-C7", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("名称", "餐椅-模块行")
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C7")).thenReturn(batch);
        // 同一逻辑产品两行固化了不同品类（如早前编辑遗留）→ 唯一性门禁拦截
        stubSnapshot("B-C7", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayloadMulti("G0", "X-1", new int[]{2, 3}, new String[]{CY, CZ})
        ));

        ExcelAiMappingRequest request = baseConfirmRequest("B-C7", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("同一逻辑产品"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void confirm_withExpectedRevision_snapshotOverridesRequestSelections() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-C8", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-C8")).thenReturn(batch);
        stubSnapshot("B-C8", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY),
            groupPayload("G1", "B-1", 3, CZ)
        ));
        when(previewSnapshotService.loadSourceRows(any())).thenReturn(List.of(
            Map.of("型号", "A-1", "名称", "a", "__rowIndex__", "2"),
            Map.of("型号", "B-1", "名称", "b", "__rowIndex__", "3")));
        batch.setPreviewRows("[]"); // 执行必须读取已确认快照，不能回退批次原始行

        ExcelAiMappingRequest request = baseConfirmRequest("B-C8", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        // 请求携带的行级选择与快照不一致：后端只认 Snapshot 固化的 finalCategoryCode
        request.setRowCategorySelections(Map.of(2, CZ, 3, CY));

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(2)).insert(captor.capture());
        assertEquals(CY, captor.getAllValues().get(0).getCategoryCode(), "行 2 应以快照值 CY 为准");
        assertEquals(CZ, captor.getAllValues().get(1).getCategoryCode(), "行 3 应以快照值 CZ 为准");
        // 行级最终品类写入行记录 mapped_fields 快照（§17）
        ArgumentCaptor<Map<String, String>> mappedFieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(excelImportRowService, times(2)).initRow(anyString(), anyInt(), anyString(), anyMap(), any(),
            mappedFieldsCaptor.capture(), anyList());
        assertEquals(CY, mappedFieldsCaptor.getAllValues().get(0).get("categoryCode"));
        assertEquals(CZ, mappedFieldsCaptor.getAllValues().get(1).get("categoryCode"));
    }

    // ------------------------------------------------------------------
    // P0-B3 Confirm 收口 / Revalidate / Issue 物化 / variantKey 持久化
    // （设计文档 v3.3 §24.1/§27/§45/§72）
    // ------------------------------------------------------------------

    @Test
    void confirm_withExpectedRevision_writesConfirmedRevisionPointer() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-P1", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-P1")).thenReturn(batch);
        stubSnapshot("B-P1", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)));

        ExcelAiMappingRequest request = baseConfirmRequest("B-P1", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        excelAiImportService.confirmAndImport(request);

        // confirm 成功写 confirmed_revision 指针（§45）；Import 前品类门禁直接校验快照负载（§72）
        verify(batchMapper).confirmRevision(eq("B-P1"), eq(2L), any());
    }

    @Test
    void confirm_confirmRevisionCasMismatch_409() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-P2", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-P2")).thenReturn(batch);
        stubSnapshot("B-P2", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)));
        // claim 间隙 Revision 被并发编辑推进 → confirmed_revision CAS 失败
        // （doReturn 形式：setUp 的 lenient answer 含嵌套 mock 调用，when() 形式重桩会误触发）
        org.mockito.Mockito.doReturn(0).when(batchMapper)
            .confirmRevision(anyString(), anyLong(), any());

        ExcelAiMappingRequest request = baseConfirmRequest("B-P2", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        PreviewRevisionConflictException e = assertThrows(PreviewRevisionConflictException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertEquals(409, e.getCode());
    }

    @Test
    void confirm_archivedSnapshot_410() {
        stubCategoryDicts();
        ExcelImportBatch batch = batchWithRows("B-P3", List.of(row("型号", "A-1", "名称", "a")));
        // 受限 GC 已归档：指针清空 + 归档摘要存在（§44.2）
        batch.setPreviewRevision(null);
        batch.setSnapshotArchiveSummary(
            "{\"archivedAt\":\"2026-01-01T00:00:00\",\"archiveReason\":\"retention_expired\"}");
        when(batchMapper.selectById("B-P3")).thenReturn(batch);

        ExcelAiMappingRequest request = baseConfirmRequest("B-P3", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertEquals(410, e.getCode());
        assertTrue(e.getMessage().contains("snapshot_expired"), e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void executeImport_groupRerun_recoversCommittedRowAndContinuesModuleRow(boolean recoveredLastRow) {
        // §50.2 重跑恢复（评审回归）：同产品两行组——行 2 上轮已提交（recoveredRows），
        // 行 3 模块行上轮技术失败。重跑时：行 2 不重做领域写入（不重复建产品/变体），
        // 仅恢复组上下文；行 3 经 sameProduct 挂靠回该产品补齐变体——
        // 若恢复缺失，行 3 会命中「已存在跳过」（updateIfExists=false），变体永远缺失
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-REC", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "A-1", "名称", "a-模块")
        ));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-REC")).thenReturn(batch);
        stubSnapshot("B-REC", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayloadMulti("G0", "A-1", recoveredLastRow ? new int[]{2, 3, 4} : new int[]{2, 3},
                recoveredLastRow ? new String[]{CY, CY, CY} : new String[]{CY, CY})));
        List<Map<String, String>> sourceRows = new java.util.ArrayList<>(List.of(
            Map.of("型号", "A-1", "名称", "a", "__rowIndex__", "2"),
            Map.of("型号", "A-1", "名称", "a-模块", "__rowIndex__", "3")));
        if (recoveredLastRow) {
            sourceRows.add(Map.of("型号", "A-1", "名称", "a-末行", "__rowIndex__", "4"));
        }
        when(previewSnapshotService.loadSourceRows(any())).thenReturn(sourceRows);

        // 上轮已提交行 2（本 Attempt 创建 RSPU-REC）：恢复清单进 Item 摘要；
        // 身份解析命中它——若行 3 未被恢复机制挂靠，会走「已存在跳过」分支
        RspuMaster committedRspu = new RspuMaster();
        committedRspu.setRspuId("RSPU-REC");
        committedRspu.setProductName("a");
        committedRspu.setStatus("active");
        SupplierIdentityResolutionService.ResolvedIdentity identity =
            new SupplierIdentityResolutionService.ResolvedIdentity("RSPU-REC",
                SupplierIdentityResolutionService.ResolutionSource.LEGACY_EXTERNAL_CODE, null);
        lenient().when(supplierIdentityResolutionService.resolveBatch(any(), any(), anyCollection()))
            .thenReturn(Map.of("A-1", identity));
        lenient().when(rspuMapper.selectBatchIds(anyCollection())).thenReturn(List.of(committedRspu));
        lenient().when(supplierIdentityResolutionService.resolve(any(), any(), eq("A-1")))
            .thenReturn(Optional.of(identity));
        lenient().when(rspuMapper.selectById("RSPU-REC")).thenReturn(committedRspu);
        com.rsdp.entity.ExcelImportAttemptItem recoveredItem = new com.rsdp.entity.ExcelImportAttemptItem();
        recoveredItem.setStatus(com.rsdp.entity.ExcelImportAttemptItem.STATUS_RUNNING);
        recoveredItem.setVariantSuccessCount(1);
        recoveredItem.setQuoteSuccessCount(0);
        recoveredItem.setQuoteFailedCount(0);
        recoveredItem.setResultSummary("{\"rowsTotal\":1,\"rowsSucceeded\":1,"
            + "\"recoveredRows\":[{\"row\":2,\"rspuId\":\"RSPU-REC\",\"groupKey\":\"A-1\"}]}");
        if (recoveredLastRow) {
            recoveredItem.setResultSummary(recoveredItem.getResultSummary().replace("}]}",
                "},{\"row\":4,\"rspuId\":\"RSPU-REC\",\"groupKey\":\"A-1\"}]}"));
        }
        when(attemptService.getItem(anyString(), eq("PGROUP-TEST-G0"))).thenReturn(recoveredItem);

        ExcelAiMappingRequest request = baseConfirmRequest("B-REC", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);

        ExcelAiImportResult result = confirmAndImport(request);

        // 行 2：恢复跳过——不重新建产品（否则会产生重复 RSPU）
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
        verify(excelImportRowService, org.mockito.Mockito.times(recoveredLastRow ? 2 : 1)).markSkipped(any(), argThat(msg -> msg.contains("上轮已提交")));
        // 行 3（模块行）：挂靠恢复的产品补齐变体——若 currentGroup 未恢复，
        // 该行会命中「已存在跳过」，createVariant 永远不会被调用
        verify(rspuVariantService).createVariant(eq("RSPU-REC"), any());
        verify(attemptService).completeGroup(anyString(), eq("PGROUP-TEST-G0"), anyString());
        assertEquals(0, result.getFailedCount(), "导入失败明细: " + result.getFailures());
    }

    @Test
    void executeImport_confirmedPointerClearedAfterConfirm_409() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-P4", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-P4")).thenReturn(batch);
        stubSnapshot("B-P4", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)));

        ExcelAiMappingRequest request = baseConfirmRequest("B-P4", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        ExcelAiImportSubmitResult submit = submitImport(request);

        // confirm 后 Snapshot 被新编辑取代：confirmed_revision 已被清空（advancePreviewRevision CAS 语义）
        batch.setConfirmedRevision(null);
        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.executeImport(submit.getTaskId(), submit.getBatchId()));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("confirmed_revision"), e.getMessage());
    }

    @Test
    void executeImport_confirmedPointerMismatch_409() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-P5", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-P5")).thenReturn(batch);
        stubSnapshot("B-P5", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)));

        ExcelAiMappingRequest request = baseConfirmRequest("B-P5", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        ExcelAiImportSubmitResult submit = submitImport(request);

        // confirmed_revision 与请求 expectedRevision 不一致 → 409
        batch.setConfirmedRevision(3L);
        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.executeImport(submit.getTaskId(), submit.getBatchId()));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("不一致"), e.getMessage());
    }

    @Test
    void executeImport_archivedAfterConfirm_410() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-P6", List.of(row("型号", "A-1", "名称", "a")));
        batch.setPreviewRevision(2L);
        when(batchMapper.selectById("B-P6")).thenReturn(batch);
        stubSnapshot("B-P6", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayload("G0", "A-1", 2, CY)));

        ExcelAiMappingRequest request = baseConfirmRequest("B-P6", CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(2L);
        ExcelAiImportSubmitResult submit = submitImport(request);

        // confirm 之后受限 GC 归档：指针清空 + 摘要存在 → 410 snapshot_expired
        batch.setConfirmedRevision(null);
        batch.setPreviewRevision(null);
        batch.setSnapshotArchiveSummary(
            "{\"archivedAt\":\"2026-01-01T00:00:00\",\"archiveReason\":\"retention_expired\"}");
        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.executeImport(submit.getTaskId(), submit.getBatchId()));
        assertEquals(410, e.getCode());
        assertTrue(e.getMessage().contains("snapshot_expired"), e.getMessage());
    }

    @Test
    void applyCategoryRevision_unresolvedRows_createdButBlockedAtConfirm() {
        stubCategoryDicts();
        // 未确定行在前（避免类别列 forward-fill 继承上行值）
        ExcelImportBatch batch = batchWithRows("B-I1", List.of(
            row("型号", "A-1", "名称", "a"), // 无类别、未行级选择 → 未确定
            row("型号", "B-1", "名称", "b", "类别", CY)
        ));
        batch.setPreviewRevision(1L);
        when(batchMapper.selectById("B-I1")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(1L);
        request.setMapping(basicMapping());

        // Revision 创建保持宽松（允许保存部分选择），未确定行 finalCategoryCode 为 null
        ExcelAiCategoryRevisionResponse response = excelAiImportService.applyCategoryRevision("B-I1", request);
        assertNull(response.getRows().stream()
            .filter(r -> r.getRowIndex() == 2).findFirst().orElseThrow().getFinalCategoryCode());

        // confirm 时品类门禁直接校验快照负载：存在未确定行 → 拒绝导入（§20/§72）
        stubCreatePath();
        batch.setPreviewRevision(2L);
        stubSnapshot("B-I1", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayloadMulti("G0", "A-1", new int[]{2}, new String[]{null}),
            groupPayload("G1", "B-1", 3, CY)));
        ExcelAiMappingRequest confirm = baseConfirmRequest("B-I1", CategoryMode.MIXED);
        confirm.setCandidateCategoryCodes(List.of(CY, CZ));
        confirm.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(confirm));
        assertTrue(e.getMessage().contains("商品品类未确定"), e.getMessage());
    }

    @Test
    void applyCategoryRevision_groupConflict_createdButBlockedAtConfirm() {
        stubCategoryDicts();
        // 同一逻辑产品两行（型号 forward-fill 同组）固化了不同品类
        ExcelImportBatch batch = batchWithRows("B-I2", List.of(
            row("型号", "X-1", "名称", "餐椅", "类别", CY),
            row("名称", "餐椅-模块", "类别", CZ)
        ));
        batch.setPreviewRevision(1L);
        when(batchMapper.selectById("B-I2")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setExpectedRevision(1L);
        request.setMapping(basicMapping());

        // Revision 创建保持宽松，两个品类的最终值都固化进负载
        ExcelAiCategoryRevisionResponse response = excelAiImportService.applyCategoryRevision("B-I2", request);
        assertEquals(2, response.getRows().stream().filter(r -> r.getFinalCategoryCode() != null).count());

        // confirm 时品类门禁拒绝：同一逻辑产品多个品类
        stubCreatePath();
        batch.setPreviewRevision(2L);
        stubSnapshot("B-I2", 2L, "MIXED", null, List.of(CY, CZ), List.of(
            groupPayloadMulti("G0", "X-1", new int[]{2, 3}, new String[]{CY, CZ})));
        ExcelAiMappingRequest confirm = baseConfirmRequest("B-I2", CategoryMode.MIXED);
        confirm.setCandidateCategoryCodes(List.of(CY, CZ));
        confirm.setExpectedRevision(2L);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(confirm));
        assertTrue(e.getMessage().contains("多个品类"), e.getMessage());
    }

    @Test
    void applyCategoryRevision_persistsVariantKeysInPayload() {
        stubCategoryDicts();
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("型号", "externalCode");
        mapping.put("名称", "productName");
        mapping.put("尺寸", "dimensions");
        mapping.put("颜色", "colorPrimaryName");
        // 同组两行（型号 forward-fill）身份相同 → 合并为一个 variant
        ExcelImportBatch batch = batchWithRows("B-V1", List.of(
            row("型号", "A-1", "名称", "a", "尺寸", "100x50", "颜色", "红"),
            row("名称", "a-模块", "尺寸", "100x50", "颜色", "红")
        ));
        batch.setPreviewRevision(1L);
        when(batchMapper.selectById("B-V1")).thenReturn(batch);

        ExcelAiCategoryRevisionRequest request = new ExcelAiCategoryRevisionRequest();
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint(CY);
        request.setExpectedRevision(1L);
        request.setMapping(mapping);

        excelAiImportService.applyCategoryRevision("B-V1", request);

        ArgumentCaptor<ExcelImportPreviewSnapshotService.CreateRevisionCommand> captor =
            ArgumentCaptor.forClass(ExcelImportPreviewSnapshotService.CreateRevisionCommand.class);
        verify(previewSnapshotService).createNextRevision(captor.capture());
        JsonNode payload = captor.getValue().getGroups().get(0).getNormalizedPayload();
        JsonNode variants = payload.get("variants");
        assertEquals(1, variants.size(), payload.toString());
        String variantKey = variants.get(0).get("variantKey").asText();
        assertTrue(variantKey.startsWith("VAR:"), variantKey);
        assertEquals("100x50", variants.get(0).get("identity").get("size").asText());
        assertEquals("红", variants.get(0).get("identity").get("color").asText());
        assertEquals(2, variants.get(0).get("sourceRowIndexes").size());
        // 行节点携带同一 variantKey（§24.1：Issue Target 的稳定引用）
        assertEquals(variantKey, payload.get("rows").get(0).get("variantKey").asText());
        assertEquals(variantKey, payload.get("rows").get(1).get("variantKey").asText());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * confirm 已异步化（confirmAndImport 只做校验/抢占/建任务/投递，立即返回受理状态）；
     * 本方法串联 confirmAndImport（受理）→ executeImport（异步执行本体），
     * 与 ExcelAiImportCategoryModeTest 同名辅助方法口径一致。
     */
    private ExcelAiImportResult confirmAndImport(ExcelAiMappingRequest request) {
        ExcelAiImportSubmitResult submit = submitImport(request);
        return excelAiImportService.executeImport(submit.getTaskId(), submit.getBatchId());
    }

    /**
     * 只走受理段（confirmAndImport + 任务桩），返回受理结果；
     * 需要在中途扰动批次状态再驱动 executeImport 的用例（P0-B3 Revalidate）使用。
     */
    private ExcelAiImportSubmitResult submitImport(ExcelAiMappingRequest request) {
        ExcelAiImportSubmitResult submit = excelAiImportService.confirmAndImport(request);
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, atLeastOnce()).insert(taskCaptor.capture());
        AsyncTask importTask = taskCaptor.getAllValues().stream()
            .filter(t -> submit.getTaskId().equals(t.getTaskId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到批次导入任务: " + submit.getTaskId()));
        when(asyncTaskMapper.selectById(submit.getTaskId())).thenReturn(importTask);
        return submit;
    }

    private ExcelAiMappingRequest baseConfirmRequest(String batchId, CategoryMode mode) {
        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(batchId);
        request.setMapping(basicMapping());
        request.setCategoryMode(mode);
        return request;
    }

    /** 构造 Snapshot 视图桩：Header（策略字段）+ 指定负载的 Product Group 列表。 */
    private void stubSnapshot(String batchId, long revision, String mode, String hint,
                              List<String> candidates, List<ExcelImportProductGroup> groups) {
        ExcelImportPreviewRevision header = new ExcelImportPreviewRevision();
        header.setBatchId(batchId);
        header.setPreviewRevision(revision);
        header.setCategoryMode(mode);
        header.setCategoryHint(hint);
        try {
            header.setCandidateCategoryCodes(new ObjectMapper()
                .writeValueAsString(candidates != null ? candidates : List.of()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(previewSnapshotService.getSnapshot(batchId, revision))
            .thenReturn(Optional.of(new ExcelImportPreviewSnapshotService.SnapshotView(header, groups)));
    }

    /** 单组单行负载。 */
    private ExcelImportProductGroup groupPayload(String groupKey, String externalCode,
                                                 int rowIndex, String finalCode) {
        return groupPayloadMulti(groupKey, externalCode, new int[]{rowIndex}, new String[]{finalCode});
    }

    /** 单组多行负载（finalCode 为 null 表示该行未确定品类）。 */
    private ExcelImportProductGroup groupPayloadMulti(String groupKey, String externalCode,
                                                      int[] rowIndexes, String[] finalCodes) {
        StringBuilder rowsJson = new StringBuilder();
        for (int i = 0; i < rowIndexes.length; i++) {
            if (i > 0) {
                rowsJson.append(',');
            }
            rowsJson.append("{\"sourceRowIndex\":").append(rowIndexes[i])
                .append(",\"finalCategoryCode\":")
                .append(finalCodes[i] == null ? "null" : "\"" + finalCodes[i] + "\"")
                .append('}');
        }
        ExcelImportProductGroup group = new ExcelImportProductGroup();
        group.setGroupId("PGROUP-TEST-" + groupKey);
        group.setProductGroupKey(groupKey);
        group.setNormalizedPayload("{\"externalCode\":\"" + externalCode
            + "\",\"sourceRowsRef\":\"SENS-TEST\",\"rows\":[" + rowsJson + "]}");
        return group;
    }

    private void stubCategoryDicts() {
        lenient().when(dictService.listByType("category")).thenReturn(List.of(
            createDict(CY, "餐椅"),
            createDict(CZ, "餐桌"),
            createDict(CJ, "茶几")
        ));
    }

    /** 新建路径公共桩：RSPU insert 回填 ID + 变体创建。 */
    private void stubCreatePath() {
        lenient().when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-" + System.nanoTime());
            return 1;
        });
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        lenient().when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);
    }

    /** 基础字段映射：型号 → externalCode，名称 → productName，类别 → categoryCode。 */
    private Map<String, String> basicMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("型号", "externalCode");
        mapping.put("名称", "productName");
        mapping.put("类别", "categoryCode");
        return mapping;
    }

    /** 构造数据行（表头 → 值），保持键序。 */
    private Map<String, String> row(String... keyValues) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /** 构造导入批次：previewRows 为行 JSON（自动补 __rowIndex__ = 序号 + 2，与预览口径一致）。 */
    private ExcelImportBatch batchWithRows(String batchId, List<Map<String, String>> rows) {
        List<Map<String, String>> indexed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = new LinkedHashMap<>(rows.get(i));
            row.put("__rowIndex__", String.valueOf(i + 2));
            indexed.add(row);
        }
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId(batchId);
        batch.setStatus("pending");
        batch.setTotalRows(rows.size());
        try {
            batch.setPreviewRows(new ObjectMapper().writeValueAsString(indexed));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return batch;
    }

    private CategoryDict createDict(String dictCode, String dictName) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("category");
        dict.setDictCode(dictCode);
        dict.setDictName(dictName);
        return dict;
    }
}
