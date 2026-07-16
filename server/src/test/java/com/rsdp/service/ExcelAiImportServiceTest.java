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
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(excelImportRowService.initRow(anyString(), anyInt(), anyString(), anyMap(), any()))
            .thenAnswer(inv -> System.nanoTime());
        lenient().when(factoryLeadTimeRuleService.calculateLeadTime(anyString(), any(), any(), anyString(), anyInt()))
            .thenReturn(null);
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

        // 组内 AI 识别任务只建一次（基于组主图）
        verify(asyncTaskMapper, times(1)).insert(any(com.rsdp.entity.AsyncTask.class));
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
}
