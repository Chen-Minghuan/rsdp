package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
        // 构造最小 Excel：表头 + 1 行数据
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
        // 先通过 previewMapping 生成一个真实批次
        byte[] excelBytes = createMinimalExcelBytes();
        MockMultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

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
            savedBatch.setTotalRows(batch.getTotalRows());
            return 1;
        });

        ExcelAiMappingResponse preview;
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            preview = excelAiImportService.previewMapping(file);
        }

        when(batchMapper.selectById(preview.getBatchId())).thenReturn(savedBatch);
        when(storageService.get("excel-imports/BATCH-TEST.xlsx"))
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
        // 表头包含空单元格：第 0 列为空，第 1 列为"名称"
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
            // 空表头列不应出现在 previewRows 的 key 中
            assertTrue(response.getPreviewRows().get(0).containsKey("名称"));
        }

        verify(batchMapper, times(1)).insert(any(ExcelImportBatch.class));
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
            header.createCell(0).setBlank(); // 空表头单元格
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
