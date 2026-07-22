package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
import com.rsdp.dto.response.PriceColumnInfo;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Excel AI 辅助导入服务测试。
 */
@ExtendWith(MockitoExtension.class)
class ExcelAiImportServiceTest {

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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(excelImportRowService.initRow(anyString(), anyInt(), anyString(), anyMap(), any()))
            .thenAnswer(inv -> System.nanoTime());
        lenient().when(factoryLeadTimeRuleService.calculateLeadTime(anyString(), any(), any(), anyString(), anyInt()))
            .thenReturn(null);
        // 默认批次可抢占导入权（P1-6 并发防护）；个别测试可覆盖为 0 模拟冲突
        lenient().when(batchMapper.claimForImport(anyString())).thenReturn(1);
        // previewMapping 的 saveBatch 使用编程式事务（P1-7），单测中事务管理器全部 mock
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void previewMapping_shouldCallAiAndSaveBatch() throws IOException {
        byte[] excelBytes = createMinimalExcelBytes();
        java.util.List<java.util.Map<Integer, String>> rawRows = readExcelRaw(excelBytes);
        org.junit.jupiter.api.Assertions.assertEquals(2, rawRows.size(), "测试 Excel 应包含表头和 1 行数据");

        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        // 品类值「FS」本身就是字典码：本地解析命中，不再触发第二次 AI 品类归一调用
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals("BATCH-TEST", response.getBatchId());
            assertEquals("categoryCode", response.getSuggestedMapping().get("品类"));
            assertEquals("productName", response.getSuggestedMapping().get("名称"));
            assertEquals(1, response.getPreviewRows().size());
        }

        verify(visionService, times(1)).chatText(anyString(), anyString());
        verify(batchMapper, times(1)).insert(any(ExcelImportBatch.class));
        // P1-7：previewMapping 不再持有方法级事务，批次写入使用编程式事务
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void confirmAndImport_shouldCreateRspuForEachRow() throws IOException {
        byte[] excelBytes = createMinimalExcelBytes();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats.officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\",\"风格\":\"positioningLabel\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createMinimalExcelBytes()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("style", "MC", "中古风")));
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("RSPU-TEST-V001");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(Map.of(
            "品类", "categoryCode",
            "名称", "productName",
            "风格", "positioningLabel"
        ));

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(1, result.getTotalRows());
        if (!result.getFailures().isEmpty()) {
            System.out.println("导入失败明细: " + result.getFailures());
        }
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(1, result.getRspuIds().size());
        assertTrue(result.getRspuIds().get(0).startsWith("RSPU-"));

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        RspuMaster created = rspuCaptor.getValue();
        assertEquals("FS", created.getCategoryCode());
        assertEquals("MC", created.getPositioningLabel());
    }

    @Test
    void previewMapping_shouldHandleEmptyHeaderCell() throws IOException {
        byte[] excelBytes = createExcelWithEmptyHeader();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals("BATCH-TEST", response.getBatchId());
            assertEquals(1, response.getPreviewRows().size());
            assertTrue(response.getPreviewRows().get(0).containsKey("名称"));
        }

