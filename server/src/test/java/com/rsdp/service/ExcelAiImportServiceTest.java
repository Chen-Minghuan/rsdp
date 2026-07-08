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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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
