package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.CategoryMode;
import com.rsdp.dto.request.ExcelAiClassifyCategoriesRequest;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiClassifyCategoriesResponse;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportSubmitResult;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Excel 单一/混合品类导入改造（方案 v2.2，docs/02-architecture/09）单元测试。
 *
 * <p>覆盖：SINGLE 默认品类语义（§18）、MIXED 候选集约束的 AI 逐行分类（§7/§15）、
 * 逻辑产品分组复用 RSPU 归组规则（§14）、导入前置品类校验（§20/§22）、
 * 更新模式跨品类保护（§21.1）、旧路径兼容（§26.1）。</p>
 *
 * <p>测试数据直接构造批次 previewRows（JSON）+ 显式字段映射，不经 previewMapping；
 * 行号与预览口径一致（__rowIndex__ = 序号 + 2，第 1 行为表头）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExcelAiImportCategoryModeTest {

    /** 餐椅 / 餐桌 / 茶几 / 休闲椅（测试字典码） */
    private static final String CY = "CY";
    private static final String CZ = "CZ";
    private static final String CJ = "CJ";
    private static final String XY = "XY";

    @InjectMocks
    private ExcelAiImportService excelAiImportService;

    @Mock
    private ExcelImportBatchMapper batchMapper;
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
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(dataScopeHelper.canAccessFactory(anyString())).thenReturn(true);
        lenient().when(dataScopeHelper.currentDataScope()).thenReturn(DataScope.ALL);
        lenient().when(rspuVariantService.createVariantForEntry(anyString(), any()))
            .thenAnswer(inv -> rspuVariantService.createVariant(inv.getArgument(0), inv.getArgument(1)));
        lenient().when(factoryMasterMapper.selectById(anyString()))
            .thenReturn(new com.rsdp.entity.FactoryMaster());
    }

    /**
     * confirm 已异步化（confirmAndImport 只做校验/抢占/建任务/投递，立即返回受理状态）；
     * 本方法串联 confirmAndImport（受理）→ executeImport（异步执行本体，含 input_data 序列化/还原），
     * 返回导入结果，保持用例断言语义不变（与 ExcelAiImportServiceTest 同名辅助方法一致）。
     */
    private ExcelAiImportResult confirmAndImport(ExcelAiMappingRequest request) {
        ExcelAiImportSubmitResult submit = excelAiImportService.confirmAndImport(request);
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, atLeastOnce()).insert(taskCaptor.capture());
        AsyncTask importTask = taskCaptor.getAllValues().stream()
            .filter(t -> submit.getTaskId().equals(t.getTaskId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到批次导入任务: " + submit.getTaskId()));
        when(asyncTaskMapper.selectById(submit.getTaskId())).thenReturn(importTask);
        return excelAiImportService.executeImport(submit.getTaskId(), submit.getBatchId());
    }

    // ------------------------------------------------------------------
    // SINGLE 行级预分类（§4.1/§18：行内类别列归一命中优先，默认品类只补空值行，不调 AI）
    // ------------------------------------------------------------------

    @Test
    void classify_single_rowCategoryHitBeatsHint_emptyRowFallsBackToHint_noAi() {
        stubCategoryDicts();
        // 空类别行在前（forward-fill 合并单元格语义下，后续空行会继承上行类别值，
        // 只有列内尚无任何值时的空行才真正落到默认品类）
        when(batchMapper.selectById("B-S1")).thenReturn(batchWithRows("B-S1", List.of(
            row("型号", "A-001", "名称", "无类别行"),
            row("型号", "B-001", "名称", "有类别行", "类别", CY)
        )));

        ExcelAiClassifyCategoriesRequest request = new ExcelAiClassifyCategoriesRequest();
        request.setMode(CategoryMode.SINGLE);
        request.setCategoryHint(CZ);
        request.setMapping(basicMapping());

        ExcelAiClassifyCategoriesResponse response = excelAiImportService.classifyRowCategories("B-S1", request);

        assertEquals(2, response.getSuggestions().size());
        // 空值行落默认品类（categoryHint），来源 default
        assertSuggestion(response.getSuggestions().get(0), 2, false, CZ, "default");
        // 行内类别列归一命中优先于 categoryHint（§18）
        assertSuggestion(response.getSuggestions().get(1), 3, false, CY, "dict");
        // SINGLE 不进行品类 AI 识别（§4.1）
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void classify_single_dictNameHit_normalized_noAi() {
        stubCategoryDicts();
        when(dictResolverService.resolveCodeByName("category", "餐椅")).thenReturn(CY);
        when(batchMapper.selectById("B-S2")).thenReturn(batchWithRows("B-S2", List.of(
            row("型号", "A-001", "名称", "椅子", "类别", "餐椅")
        )));

        ExcelAiClassifyCategoriesRequest request = new ExcelAiClassifyCategoriesRequest();
        request.setMode(CategoryMode.SINGLE);
        request.setCategoryHint(CZ);
        request.setMapping(basicMapping());

        ExcelAiClassifyCategoriesResponse response = excelAiImportService.classifyRowCategories("B-S2", request);

        assertEquals(1, response.getSuggestions().size());
        assertSuggestion(response.getSuggestions().get(0), 2, false, CY, "dict");
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void classify_single_unresolvableRowValue_fallsBackToHint_noAi() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-S3")).thenReturn(batchWithRows("B-S3", List.of(
            row("型号", "A-001", "名称", "椅子", "类别", "无法归一的玩意")
        )));

        ExcelAiClassifyCategoriesRequest request = new ExcelAiClassifyCategoriesRequest();
        request.setMode(CategoryMode.SINGLE);
        request.setCategoryHint(CZ);
        request.setMapping(basicMapping());

        ExcelAiClassifyCategoriesResponse response = excelAiImportService.classifyRowCategories("B-S3", request);

        // 行内值无法归一为合法字典码 → 不算命中，落默认品类
        assertSuggestion(response.getSuggestions().get(0), 2, false, CZ, "default");
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void classify_single_missingOrIllegalHint_rejected() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-S4")).thenReturn(batchWithRows("B-S4", List.of(
            row("型号", "A-001", "名称", "椅子")
        )));

        ExcelAiClassifyCategoriesRequest noHint = new ExcelAiClassifyCategoriesRequest();
        noHint.setMode(CategoryMode.SINGLE);
        noHint.setMapping(basicMapping());
        BusinessException e1 = assertThrows(BusinessException.class,
            () -> excelAiImportService.classifyRowCategories("B-S4", noHint));
        assertTrue(e1.getMessage().contains("请选择默认商品品类"), "异常信息: " + e1.getMessage());

        ExcelAiClassifyCategoriesRequest badHint = new ExcelAiClassifyCategoriesRequest();
        badHint.setMode(CategoryMode.SINGLE);
        badHint.setCategoryHint("NOPE");
        badHint.setMapping(basicMapping());
        BusinessException e2 = assertThrows(BusinessException.class,
            () -> excelAiImportService.classifyRowCategories("B-S4", badHint));
        assertTrue(e2.getMessage().contains("请选择默认商品品类"), "异常信息: " + e2.getMessage());
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // MIXED 候选集约束的 AI 逐行分类（§7/§15）
    // ------------------------------------------------------------------

    @Test
    void classify_mixed_candidatesTooFewOrIllegal_rejected() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-M1")).thenReturn(batchWithRows("B-M1", List.of(
            row("型号", "A-001", "名称", "椅子")
        )));

        ExcelAiClassifyCategoriesRequest tooFew = mixedRequest(List.of(CY));
        BusinessException e1 = assertThrows(BusinessException.class,
            () -> excelAiImportService.classifyRowCategories("B-M1", tooFew));
        assertTrue(e1.getMessage().contains("至少选择两个"), "异常信息: " + e1.getMessage());

        ExcelAiClassifyCategoriesRequest illegal = mixedRequest(List.of(CY, "NOPE"));
        BusinessException e2 = assertThrows(BusinessException.class,
            () -> excelAiImportService.classifyRowCategories("B-M1", illegal));
        assertTrue(e2.getMessage().contains("非法品类码"), "异常信息: " + e2.getMessage());
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void classify_mixed_aiWithinCandidates_accepted_sheetNameOnlyAsContext() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-M2")).thenReturn(batchWithRows("B-M2", List.of(
            row("型号", "WG-1", "名称", "胡桃木餐椅"),
            row("型号", "WG-2", "名称", "长方桌")
        )));
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"cy\"},{\"index\":2,\"categoryCode\":\"CZ\"}]");

        ExcelAiClassifyCategoriesRequest request = mixedRequest(List.of(CY, CZ));
        request.setSheetName("黑胡桃木高定系列");
        ExcelAiClassifyCategoriesResponse response = excelAiImportService.classifyRowCategories("B-M2", request);

        assertEquals(2, response.getSuggestions().size());
        // AI 输出大小写不敏感归一；来源 ai
        assertSuggestion(response.getSuggestions().get(0), 2, false, CY, "ai");
        assertSuggestion(response.getSuggestions().get(1), 3, false, CZ, "ai");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionService, times(1)).chatText(anyString(), promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // 候选集约束注入 prompt；Sheet 名仅作上下文线索（§26.8）
        assertTrue(prompt.contains(CY) && prompt.contains(CZ), "prompt 应含候选集: " + prompt);
        assertTrue(prompt.contains("黑胡桃木高定系列"), "prompt 应含 Sheet 名上下文: " + prompt);
        assertTrue(prompt.contains("WG-1") && prompt.contains("WG-2"), "prompt 应含产品文本: " + prompt);
    }

    @Test
    void classify_mixed_aiOutOfCandidates_nulled_aiNullAllowed_noForcedGuess() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-M3")).thenReturn(batchWithRows("B-M3", List.of(
            row("型号", "WG-1", "名称", "休闲椅"),
            row("型号", "WG-2", "名称", "信息不足 X")
        )));
        // 组1：AI 返回候选集外的字典码 XY → 越界置 null；组2：AI 返回 null → 允许，不强制猜（§7/§15）
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"XY\"},{\"index\":2,\"categoryCode\":null}]");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-M3", mixedRequest(List.of(CY, CZ)));

        assertSuggestion(response.getSuggestions().get(0), 2, false, null, "none");
        assertSuggestion(response.getSuggestions().get(1), 3, false, null, "none");
    }

    @Test
    void classify_mixed_aiInvalidJson_allUnrecognized_noError() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-M4")).thenReturn(batchWithRows("B-M4", List.of(
            row("型号", "WG-1", "名称", "椅子")
        )));
        when(visionService.chatText(anyString(), anyString())).thenReturn("抱歉，我无法判断");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-M4", mixedRequest(List.of(CY, CZ)));

        assertEquals(1, response.getSuggestions().size());
        assertSuggestion(response.getSuggestions().get(0), 2, false, null, "none");
    }

    @Test
    void classify_mixed_aiThrows_wholeChunkUnrecognized_notBlocking() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-M5")).thenReturn(batchWithRows("B-M5", List.of(
            row("型号", "WG-1", "名称", "椅子"),
            row("型号", "WG-2", "名称", "桌子")
        )));
        when(visionService.chatText(anyString(), anyString())).thenThrow(new RuntimeException("AI 超时"));

        // AI 整体失败不抛错阻断：本 chunk 全部按未识别处理
        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-M5", mixedRequest(List.of(CY, CZ)));

        assertEquals(2, response.getSuggestions().size());
        assertSuggestion(response.getSuggestions().get(0), 2, false, null, "none");
        assertSuggestion(response.getSuggestions().get(1), 3, false, null, "none");
    }

    @Test
    void classify_mixed_chunking_perChunkDegradation() {
        stubCategoryDicts();
        // 21 个逻辑产品 → 2 个 chunk（20 + 1）；首个 chunk 失败不影响第二个 chunk
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            rows.add(row("型号", "W-" + i, "名称", "产品 " + i));
        }
        when(batchMapper.selectById("B-M6")).thenReturn(batchWithRows("B-M6", rows));
        when(visionService.chatText(anyString(), anyString()))
            .thenThrow(new RuntimeException("AI 超时"))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"CZ\"}]");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-M6", mixedRequest(List.of(CY, CZ)));

        assertEquals(21, response.getSuggestions().size());
        verify(visionService, times(2)).chatText(anyString(), anyString());
        // 前 20 行（首个 chunk）整体降级为未识别
        for (int i = 0; i < 20; i++) {
            assertSuggestion(response.getSuggestions().get(i), i + 2, false, null, "none");
        }
        // 第 21 行（第二个 chunk）正常识别
        assertSuggestion(response.getSuggestions().get(20), 22, false, CZ, "ai");
    }

    @Test
    void classify_mixed_dictHitOutsideCandidates_adopted_groupExcludedFromAi() {
        stubCategoryDicts();
        // 无类别行在前：forward-fill 合并单元格语义下，后续空类别行会继承上行值
        when(batchMapper.selectById("B-M7")).thenReturn(batchWithRows("B-M7", List.of(
            row("型号", "B-1", "名称", "餐椅"),
            row("型号", "A-1", "名称", "休闲椅", "类别", XY)
        )));
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"CY\"}]");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-M7", mixedRequest(List.of(CY, CZ)));

        // 行内类别列确定性命中不受候选集限制（§9：Excel 自有数据可信，可超候选集）
        assertSuggestion(response.getSuggestions().get(0), 2, false, CY, "ai");
        assertSuggestion(response.getSuggestions().get(1), 3, false, XY, "dict");
        // 已确定性命中的组不送 AI：prompt 中只应出现未命中组的型号
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionService, times(1)).chatText(anyString(), promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("B-1"), "prompt 应含未命中组: " + promptCaptor.getValue());
        assertTrue(!promptCaptor.getValue().contains("A-1"), "dict 命中组不应送 AI: " + promptCaptor.getValue());
    }

    // ------------------------------------------------------------------
    // 逻辑产品分组（§14：去重按逻辑产品，复用 RSPU 分组规则）
    // ------------------------------------------------------------------

    @Test
    void classify_mixed_sameLogicalProduct_classifiedOnce_sharedResult() {
        stubCategoryDicts();
        // r3 型号空（纵向合并单元格）→ forward-fill 继承 X-1，与 r2 同一逻辑产品
        when(batchMapper.selectById("B-G1")).thenReturn(batchWithRows("B-G1", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("名称", "餐椅-模块行"),
            row("型号", "Y-1", "名称", "茶几")
        )));
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"CY\"},{\"index\":2,\"categoryCode\":\"CJ\"}]");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-G1", mixedRequest(List.of(CY, CJ)));

        assertEquals(3, response.getSuggestions().size());
        // 同一逻辑产品的变体行共享分类结果，不重复消耗 LLM 调用（§14）
        assertSuggestion(response.getSuggestions().get(0), 2, false, CY, "ai");
        assertSuggestion(response.getSuggestions().get(1), 3, false, CY, "ai");
        assertSuggestion(response.getSuggestions().get(2), 4, false, CJ, "ai");
        verify(visionService, times(1)).chatText(anyString(), anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionService).chatText(anyString(), promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("2. "), "2 个逻辑产品应都在 prompt 中: " + prompt);
        assertTrue(!prompt.contains("3. "), "同组模块行不应重复进 prompt: " + prompt);
    }

    @Test
    void classify_filteredRow_marked_andDoesNotBreakGroupContinuity() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-G2")).thenReturn(batchWithRows("B-G2", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("备注", "注意事项：轻拿轻放"),
            row("型号", "X-1", "名称", "餐椅-模块"),
            row("型号", "Z-1", "名称", "餐桌")
        )));
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("[{\"index\":1,\"categoryCode\":\"CY\"},{\"index\":2,\"categoryCode\":\"CZ\"}]");

        ExcelAiClassifyCategoriesResponse response =
            excelAiImportService.classifyRowCategories("B-G2", mixedRequest(List.of(CY, CZ)));

        assertEquals(4, response.getSuggestions().size());
        assertSuggestion(response.getSuggestions().get(0), 2, false, CY, "ai");
        // 系统过滤行（说明行）：filtered=true，无需确定品类
        assertSuggestion(response.getSuggestions().get(1), 3, true, null, "none");
        // 过滤行不打断连续性：r4 与 r2 仍属同一逻辑产品，共享分类结果
        assertSuggestion(response.getSuggestions().get(2), 4, false, CY, "ai");
        assertSuggestion(response.getSuggestions().get(3), 5, false, CZ, "ai");
        // 仅 2 个逻辑产品送 AI
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(visionService, times(1)).chatText(anyString(), promptCaptor.capture());
        assertTrue(!promptCaptor.getValue().contains("3. "), "应只有 2 个逻辑产品: " + promptCaptor.getValue());
    }

    @Test
    void groupKeys_consecutiveSameExternalCode_shareGroup() throws Exception {
        List<String> keys = computeGroupKeys(List.of(
            row("型号", "X-1", "名称", "a"),
            row("型号", "X-1", "名称", "b"),
            row("型号", "Y-1", "名称", "c")
        ), basicMapping());
        assertNotNull(keys.get(0));
        assertEquals(keys.get(0), keys.get(1), "连续相同 externalCode 应归同一组");
        assertTrue(!keys.get(0).equals(keys.get(2)), "不同 externalCode 应另起一组");
    }

    @Test
    void groupKeys_emptyExternalCode_independentAndBreaksContinuity() throws Exception {
        // 空码行各自独立成组
        List<String> keys = computeGroupKeys(List.of(
            row("名称", "无码 a"),
            row("名称", "无码 b")
        ), basicMapping());
        assertNotNull(keys.get(0));
        assertNotNull(keys.get(1));
        assertTrue(!keys.get(0).equals(keys.get(1)), "空码行应各自独立成组（空编码永不归组）");

        // 空码行打断前后连续性：X / 空码 / X → 三个组
        List<String> keys2 = computeGroupKeys(List.of(
            row("型号", "X-1", "名称", "a"),
            row("名称", "无码"),
            row("型号", "X-1", "名称", "b")
        ), basicMapping());
        assertTrue(!keys2.get(0).equals(keys2.get(1)) && !keys2.get(1).equals(keys2.get(2))
            && !keys2.get(0).equals(keys2.get(2)), "空码行应打断连续性");
    }

    @Test
    void groupKeys_systemFilteredRows_nullAndDoNotBreakContinuity() throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>(basicMapping());
        mapping.put("尺寸", "dimensions");
        List<String> keys = computeGroupKeys(List.of(
            row("型号", "X-1", "名称", "a"),
            row("备注", "注意事项：勿折"),
            row("型号", "型号", "名称", "名称"), // 重复表头行
            row("类别", "一桌四椅组合"),        // 组合汇总价行（无尺寸等标识字段）
            row("型号", "X-1", "名称", "b")
        ), mapping);
        assertNotNull(keys.get(0));
        assertNull(keys.get(1), "说明行不参与分组");
        assertNull(keys.get(2), "重复表头行不参与分组");
        assertNull(keys.get(3), "组合汇总价行不参与分组");
        assertEquals(keys.get(0), keys.get(4), "系统过滤行不应打断前后行连续性");
    }

    @Test
    void groupKeys_matchesImportLoopCurrentGroupSemantics() throws Exception {
        // 对照用例：computeLogicalGroupKeys 与导入主循环内联 currentGroup 归组语义一致。
        // 这里按导入循环算法（prepareRow 的 sameProduct 判定 + 主循环的组状态更新）模拟一遍，
        // 要求两种实现的分组等价关系完全一致。
        Map<String, String> mapping = basicMapping();
        List<Map<String, String>> rows = List.of(
            row("型号", "X-1", "名称", "a"),
            row("型号", "X-1", "名称", "b"),
            row("备注", "注意事项：说明行"),
            row("型号", "X-1", "名称", "c"),
            row("名称", "无码行"),
            row("型号", "Y-1", "名称", "d"),
            row("型号", "X-1", "名称", "e")
        );
        // 模拟导入主循环：sameProduct = currentGroup != null && hasText(code) && code.equals(current)；
        // 系统过滤行提前跳过、不触碰当前组状态
        List<Integer> loopGroups = new ArrayList<>();
        boolean hasCurrent = false;
        String currentCode = null;
        int currentId = -1;
        int seq = 0;
        for (Map<String, String> r : rows) {
            if (isFilteredRow(r, mapping)) {
                loopGroups.add(null);
                continue;
            }
            String code = resolveExternalCode(r, mapping);
            boolean sameProduct = hasCurrent && code != null && !code.isBlank() && code.equals(currentCode);
            if (!sameProduct) {
                currentId = seq++;
                currentCode = code;
                hasCurrent = true;
            }
            loopGroups.add(currentId);
        }
        List<String> keys = computeGroupKeys(rows, mapping);
        // 把 computeLogicalGroupKeys 的 key 按出现顺序重编号为整数 id，逐位比较等价关系
        Map<String, Integer> ids = new LinkedHashMap<>();
        List<Integer> helperGroups = new ArrayList<>();
        for (String key : keys) {
            if (key == null) {
                helperGroups.add(null);
            } else {
                helperGroups.add(ids.computeIfAbsent(key, k -> ids.size()));
            }
        }
        assertEquals(loopGroups, helperGroups, "computeLogicalGroupKeys 应与导入主循环 currentGroup 归组语义一致");
    }

    // ------------------------------------------------------------------
    // 导入前置品类校验（§20/§22，confirmAndImport 兜底）
    // ------------------------------------------------------------------

    @Test
    void import_oldPath_noMode_skipsCategoryPrecheck_keepsPerRowTolerance() {
        // 旧路径（未选导入方式）不做品类前置校验，维持逐行容错（§26.1）：
        // 无品类的行逐行失败进失败清单，而不是整批拒绝
        when(batchMapper.selectById("B-OLD")).thenReturn(batchWithRows("B-OLD", List.of(
            row("型号", "A-1", "名称", "无品类行")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-OLD");
        request.setMapping(basicMapping());

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getFailures().get(0).getReason().contains("品类码不能为空"),
            "旧路径应保持逐行报错: " + result.getFailures());
        verify(batchMapper, times(1)).claimForImport("B-OLD");
    }

    @Test
    void import_single_missingHint_rejectedBeforeClaim() {
        when(batchMapper.selectById("B-V1")).thenReturn(batchWithRows("B-V1", List.of(
            row("型号", "A-1", "名称", "a")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V1");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.SINGLE);

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("请选择默认商品品类"), "异常信息: " + e.getMessage());
        // 前置校验失败不扰动批次状态（不抢占导入权）
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_single_illegalHint_rejected() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-V2")).thenReturn(batchWithRows("B-V2", List.of(
            row("型号", "A-1", "名称", "a")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V2");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint("NOPE");

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("不是合法品类码"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_mixed_candidatesTooFew_rejected() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-V3")).thenReturn(batchWithRows("B-V3", List.of(
            row("型号", "A-1", "名称", "a")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V3");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY));

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("至少选择两个"), "异常信息: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_rowSelection_illegalCode_rejectedWithRowNumber() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-V4")).thenReturn(batchWithRows("B-V4", List.of(
            row("型号", "A-1", "名称", "a")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V4");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setRowCategorySelections(Map.of(2, "NOPE"));

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("非法品类码"), "异常信息: " + e.getMessage());
        assertTrue(e.getMessage().contains("行号 2"), "应给出行号: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_mixed_undeterminedRows_rejectedWithRowList() {
        stubCategoryDicts();
        when(batchMapper.selectById("B-V5")).thenReturn(batchWithRows("B-V5", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V5");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        // 未提交 rowCategorySelections：MIXED 无兜底 → 两行均未确定

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("未确定"), "异常信息: " + e.getMessage());
        assertTrue(e.getMessage().contains("2") && e.getMessage().contains("3"),
            "应返回未确定行号清单: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_mixed_skipRowsExcludedFromPrecheck() {
        stubCategoryDicts();
        stubCreatePath();
        when(batchMapper.selectById("B-V6")).thenReturn(batchWithRows("B-V6", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V6");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setRowCategorySelections(Map.of(2, CY));
        request.setSkipRows(List.of(3)); // 未确定的行 3 被用户跳过 → 不拦截

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(captor.capture());
        assertEquals(CY, captor.getValue().getCategoryCode());
    }

    @Test
    void import_sameLogicalProduct_conflictingCategories_rejectedWithRowList() {
        stubCategoryDicts();
        // r3 型号空 → forward-fill 继承 X-1，与 r2 同一逻辑产品
        when(batchMapper.selectById("B-V7")).thenReturn(batchWithRows("B-V7", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("名称", "餐椅-模块行")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V7");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setRowCategorySelections(Map.of(2, CY, 3, CZ)); // 同组两行品类不一致

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("同一逻辑产品"), "异常信息: " + e.getMessage());
        assertTrue(e.getMessage().contains("2") && e.getMessage().contains("3"),
            "应给出冲突行号清单: " + e.getMessage());
        verify(batchMapper, never()).claimForImport(anyString());
    }

    @Test
    void import_sameLogicalProduct_consistentCategory_passes_singleRspu_snapshotWritten() {
        stubCategoryDicts();
        stubCreatePath();
        when(batchMapper.selectById("B-V8")).thenReturn(batchWithRows("B-V8", List.of(
            row("型号", "X-1", "名称", "餐椅"),
            row("名称", "餐椅-模块行")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-V8");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        request.setRowCategorySelections(Map.of(2, CY, 3, CY));

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        // 同一逻辑产品只建一个 RSPU
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertEquals(CY, rspuCaptor.getValue().getCategoryCode());
        // 行级最终品类写入行记录 mapped_fields 快照（§17）
        ArgumentCaptor<Map<String, String>> mappedFieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(excelImportRowService, times(2)).initRow(anyString(), anyInt(), anyString(), anyMap(), any(),
            mappedFieldsCaptor.capture(), anyList());
        for (Map<String, String> mappedFields : mappedFieldsCaptor.getAllValues()) {
            assertEquals(CY, mappedFields.get("categoryCode"), "mapped_fields 应含行级最终品类快照");
        }
    }

    // ------------------------------------------------------------------
    // 新模式正式导入的收敛兜底链（§26.1）
    // ------------------------------------------------------------------

    @Test
    void import_single_fullFlow_rowHitBeatsHint_hintFillsEmpty_categoryGuessIgnored() {
        stubCategoryDicts();
        stubCreatePath();
        ExcelImportBatch batch = batchWithRows("B-F1", List.of(
            row("型号", "A-1", "名称", "无类别行"),
            row("型号", "B-1", "名称", "有类别行", "类别", CY)
        ));
        // categoryGuess（previewMapping 的整文件猜测，存 category_hint 列）：新模式下退出兜底链
        batch.setCategoryHint(CJ);
        when(batchMapper.selectById("B-F1")).thenReturn(batch);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-F1");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.SINGLE);
        request.setCategoryHint(CZ);

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(2)).insert(captor.capture());
        List<RspuMaster> created = captor.getAllValues();
        assertEquals(CZ, created.get(0).getCategoryCode(), "空值行应落默认品类 categoryHint");
        assertEquals(CY, created.get(1).getCategoryCode(), "行内类别列归一命中应优先于 categoryHint");
        for (RspuMaster rspu : created) {
            assertTrue(!CJ.equals(rspu.getCategoryCode()), "categoryGuess 在新模式下不应参与兜底");
        }
    }

    @Test
    void import_mixed_fullFlow_selectionsApplied_unknownRowNumberIgnored_snapshotWritten() {
        stubCategoryDicts();
        stubCreatePath();
        when(batchMapper.selectById("B-F2")).thenReturn(batchWithRows("B-F2", List.of(
            row("型号", "A-1", "名称", "a"),
            row("型号", "B-1", "名称", "b")
        )));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-F2");
        request.setMapping(basicMapping());
        request.setCategoryMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(List.of(CY, CZ));
        // 行号 99 不存在：多余选择项不影响导入（按行号匹配，永不命中）
        request.setRowCategorySelections(Map.of(2, CY, 3, CZ, 99, CZ));

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(2)).insert(captor.capture());
        assertEquals(CY, captor.getAllValues().get(0).getCategoryCode());
        assertEquals(CZ, captor.getAllValues().get(1).getCategoryCode());
        ArgumentCaptor<Map<String, String>> mappedFieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(excelImportRowService, times(2)).initRow(anyString(), anyInt(), anyString(), anyMap(), any(),
            mappedFieldsCaptor.capture(), anyList());
        assertEquals(CY, mappedFieldsCaptor.getAllValues().get(0).get("categoryCode"));
        assertEquals(CZ, mappedFieldsCaptor.getAllValues().get(1).get("categoryCode"));
    }

    // ------------------------------------------------------------------
    // 更新模式跨品类保护（§21.1）
    // ------------------------------------------------------------------

    @Test
    void import_updateMode_crossCategory_retainsOriginal_reportsIssue() {
        stubCategoryDicts();
        stubVariant();
        when(batchMapper.selectById("B-U1")).thenReturn(batchWithRows("B-U1", List.of(
            row("型号", "ABC-001", "名称", "新名称", "类别", CY)
        )));
        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-EXIST");
        existing.setExternalCode("ABC-001");
        existing.setProductName("原有名称");
        existing.setCategoryCode(CZ);
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-U1");
        request.setMapping(basicMapping());
        request.setUpdateIfExists(true);

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).updateById(captor.capture());
        RspuMaster updated = captor.getValue();
        // 品类不更新：保留原品类（§21.1）
        assertEquals(CZ, updated.getCategoryCode(), "品类与已有商品不一致时应保留原品类");
        // 其余字段更新语义不变
        assertEquals("新名称", updated.getProductName());
        // 记用户可见的行级提示
        assertTrue(result.getFailures().stream().anyMatch(f -> f.getReason() != null
                && f.getReason().contains("已保留原品类")
                && f.getReason().contains(CZ) && f.getReason().contains(CY)),
            "应记录跨品类保护提示: " + result.getFailures());
    }

    @Test
    void import_updateMode_sameCategory_noProtectionIssue() {
        stubCategoryDicts();
        stubVariant();
        when(batchMapper.selectById("B-U2")).thenReturn(batchWithRows("B-U2", List.of(
            row("型号", "ABC-001", "名称", "新名称", "类别", CZ)
        )));
        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-EXIST");
        existing.setExternalCode("ABC-001");
        existing.setCategoryCode(CZ);
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-U2");
        request.setMapping(basicMapping());
        request.setUpdateIfExists(true);

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).updateById(captor.capture());
        assertEquals(CZ, captor.getValue().getCategoryCode());
        assertTrue(result.getFailures().stream().noneMatch(f -> f.getReason() != null
                && f.getReason().contains("已保留原品类")),
            "品类一致时不应记保护提示: " + result.getFailures());
    }

    @Test
    void import_updateMode_emptyExistingCategory_filled() {
        stubCategoryDicts();
        stubVariant();
        when(batchMapper.selectById("B-U3")).thenReturn(batchWithRows("B-U3", List.of(
            row("型号", "ABC-001", "名称", "新名称", "类别", CY)
        )));
        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-EXIST");
        existing.setExternalCode("ABC-001");
        existing.setCategoryCode(null); // 已有品类为空 → 补填
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("B-U3");
        request.setMapping(basicMapping());
        request.setUpdateIfExists(true);

        ExcelAiImportResult result = confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).updateById(captor.capture());
        assertEquals(CY, captor.getValue().getCategoryCode(), "已有品类为空时应补填本次品类");
        assertNotNull(captor.getValue().getCategoryPath(), "补填品类时应同步 categoryPath");
        assertTrue(result.getFailures().stream().noneMatch(f -> f.getReason() != null
                && f.getReason().contains("已保留原品类")),
            "补填不应记保护提示: " + result.getFailures());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void stubCategoryDicts() {
        when(dictService.listByType("category")).thenReturn(List.of(
            createDict("category", CY, "餐椅"),
            createDict("category", CZ, "餐桌"),
            createDict("category", CJ, "茶几"),
            createDict("category", XY, "休闲椅")
        ));
    }

    /** 新建路径公共桩：RSPU insert 回填 ID + 变体创建。 */
    private void stubCreatePath() {
        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-" + System.nanoTime());
            return 1;
        });
        stubVariant();
    }

    private void stubVariant() {
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        lenient().when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);
    }

    private void assertSuggestion(ExcelAiClassifyCategoriesResponse.RowCategorySuggestion suggestion,
                                  int rowIndex, boolean filtered, String expectedCode, String expectedSource) {
        assertEquals(rowIndex, suggestion.getRowIndex(), "行号");
        assertEquals(filtered, suggestion.isFiltered(), "filtered 标记, row=" + rowIndex);
        assertEquals(expectedCode, suggestion.getSuggestedCategoryCode(), "建议品类码, row=" + rowIndex);
        assertEquals(expectedSource, suggestion.getSource(), "建议来源, row=" + rowIndex);
    }

    private ExcelAiClassifyCategoriesRequest mixedRequest(List<String> candidates) {
        ExcelAiClassifyCategoriesRequest request = new ExcelAiClassifyCategoriesRequest();
        request.setMode(CategoryMode.MIXED);
        request.setCandidateCategoryCodes(candidates);
        request.setMapping(basicMapping());
        return request;
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

    private CategoryDict createDict(String dictType, String dictCode, String dictName) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType(dictType);
        dict.setDictCode(dictCode);
        dict.setDictName(dictName);
        return dict;
    }

    @SuppressWarnings("unchecked")
    private List<String> computeGroupKeys(List<Map<String, String>> rows, Map<String, String> mapping)
        throws Exception {
        Method m = ExcelAiImportService.class.getDeclaredMethod("computeLogicalGroupKeys", List.class, Map.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(excelAiImportService, rows, mapping);
    }

    private String resolveExternalCode(Map<String, String> row, Map<String, String> mapping) throws Exception {
        Method m = ExcelAiImportService.class.getDeclaredMethod("resolveRowExternalCode", Map.class, Map.class);
        m.setAccessible(true);
        return (String) m.invoke(excelAiImportService, row, mapping);
    }

    private boolean isFilteredRow(Map<String, String> row, Map<String, String> mapping) throws Exception {
        Method note = ExcelAiImportService.class.getDeclaredMethod("isNoteOrEmptyRow", Map.class);
        note.setAccessible(true);
        Method header = ExcelAiImportService.class.getDeclaredMethod("isRepeatedHeaderRow", Map.class);
        header.setAccessible(true);
        Method combo = ExcelAiImportService.class.getDeclaredMethod("isComboSummaryRow", Map.class, Map.class);
        combo.setAccessible(true);
        return (boolean) note.invoke(excelAiImportService, row)
            || (boolean) header.invoke(excelAiImportService, row)
            || (boolean) combo.invoke(excelAiImportService, row, mapping);
    }
}