        verify(batchMapper, times(1)).insert(any(ExcelImportBatch.class));
    }

    @Test
    void previewMapping_shouldRecognizeMultiPriceColumns() throws IOException {
        byte[] excelBytes = createExcelWithMultiPriceColumns();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode,productName\",\"产品尺寸\":\"dimensions\",\"材质说明\":\"materialTags\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals(2, response.getPriceColumns().size());
            assertEquals("A级布", response.getPriceColumns().get(0).getMaterialName());
            assertEquals("AA级布", response.getPriceColumns().get(1).getMaterialName());
            assertEquals("价格（PRICE）-A级布", response.getPriceColumns().get(0).getHeader());
        }

        verify(batchMapper, times(1)).insert(any(ExcelImportBatch.class));
    }

    @Test
    void confirmAndImport_shouldCreateVariantsAndRskusForPriceColumns() throws IOException {
        byte[] excelBytes = createExcelWithMultiPriceColumns();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode,productName\",\"产品尺寸\":\"dimensions\",\"材质说明\":\"materialTags\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithMultiPriceColumns()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponseA = new RspuVariantResponse();
        variantResponseA.setVariantId("V-A");
        RspuVariantResponse variantResponseAA = new RspuVariantResponse();
        variantResponseAA.setVariantId("V-AA");
        when(rspuVariantService.createVariant(anyString(), any()))
            .thenReturn(variantResponseA, variantResponseAA);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setDefaultShippingFrom("广东佛山");
        request.setDefaultMoq(10);
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());

        verify(rspuVariantService, times(2)).createVariant(anyString(), any());
        verify(rskuService, times(2)).createRsku(any());
    }

    @Test
    void previewMapping_shouldFilterImageColumnMapping() throws IOException {
        byte[] excelBytes = createExcelWithImageColumn();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // AI 误把内嵌图片列映射为主图 URL
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"产品图样\":\"primaryImageUrl\",\"型号\":\"externalCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals("externalCode", response.getSuggestedMapping().get("型号"));
            assertEquals("productName", response.getSuggestedMapping().get("名称"));
            assertTrue(!response.getSuggestedMapping().containsKey("产品图样")
                    || response.getSuggestedMapping().get("产品图样") == null,
                "内嵌图片列不应被映射为 URL 字段");
        }
    }

    @Test
    void confirmAndImport_shouldSkipNoteRow() throws IOException {
        byte[] excelBytes = createExcelWithNoteRow();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithNoteRow()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-DEFAULT");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(2, result.getTotalRows(), "原始数据行应包含 1 行数据 + 1 行说明");
        assertEquals(1, result.getSuccessCount(), "说明行应被跳过");
        assertEquals(0, result.getFailedCount());
    }

    @Test
    void confirmAndImport_shouldInferPriceParentHeaderSpan() throws IOException {
        byte[] excelBytes = createExcelWithPriceParentSpan();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithPriceParentSpan()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any()))
            .thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        // 两个价格列都应识别并创建变体
        verify(rspuVariantService, times(2)).createVariant(anyString(), any());
    }

    @Test
    void confirmAndImport_shouldAttachEmbeddedImageByPhysicalRow() throws IOException {
        // 双行表头场景：数据从物理行 2 开始，图片锚在物理行 2。
        // 旧的「rowIndex - 1」换算会查物理行 1（表头行）导致图片丢失；重建物理行号后应精确命中。
        byte[] excelBytes = createExcelWithImageOnDataRow();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        // 导入时会读取两次原始文件（提取图片 + 重建行号），每次需返回新流
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithImageOnDataRow()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        // 图片应通过物理行号精确关联到数据行：imageCount == 1
        verify(excelImportRowService).markSuccess(any(), any(), any(), any(), eq(1), any(), any());

        // P0-2：tasks 提供 taskId ↔ rspuId 成对关联，不与 rspuIds 平行数组错位
        assertEquals(1, result.getTasks().size());
        assertEquals(result.getRspuIds().get(0), result.getTasks().get(0).getRspuId());
        assertNotNull(result.getTasks().get(0).getTaskId());
        assertEquals(result.getTaskIds().get(0), result.getTasks().get(0).getTaskId());
    }

    @Test
    void confirmAndImport_shouldPreferRowLeadTimeDays() throws IOException {
        // 数据行带「交期」列（值 30天）：RSKU 交期应取行级值，不再调用工厂规则动态计算
        byte[] excelBytes = createExcelWithLeadTimeColumn();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\",\"交期\":\"leadTimeDays\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithLeadTimeColumn()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setDefaultLeadTimeDays(15);
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertNotNull(result);
        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());

        ArgumentCaptor<com.rsdp.dto.request.RskuCreateRequest> rskuCaptor =
            ArgumentCaptor.forClass(com.rsdp.dto.request.RskuCreateRequest.class);
        verify(rskuService, times(2)).createRsku(rskuCaptor.capture());
        for (com.rsdp.dto.request.RskuCreateRequest rsku : rskuCaptor.getAllValues()) {
            assertEquals(30, rsku.getLeadTimeDays(), "RSKU 交期应取 Excel 行级值");
        }
        verify(factoryLeadTimeRuleService, never()).calculateLeadTime(anyString(), any(), any(), anyString(), anyInt());
    }

    @Test
    void confirmAndImport_shouldHandleMergedCellsRepeatedHeadersAndNullPrices() throws IOException {
        // 综合真实场景：
        // - 数据行 2 的型号列为空（纵向合并单元格），应继承上一行型号
        // - 中间混有重复双行表头，应跳过不建产品
        // - 数据行 2 的 AA级布价格为空（key 存在 value 为 null），不应 NPE
        byte[] excelBytes = createExcelWithMergedCellsAndRepeatedHeader();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode\",\"规格/模块\":\"variantDisplayName\",\"交期\":\"leadTimeDays\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithMergedCellsAndRepeatedHeader()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        // 3 个数据行全部成功（含 null 价格行），2 行重复表头被跳过
        assertEquals(3, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        assertEquals(0, result.getFailedCount());
        verify(excelImportRowService, times(2)).markSkipped(any(), anyString());

        // 产品归组：行 2 型号继承行 1（合并单元格填充），同型号归入同一 RSPU，
        // 只新建 2 个产品（MJ-S96、MJ-S97），模块行不再成为独立产品
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(2)).insert(rspuCaptor.capture());
        assertEquals("MJ-S96", rspuCaptor.getAllValues().get(0).getExternalCode());
        assertEquals("MJ-S97", rspuCaptor.getAllValues().get(1).getExternalCode());

        // 变体按「行 × 价格列」创建：行1（2）+ 行2（1）+ 行3（2）= 5 个
        verify(rspuVariantService, times(5)).createVariant(anyString(), any());

        // RSKU：行1（2 个价格）+ 行2（1 个价格，AA级布为空跳过）+ 行3（2 个价格）= 5 个
        verify(rskuService, times(5)).createRsku(any());
    }

    @Test
    void confirmAndImport_shouldShareGroupPrimaryImageAndFilterNonImageColumns() throws IOException {
        // 图片语义：同产品模块行共享组主图，模块行的图降级为详情图；
        // 非图片列的内嵌图（logo/二维码）不识别为产品图
        byte[] excelBytes = createExcelWithModuleImagesAndLogo();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode\",\"规格/模块\":\"variantDisplayName\",\"价格\":\"__PRICE__:A级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithModuleImagesAndLogo()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-GROUP");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());

        // 归组：两个模块行只创建一个 RSPU
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));

        // 图片：共 2 张入库（PNG1 主图 + PNG2 详情图），col5 的 logo 被过滤
        ArgumentCaptor<com.rsdp.entity.ImageAssets> imageCaptor =
            ArgumentCaptor.forClass(com.rsdp.entity.ImageAssets.class);
        verify(imageAssetsMapper, times(2)).insert(imageCaptor.capture());
        var savedImages = imageCaptor.getAllValues();
        assertTrue(savedImages.get(0).getPrimary(), "行 1 图片应成为组主图");
        assertEquals("white_bg", savedImages.get(0).getImageType());
        assertEquals(Boolean.FALSE, savedImages.get(1).getPrimary(), "行 2 模块图应降级为详情图，不覆盖组主图");
        assertEquals("detail", savedImages.get(1).getImageType());
        assertEquals(savedImages.get(0).getRspuId(), savedImages.get(1).getRspuId(), "模块图应挂在同一 RSPU 下");
        assertNull(savedImages.get(0).getVariantId(), "产品图样列的图归属 RSPU 产品级");
        assertNull(savedImages.get(1).getVariantId(), "产品图样列的图归属 RSPU 产品级");

        // 组内 AI 识别任务只建一次（基于组主图）
        verify(asyncTaskMapper, times(1)).insert(any(com.rsdp.entity.AsyncTask.class));
    }

    @Test
    void confirmAndImport_shouldNotPromoteModuleExampleImageToPrimary() throws IOException {
        // 表格有图片列，但产品行的图只锚在「规格/模块」列（模块样式示例图）：
        // 示例图只能作为详情图，不得升为主图，也不应触发 AI 识别任务
        byte[] excelBytes = createExcelWithModuleOnlyImage();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode\",\"规格/模块\":\"variantDisplayName\",\"价格\":\"__PRICE__:A级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithModuleOnlyImage()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());

        // 示例图入库但必须是详情图，不能升为主图
        ArgumentCaptor<com.rsdp.entity.ImageAssets> imageCaptor =
            ArgumentCaptor.forClass(com.rsdp.entity.ImageAssets.class);
        verify(imageAssetsMapper, times(1)).insert(imageCaptor.capture());
        assertEquals(Boolean.FALSE, imageCaptor.getValue().getPrimary(), "模块示例图不得升为主图");
        assertEquals("detail", imageCaptor.getValue().getImageType());
        assertEquals("V-A", imageCaptor.getValue().getVariantId(), "模块示例图应挂到本行变体");

        // 无主图 → 不建 AI 识别任务
        verify(asyncTaskMapper, never()).insert(any(com.rsdp.entity.AsyncTask.class));
    }

    @Test
    void confirmAndImport_shouldSaveMultipleStylesWithFirstAsPrimary() throws IOException {
        // 风格列多值（「中古风/奶油风」）：首值为主风格写 positioning_label + is_primary=true，
        // 其余为辅风格写 rspu_style(is_primary=false)
        byte[] excelBytes = createExcelWithMultiStyle();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\",\"风格\":\"positioningLabel\",\"价格\":\"__PRICE__:A级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithMultiStyle()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of(
            createDict("style", "MC", "中古风"),
            createDict("style", "CR", "奶油风")));
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(rspuMapper.insert(any(RspuMaster.class))).thenAnswer(inv -> {
            RspuMaster rspu = inv.getArgument(0);
            rspu.setRspuId("RSPU-TEST");
            return 1;
        });

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());

        // 主风格写入 positioning_label
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertEquals("MC", rspuCaptor.getValue().getPositioningLabel(), "主字段应存第一个风格");

        // rspu_style 两条：MC 主、CR 辅
        ArgumentCaptor<com.rsdp.entity.RspuStyle> styleCaptor =
            ArgumentCaptor.forClass(com.rsdp.entity.RspuStyle.class);
        verify(rspuStyleMapper, times(2)).insert(styleCaptor.capture());
        var styles = styleCaptor.getAllValues();
        assertEquals("MC", styles.get(0).getStyleCode());
        assertEquals(Boolean.TRUE, styles.get(0).getIsPrimary(), "第一个风格应为主风格");
        assertEquals("CR", styles.get(1).getStyleCode());
        assertEquals(Boolean.FALSE, styles.get(1).getIsPrimary(), "后续风格应为辅风格");
    }

    @Test
    void confirmAndImport_shouldResolveChineseCategoryViaDictName() throws IOException {
        // 品类中文名归一：字典中文名精确匹配（茶桌/沙发等中文品名 → 字典码）
        ExcelImportBatch savedBatch = prepareCategoryBatch(createExcelWithCategoryValues("沙发"),
            "{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");

        when(batchMapper.selectById(savedBatch.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithCategoryValues("沙发")));
        stubCategoryDicts();
        when(dictResolverService.resolveCodeByName("category", "沙发")).thenReturn("FS");
        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(savedBatch.getBatchId());
        request.setMapping(Map.of("品类", "categoryCode", "名称", "productName"));

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertEquals("FS", rspuCaptor.getValue().getCategoryCode(), "中文品名「沙发」应归一为字典码 FS");
    }

    @Test
    void confirmAndImport_shouldResolveChineseCategoryViaAlias() throws IOException {
        // 品类中文名归一：字典名不中的方言（茶桌）走别名库
        ExcelImportBatch savedBatch = prepareCategoryBatch(createExcelWithCategoryValues("茶桌"),
            "{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");

        when(batchMapper.selectById(savedBatch.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithCategoryValues("茶桌")));
        stubCategoryDicts();
        when(dictAliasService.resolveAlias("category", "茶桌")).thenReturn("TB");
        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(savedBatch.getBatchId());
        request.setMapping(Map.of("品类", "categoryCode", "名称", "productName"));

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertEquals("TB", rspuCaptor.getValue().getCategoryCode(), "方言「茶桌」应经别名库归一为 TB");
        verify(dictAliasService).resolveAlias("category", "茶桌");
    }

    @Test
    void confirmAndImport_shouldPreferUserCategoryMappingAndLearnAlias() throws IOException {
        // 用户确认映射优先级最高（高于字典名/别名），导入后写回别名库自学习
        ExcelImportBatch savedBatch = prepareCategoryBatch(createExcelWithCategoryValues("茶桌"),
            "{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");

        when(batchMapper.selectById(savedBatch.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithCategoryValues("茶桌")));
        stubCategoryDicts();
        // 字典名层会解析出 FS，但用户确认映射指定 TB，应优先采用 TB（本桩故意不命中：验证短路）
        lenient().when(dictResolverService.resolveCodeByName("category", "茶桌")).thenReturn("FS");
        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(savedBatch.getBatchId());
        request.setMapping(Map.of("品类", "categoryCode", "名称", "productName"));
        request.setCategoryMapping(Map.of("茶桌", "TB"));

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertEquals("TB", rspuCaptor.getValue().getCategoryCode(), "用户确认映射应优先于字典名解析");
        verify(dictResolverService, never()).resolveCodeByName(anyString(), anyString());
        // 用户确认的映射写回别名库自学习
        verify(dictAliasService).saveAlias(eq("category"), eq("茶桌"), eq("TB"), any());
    }

    @Test
    void confirmAndImport_shouldKeepRawCategoryWhenUnresolvable() throws IOException {
        // 无法解析的品类词：保留原值，validateRow 报「品类码不存在」（行为兼容）
        ExcelImportBatch savedBatch = prepareCategoryBatch(createExcelWithCategoryValues("未知品类"),
            "{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");

        when(batchMapper.selectById(savedBatch.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithCategoryValues("未知品类")));
        stubCategoryDicts();

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(savedBatch.getBatchId());
        request.setMapping(Map.of("品类", "categoryCode", "名称", "productName"));

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getFailures().get(0).getReason().contains("品类码不存在"),
            "失败原因: " + result.getFailures().get(0).getReason());
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
    }

    @Test
    void previewMapping_shouldSuggestCategoryMappingsViaDictAliasAndAi() throws IOException {
        // 预览品类映射建议：字典名命中 dict、别名库命中 alias、未解析词 AI 归一
        byte[] excelBytes = createExcelWithCategoryValues("沙发", "方凳", "茶桌");
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}")
            .thenReturn("[{\"rawValue\":\"茶桌\",\"dictCode\":\"TB\"}]");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });
        when(dictService.listByType("category")).thenReturn(List.of(
            createDict("category", "SF", "沙发"),
            createDict("category", "TB", "茶几"),
            createDict("category", "FC", "柜类")));
        when(dictAliasService.resolveAliases(eq("category"), any())).thenReturn(Map.of("方凳", "FC"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals(3, response.getCategoryMappings().size());
            Map<String, com.rsdp.dto.response.CategoryMappingItem> byRaw = new java.util.HashMap<>();
            for (com.rsdp.dto.response.CategoryMappingItem item : response.getCategoryMappings()) {
                byRaw.put(item.getRawValue(), item);
            }
            assertEquals("SF", byRaw.get("沙发").getSuggestedCode());
            assertEquals("dict", byRaw.get("沙发").getSource());
            assertEquals("FC", byRaw.get("方凳").getSuggestedCode());
            assertEquals("alias", byRaw.get("方凳").getSource());
            assertEquals("TB", byRaw.get("茶桌").getSuggestedCode());
            assertEquals("ai", byRaw.get("茶桌").getSource());
        }

        verify(visionService, times(2)).chatText(anyString(), anyString());
    }

    @Test
    void previewMapping_shouldDegradeCategorySuggestionWhenAiFails() throws IOException {
        // AI 品类归一调用失败：未解析词降级为 none，不阻断预览
        byte[] excelBytes = createExcelWithCategoryValues("茶桌");
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"品类\":\"categoryCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}")
            .thenThrow(new RuntimeException("AI down"));
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "TB", "茶几")));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals("BATCH-TEST", response.getBatchId());
            assertEquals(1, response.getCategoryMappings().size());
            assertEquals("茶桌", response.getCategoryMappings().get(0).getRawValue());
            assertNull(response.getCategoryMappings().get(0).getSuggestedCode());
            assertEquals("none", response.getCategoryMappings().get(0).getSource());
        }
    }

    /**
     * 执行 previewMapping 并返回持久化的批次（品类测试公共前置）。
     */
    private ExcelImportBatch prepareCategoryBatch(byte[] excelBytes, String aiMappingJson) throws IOException {
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString())).thenReturn(aiMappingJson);
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            excelAiImportService.previewMapping(file);
        }
        return savedBatch;
    }

    private void stubCategoryDicts() {
        when(dictService.listByType("category")).thenReturn(List.of(
            createDict("category", "FS", "沙发"),
            createDict("category", "TB", "茶几")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());
    }

    @Test
    void getStatus_shouldReturnBatchStatus() {
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId("BATCH-TEST");
        batch.setFileName("test.xlsx");
        batch.setStatus("done");
        batch.setTotalRows(10);
        batch.setSuccessCount(9);
        batch.setFailedCount(1);
        batch.setFailures("[]");

        when(batchMapper.selectById("BATCH-TEST")).thenReturn(batch);

        ExcelAiImportStatusResponse response = excelAiImportService.getStatus("BATCH-TEST");

        assertEquals("BATCH-TEST", response.getBatchId());
        assertEquals(10, response.getTotalRows());
        assertEquals(9, response.getSuccessCount());
        assertEquals(1, response.getFailedCount());
    }

    @Test
    void confirmAndImport_shouldPreferVariantDisplayNameField() throws IOException {
        // P0-1：「规格/模块」列映射为 variantDisplayName 标准字段，不应被 productName 覆盖
        byte[] excelBytes = createExcelWithVariantNameColumn();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode,productName\",\"规格/模块\":\"variantDisplayName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithVariantNameColumn()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-1");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());

        // 变体名应取「规格/模块」列（模块A），而不是 productName
        ArgumentCaptor<com.rsdp.dto.request.RspuVariantCreateRequest> variantCaptor =
            ArgumentCaptor.forClass(com.rsdp.dto.request.RspuVariantCreateRequest.class);
        verify(rspuVariantService).createVariant(anyString(), variantCaptor.capture());
        assertEquals("模块A", variantCaptor.getValue().getDisplayName());

        // 复合「型号品名」仍应正确拆出型号
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertEquals("MJ-S96", rspuCaptor.getValue().getExternalCode());
    }

    @Test
    void confirmAndImport_shouldReuseVariantForSameDimensions() throws IOException {
        // P1-1：归组模块行尺寸/颜色/材质组合相同（价格列材质未入字典 → 全 NULL），
        // 第二行应复用已有变体继续建 RSKU，而不是重复创建触发唯一索引冲突
        byte[] excelBytes = createExcelWithTwoModuleRows();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode\",\"规格/模块\":\"variantDisplayName\",\"价格\":\"__PRICE__:A级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithTwoModuleRows()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);

        // 模拟唯一约束语义：已创建的变体可被 (rspuId,size,color,material) 查询命中
        List<RspuVariant> createdVariants = new ArrayList<>();
        when(rspuVariantService.createVariant(anyString(), any())).thenAnswer(inv -> {
            RspuVariant variant = new RspuVariant();
            variant.setVariantId("V-" + (createdVariants.size() + 1));
            createdVariants.add(variant);
            RspuVariantResponse resp = new RspuVariantResponse();
            resp.setVariantId(variant.getVariantId());
            return resp;
        });
        when(rspuVariantMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(createdVariants));
        when(rskuService.createRsku(any())).thenReturn("RSKU-1", "RSKU-2");

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(2, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        assertEquals(0, result.getFailedCount());

        // 两行只创建一个变体，两个 RSKU 都挂在该变体下
        verify(rspuVariantService, times(1)).createVariant(anyString(), any());
        ArgumentCaptor<com.rsdp.dto.request.RskuCreateRequest> rskuCaptor =
            ArgumentCaptor.forClass(com.rsdp.dto.request.RskuCreateRequest.class);
        verify(rskuService, times(2)).createRsku(rskuCaptor.capture());
        assertEquals("V-1", rskuCaptor.getAllValues().get(0).getVariantId());
        assertEquals("V-1", rskuCaptor.getAllValues().get(1).getVariantId());
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));
    }

    @Test
    void confirmAndImport_shouldExposeRskuFailureAndBackfillRskuIds() throws IOException {
        // P1-2：RSKU 创建失败不再静默吞掉——记入批次失败明细；成功的 RSKU 回填进行级结果
        byte[] excelBytes = createExcelWithMultiPriceColumns();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode,productName\",\"产品尺寸\":\"dimensions\",\"材质说明\":\"materialTags\",\"价格-A级布\":\"__PRICE__:A级布\",\"价格-AA级布\":\"__PRICE__:AA级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithMultiPriceColumns()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponseA = new RspuVariantResponse();
        variantResponseA.setVariantId("V-A");
        RspuVariantResponse variantResponseAA = new RspuVariantResponse();
        variantResponseAA.setVariantId("V-AA");
        when(rspuVariantService.createVariant(anyString(), any()))
            .thenReturn(variantResponseA, variantResponseAA);
        // 第一个价格列 RSKU 成功，第二个失败
        when(rskuService.createRsku(any()))
            .thenReturn("RSKU-1")
            .thenThrow(new BusinessException("工厂不存在: F001"));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setDefaultFactoryCode("F001");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "行本身仍成功: " + result.getFailures());
        assertEquals(0, result.getFailedCount(), "RSKU 部分失败不计入行失败数");
        assertEquals(1, result.getFailures().size(), "RSKU 失败必须暴露在失败明细中");
        assertTrue(result.getFailures().get(0).getReason().startsWith("工厂报价失败"),
            "失败原因: " + result.getFailures().get(0).getReason());

        // markSuccess 回填真实创建的 rskuIds（不再是空列表）
        verify(excelImportRowService).markSuccess(any(), any(), any(), eq(List.of("RSKU-1")), any(), any(), any());
    }

    @Test
    void confirmAndImport_shouldSkipWhenExternalCodeExistsAndUpdateDisabled() throws IOException {
        // P1-3：updateIfExists=false（默认）且 externalCode 已存在时，该行标记 skipped
        byte[] excelBytes = createExcelWithCodeAndName();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithCodeAndName()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-EXIST");
        existing.setExternalCode("ABC-001");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setUpdateIfExists(false);

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(1, result.getSkippedCount(), "已存在且未开启更新的行应计入跳过");
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
        verify(excelImportRowService).markSkipped(any(), org.mockito.ArgumentMatchers.contains("已存在"));
    }

    @Test
    void confirmAndImport_shouldReuseAndUpdateWhenUpdateIfExists() throws IOException {
        // P1-3：updateIfExists=true 且 externalCode 已存在时，复用已有 RSPU 并更新字段，继续建变体/RSKU
        byte[] excelBytes = createExcelWithCodeAndName();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号\":\"externalCode\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenReturn(new ByteArrayInputStream(createExcelWithCodeAndName()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-EXIST");
        existing.setExternalCode("ABC-001");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-EXIST");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setUpdateIfExists(true);

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        assertEquals(List.of("RSPU-EXIST"), result.getRspuIds(), "应复用已有 RSPU");
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
        verify(rspuMapper, times(1)).updateById(any(RspuMaster.class));
        verify(auditLogService, times(1)).logUpdate(eq("rspu_master"), eq("RSPU-EXIST"), any(), any(), any());
        verify(rspuVariantService, times(1)).createVariant(eq("RSPU-EXIST"), any());
    }

    @Test
    void confirmAndImport_shouldRejectWhenBatchClaimFails() {
        // P1-6：原子抢占失败（并发导入或已完成）→ 业务异常，不进入行处理
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId("BATCH-X");
        batch.setStatus("importing");
        when(batchMapper.selectById("BATCH-X")).thenReturn(batch);
        when(batchMapper.claimForImport("BATCH-X")).thenReturn(0);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId("BATCH-X");
        request.setMapping(Map.of("名称", "productName"));

        BusinessException e = assertThrows(BusinessException.class,
            () -> excelAiImportService.confirmAndImport(request));
        assertTrue(e.getMessage().contains("正在导入或已完成"), "异常信息: " + e.getMessage());
        verify(excelImportRowService, never()).initRow(anyString(), anyInt(), anyString(), anyMap(), any());
    }

    @Test
    void previewMapping_shouldFilterImageUrlMappingForImageKeywordHeaders() throws IOException {
        // P1-8：「产品图片」这类图片关键字表头同样识别为内嵌图片列，过滤掉 AI 误映射的 URL 字段
        byte[] excelBytes = createExcelWithImageKeywordHeader();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"产品图片\":\"primaryImageUrl\",\"名称\":\"productName\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            batch.setBatchId("BATCH-TEST");
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ExcelAiMappingResponse response = excelAiImportService.previewMapping(file);

            assertNotNull(response);
            assertEquals("productName", response.getSuggestedMapping().get("名称"));
            assertTrue(!response.getSuggestedMapping().containsKey("产品图片")
                    || response.getSuggestedMapping().get("产品图片") == null,
                "「产品图片」列不应被映射为 URL 字段");
        }
    }

    @Test
    void confirmAndImport_shouldSkipNonWebImageFormat() throws IOException {
        // P1-9：EMF/WMF/TIFF 等非 web 图片格式跳过不入库，并记入行失败明细
        byte[] excelBytes = createExcelWithEmfImage();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"mapping\":{\"型号品名\":\"externalCode\",\"规格/模块\":\"variantDisplayName\",\"价格\":\"__PRICE__:A级布\"},\"categoryGuess\":\"FS\",\"notes\":\"ok\"}");
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("excel-imports/BATCH-TEST.xlsx");

        ExcelImportBatch savedBatch = new ExcelImportBatch();
        when(batchMapper.insert(any(ExcelImportBatch.class))).thenAnswer(inv -> {
            ExcelImportBatch batch = inv.getArgument(0);
            savedBatch.setBatchId(batch.getBatchId());
            savedBatch.setStoragePath(batch.getStoragePath());
            savedBatch.setStatus(batch.getStatus());
            savedBatch.setPreviewRows(batch.getPreviewRows());
            savedBatch.setColumnMapping(batch.getColumnMapping());
            savedBatch.setPriceColumns(batch.getPriceColumns());
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get(anyString()))
            .thenAnswer(inv -> new ByteArrayInputStream(createExcelWithEmfImage()));
        when(dictService.listByType("category")).thenReturn(List.of(createDict("category", "FS", "沙发")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("scene")).thenReturn(List.of());
        when(dictService.listByType("material")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of());
        when(dictService.listByType("color")).thenReturn(List.of());
        when(dictService.listByType("factory_level")).thenReturn(List.of());

        when(rspuMapper.insert(any(RspuMaster.class))).thenReturn(1);
        RspuVariantResponse variantResponse = new RspuVariantResponse();
        variantResponse.setVariantId("V-A");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        ExcelAiMappingRequest request = new ExcelAiMappingRequest();
        request.setBatchId(preview.getBatchId());
        request.setMapping(preview.getSuggestedMapping());
        request.setCategoryHint("FS");
        request.setSelectedPriceColumns(preview.getPriceColumns().stream()
            .map(PriceColumnInfo::getHeader).toList());

        ExcelAiImportResult result = excelAiImportService.confirmAndImport(request);

        assertEquals(1, result.getSuccessCount(), "导入失败明细: " + result.getFailures());
        assertTrue(result.getFailures().stream()
                .anyMatch(f -> f.getReason() != null && f.getReason().startsWith("不支持的图片格式")),
            "失败明细应包含不支持格式说明: " + result.getFailures());
        verify(imageAssetsMapper, never()).insert(any(com.rsdp.entity.ImageAssets.class));
    }

    @Test
    void getAccessibleBatch_shouldEnforceOwnership() {
        // P1-12：批次归属校验——本人或平台 ADMIN 放行，其他用户 ForbiddenException
        ExcelImportBatch batch = new ExcelImportBatch();
        batch.setBatchId("B1");
        batch.setCreatedBy("user-1");
        when(batchMapper.selectById("B1")).thenReturn(batch);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(false);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-2");
            assertThrows(ForbiddenException.class, () -> excelAiImportService.getAccessibleBatch("B1"));

            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-1");
            assertSame(batch, excelAiImportService.getAccessibleBatch("B1"));

            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(true);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-2");
            assertSame(batch, excelAiImportService.getAccessibleBatch("B1"));
        }

        assertThrows(BusinessException.class, () -> excelAiImportService.getAccessibleBatch("B-MISSING"));
    }

    private byte[] createMinimalExcelBytes() {
        try (var out = new java.io.ByteArrayOutputStream()) {
            List<List<String>> data = List.of(
                List.of("品类", "名称", "风格"),
                List.of("FS", "休闲椅 A", "中古风")
            );
            com.alibaba.excel.EasyExcel.write(out).sheet("Sheet1").doWrite(data);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithEmptyHeader() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setBlank();
            header.createCell(1).setCellValue("名称");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("FS");
            data.createCell(1).setCellValue("休闲椅 A");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithImageColumn() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品图样\nPICTURE");
            header.createCell(1).setCellValue("型号");
            header.createCell(2).setCellValue("名称");
            var data = sheet.createRow(1);
            data.createCell(1).setCellValue("ABC-001");
            data.createCell(2).setCellValue("休闲椅 A");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithNoteRow() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("型号");
            header.createCell(1).setCellValue("名称");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            var note = sheet.createRow(2);
            note.createCell(0).setCellValue("产品下单说明：\n1.非标油漆颜色+改订费用百分之10");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithPriceParentSpan() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            // 第一行表头："价格（PRICE）"只写在首列
            var header1 = sheet.createRow(0);
            header1.createCell(0).setCellValue("型号");
            header1.createCell(1).setCellValue("名称");
            header1.createCell(2).setCellValue("价格（PRICE）");
            // 第二行表头：子表头
            var header2 = sheet.createRow(1);
            header2.createCell(2).setCellValue("A级布");
            header2.createCell(3).setCellValue("AA级布");
            // 数据行
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            data.createCell(2).setCellValue("1999");
            data.createCell(3).setCellValue("2399");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithLeadTimeColumn() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header1 = sheet.createRow(0);
            header1.createCell(0).setCellValue("型号");
            header1.createCell(1).setCellValue("名称");
            header1.createCell(2).setCellValue("价格（PRICE）");
            header1.createCell(4).setCellValue("交期");
            var header2 = sheet.createRow(1);
            header2.createCell(2).setCellValue("A级布");
            header2.createCell(3).setCellValue("AA级布");
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            data.createCell(2).setCellValue("1999");
            data.createCell(3).setCellValue("2399");
            data.createCell(4).setCellValue("30天");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithMergedCellsAndRepeatedHeader() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            // 表头行 1 + 表头行 2（价格子表头）
            var header1 = sheet.createRow(0);
            header1.createCell(0).setCellValue("型号品名 ITEM NO/DESCRIPTION");
            header1.createCell(1).setCellValue("规格/模块");
            header1.createCell(2).setCellValue("价格（PRICE）");
            header1.createCell(4).setCellValue("交期");
            var header2 = sheet.createRow(1);
            header2.createCell(2).setCellValue("A级布");
            header2.createCell(3).setCellValue("AA级布");
            // 数据行 1：MJ-S96 模块A
            var data1 = sheet.createRow(2);
            data1.createCell(0).setCellValue("MJ-S96");
            data1.createCell(1).setCellValue("模块A");
            data1.createCell(2).setCellValue("8720");
            data1.createCell(3).setCellValue("9500");
            data1.createCell(4).setCellValue("30日");
            // 数据行 2：型号列为空（纵向合并单元格），AA级布价格为空
            var data2 = sheet.createRow(3);
            data2.createCell(1).setCellValue("模块B");
            data2.createCell(2).setCellValue("9780");
            data2.createCell(4).setCellValue("25日");
            // 重复表头行 1 + 2（品类区块分隔处重复出现的表头）
            var repeatHeader1 = sheet.createRow(4);
            repeatHeader1.createCell(0).setCellValue("型号品名 ITEM NO/DESCRIPTION");
            repeatHeader1.createCell(1).setCellValue("规格/模块");
            repeatHeader1.createCell(2).setCellValue("价格（PRICE）");
            var repeatHeader2 = sheet.createRow(5);
            repeatHeader2.createCell(2).setCellValue("A级布");
            repeatHeader2.createCell(3).setCellValue("AA级布");
            // 数据行 3：MJ-S97
            var data3 = sheet.createRow(6);
            data3.createCell(0).setCellValue("MJ-S97");
            data3.createCell(1).setCellValue("模块A");
            data3.createCell(2).setCellValue("5000");
            data3.createCell(3).setCellValue("5500");
            data3.createCell(4).setCellValue("30日");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithImageOnDataRow() {
        // 1x1 透明 PNG
        byte[] pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header1 = sheet.createRow(0);
            header1.createCell(0).setCellValue("型号");
            header1.createCell(1).setCellValue("名称");
            header1.createCell(2).setCellValue("价格（PRICE）");
            var header2 = sheet.createRow(1);
            header2.createCell(2).setCellValue("A级布");
            header2.createCell(3).setCellValue("AA级布");
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            data.createCell(2).setCellValue("1999");
            data.createCell(3).setCellValue("2399");
            // 浮动图片锚定在数据行（物理行 2）
            int picIdx = workbook.addPicture(pngBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
            var helper = workbook.getCreationHelper();
            var drawing = sheet.createDrawingPatriarch();
            var anchor = helper.createClientAnchor();
            anchor.setRow1(2);
            anchor.setCol1(0);
            anchor.setRow2(3);
            anchor.setCol2(1);
            drawing.createPicture(anchor, picIdx);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithModuleImagesAndLogo() {
        // PNG1：产品主图（行 1 图片列）；PNG2：模块图（行 2 图片列）；PNG3：logo（行 2 数据区域外）
        // 注意：col0 是无表头的空列，图片列在 col1——验证图片列索引用物理列而非 keySet 位置
        // （previewRows 经 jsonb 存储后键序乱序，位置推断会错位）
        byte[] pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("产品图样 PICTURE");
            header.createCell(2).setCellValue("型号品名");
            header.createCell(3).setCellValue("规格/模块");
            header.createCell(4).setCellValue("价格（PRICE）");
            var data1 = sheet.createRow(1);
            data1.createCell(2).setCellValue("MJ-S96");
            data1.createCell(3).setCellValue("模块A");
            data1.createCell(4).setCellValue("8720");
            // 行 2：型号列为空（纵向合并单元格）
            var data2 = sheet.createRow(2);
            data2.createCell(3).setCellValue("模块B");
            data2.createCell(4).setCellValue("9780");

            var helper = workbook.getCreationHelper();
            var drawing = sheet.createDrawingPatriarch();
            // PNG1 锚定：行 1 图片列（col1）
            var anchor1 = helper.createClientAnchor();
            anchor1.setRow1(1); anchor1.setCol1(1); anchor1.setRow2(2); anchor1.setCol2(2);
            drawing.createPicture(anchor1, workbook.addPicture(pngBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            // PNG2 锚定：行 2 图片列（col1）
            var anchor2 = helper.createClientAnchor();
            anchor2.setRow1(2); anchor2.setCol1(1); anchor2.setRow2(3); anchor2.setCol2(2);
            drawing.createPicture(anchor2, workbook.addPicture(pngBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            // PNG3 锚定：行 2 数据区域外（col6，模拟 logo/二维码）
            var anchor3 = helper.createClientAnchor();
            anchor3.setRow1(2); anchor3.setCol1(6); anchor3.setRow2(3); anchor3.setCol2(7);
            drawing.createPicture(anchor3, workbook.addPicture(pngBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithModuleOnlyImage() {
        // 有图片列表头（col1），但唯一一张图锚在「规格/模块」列（col3）——模块样式示例图
        byte[] pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("产品图样 PICTURE");
            header.createCell(2).setCellValue("型号品名");
            header.createCell(3).setCellValue("规格/模块");
            header.createCell(4).setCellValue("价格（PRICE）");
            var data1 = sheet.createRow(1);
            data1.createCell(2).setCellValue("MJ-S96");
            data1.createCell(3).setCellValue("模块A");
            data1.createCell(4).setCellValue("8720");

            var helper = workbook.getCreationHelper();
            var drawing = sheet.createDrawingPatriarch();
            // 图锚定：行 1 规格/模块列（col3），图片列（col1）无图
            var anchor = helper.createClientAnchor();
            anchor.setRow1(1); anchor.setCol1(3); anchor.setRow2(2); anchor.setCol2(4);
            drawing.createPicture(anchor, workbook.addPicture(pngBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithMultiStyle() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("型号");
            header.createCell(1).setCellValue("名称");
            header.createCell(2).setCellValue("风格");
            header.createCell(3).setCellValue("价格（PRICE）");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            data.createCell(2).setCellValue("中古风/奶油风");
            data.createCell(3).setCellValue("1999");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithMultiPriceColumns() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            // 第一行表头
            var header1 = sheet.createRow(0);
            header1.createCell(0).setCellValue("型号品名 ITEM NO/DESCRIPTION");
            header1.createCell(1).setCellValue("产品尺寸(厘米) SIZE（CM）");
            header1.createCell(2).setCellValue("材质说明 SIZE");
            header1.createCell(3).setCellValue("价格（PRICE）");
            header1.createCell(4).setCellValue("价格（PRICE）");
            // 第二行表头（子表头）
            var header2 = sheet.createRow(1);
            header2.createCell(3).setCellValue("A级布");
            header2.createCell(4).setCellValue("AA级布");
            // 数据行
            var data = sheet.createRow(2);
            data.createCell(0).setCellValue("ABC-001 休闲椅A");
            data.createCell(1).setCellValue("800*900*1000mm");
            data.createCell(2).setCellValue("A级布/实木框架");
            data.createCell(3).setCellValue("1999");
            data.createCell(4).setCellValue("2399");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithVariantNameColumn() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("型号品名");
            header.createCell(1).setCellValue("规格/模块");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("MJ-S96 休闲椅A");
            data.createCell(1).setCellValue("模块A");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithTwoModuleRows() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("型号品名");
            header.createCell(1).setCellValue("规格/模块");
            header.createCell(2).setCellValue("价格（PRICE）");
            var data1 = sheet.createRow(1);
            data1.createCell(0).setCellValue("MJ-S96");
            data1.createCell(1).setCellValue("模块A");
            data1.createCell(2).setCellValue("8720");
            // 行 2：型号列为空（纵向合并单元格语义，继承上一行型号）
            var data2 = sheet.createRow(2);
            data2.createCell(1).setCellValue("模块B");
            data2.createCell(2).setCellValue("9780");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithCodeAndName() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("型号");
            header.createCell(1).setCellValue("名称");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("ABC-001");
            data.createCell(1).setCellValue("休闲椅 A");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithImageKeywordHeader() {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品图片");
            header.createCell(1).setCellValue("名称");
            var data = sheet.createRow(1);
            data.createCell(1).setCellValue("休闲椅 A");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithEmfImage() {
        // 伪造 EMF 内容（POI 不校验图片字节内容），锚定在图片列数据行
        byte[] emfBytes = new byte[]{0x01, 0x00, 0x00, 0x00, 0x58, 0x02, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04};
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品图样 PICTURE");
            header.createCell(1).setCellValue("型号品名");
            header.createCell(2).setCellValue("规格/模块");
            header.createCell(3).setCellValue("价格（PRICE）");
            var data = sheet.createRow(1);
            data.createCell(1).setCellValue("MJ-S96");
            data.createCell(2).setCellValue("模块A");
            data.createCell(3).setCellValue("8720");

            var helper = workbook.getCreationHelper();
            var drawing = sheet.createDrawingPatriarch();
            var anchor = helper.createClientAnchor();
            anchor.setRow1(1);
            anchor.setCol1(0);
            anchor.setRow2(2);
            anchor.setCol2(1);
            drawing.createPicture(anchor,
                workbook.addPicture(emfBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_EMF));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createExcelWithCategoryValues(String... categoryValues) {
        try (var out = new java.io.ByteArrayOutputStream();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("品类");
            header.createCell(1).setCellValue("名称");
            for (int i = 0; i < categoryValues.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(categoryValues[i]);
                row.createCell(1).setCellValue("产品" + (i + 1));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private java.util.List<java.util.Map<Integer, String>> readExcelRaw(byte[] bytes) {
        java.util.List<java.util.Map<Integer, String>> rows = new java.util.ArrayList<>();
        try (java.io.InputStream stream = new java.io.ByteArrayInputStream(bytes)) {
            com.alibaba.excel.EasyExcel.read(stream, new com.alibaba.excel.read.listener.ReadListener<java.util.Map<Integer, String>>() {
                @Override
                public void invoke(java.util.Map<Integer, String> row, com.alibaba.excel.context.AnalysisContext context) {
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext context) {
                }
            }).sheet().headRowNumber(0).doRead();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    private CategoryDict createDict(String dictType, String dictCode, String dictName) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType(dictType);
        dict.setDictCode(dictCode);
        dict.setDictName(dictName);
        return dict;
    }

    @org.junit.jupiter.api.Test
    void parsePrice_shouldHandleMultiLineCells() throws Exception {
        java.lang.reflect.Method m = ExcelAiImportService.class.getDeclaredMethod("parsePrice", String.class);
        m.setAccessible(true);

        // 多行单元格「价格\n备注」取首行数字
        assertEquals(new java.math.BigDecimal("4700"), m.invoke(excelAiImportService, "4700\n特惠价"));
        assertEquals(new java.math.BigDecimal("3000"), m.invoke(excelAiImportService, "3000\n元/平方"));
        assertEquals(new java.math.BigDecimal("8000"), m.invoke(excelAiImportService, "8000\n左+右"));
        // 常规格式兼容
        assertEquals(new java.math.BigDecimal("3000"), m.invoke(excelAiImportService, "¥3,000"));
        assertEquals(new java.math.BigDecimal("2450.5"), m.invoke(excelAiImportService, "2450.5"));
        // 首行无数字（纯备注）返回 null
        assertNull(m.invoke(excelAiImportService, "元/平方"));
        assertNull(m.invoke(excelAiImportService, "特惠价"));
    }

    @org.junit.jupiter.api.Test
    void fuzzyMatchStandardField_shouldMapPriceHeadersToPriceColumnNotReferencePriceBand() throws Exception {
        java.lang.reflect.Method m = ExcelAiImportService.class.getDeclaredMethod("fuzzyMatchStandardField", String.class);
        m.setAccessible(true);

        // 价格类表头走价格列通道，不再误映射为参考价格带
        assertEquals("__PRICE__:出厂价", m.invoke(excelAiImportService, "出厂价"));
        assertEquals("__PRICE__:销售价", m.invoke(excelAiImportService, "销售价"));
        assertEquals("__PRICE__:价格", m.invoke(excelAiImportService, "价格"));
        // 价格带类表头仍映射参考价格带
        assertEquals("referencePriceBand", m.invoke(excelAiImportService, "价格带"));
    }

    @org.junit.jupiter.api.Test
    void forwardFillKeyColumns_shouldAlsoFillCategoryCode() throws Exception {
        java.lang.reflect.Method m = ExcelAiImportService.class.getDeclaredMethod(
            "forwardFillKeyColumns", java.util.List.class, java.util.Map.class);
        m.setAccessible(true);

        java.util.Map<String, String> mapping = java.util.Map.of(
            "型号", "externalCode", "类别", "categoryCode");
        java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        rows.add(new java.util.HashMap<>(java.util.Map.of("型号", "A001", "类别", "茶桌")));
        rows.add(new java.util.HashMap<>(java.util.Map.of("型号", "", "类别", "")));

        m.invoke(excelAiImportService, rows, mapping);

        // 合并单元格语义的后续空行应继承上一行的型号与品类
        assertEquals("A001", rows.get(1).get("型号"));
        assertEquals("茶桌", rows.get(1).get("类别"));
    }
}
