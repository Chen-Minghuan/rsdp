package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ExcelImportRow;
import com.rsdp.mapper.ExcelImportRowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelImportRowService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportRowServiceTest {

    @Mock
    private ExcelImportRowMapper rowMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ExcelImportRowService rowService;

    @Test
    void initRow_shouldInsertAndReturnId() {
        Map<String, String> rawData = Map.of("型号", "ABC-001", "名称", "休闲椅 A");

        when(rowMapper.insert(any(ExcelImportRow.class))).thenAnswer(inv -> {
            ExcelImportRow row = inv.getArgument(0);
            row.setRowId(1L);
            return 1;
        });

        Long id = rowService.initRow("BATCH-001", 2, "product", rawData, null);

        assertThat(id).isEqualTo(1L);
        ArgumentCaptor<ExcelImportRow> captor = ArgumentCaptor.forClass(ExcelImportRow.class);
        verify(rowMapper, times(1)).insert(captor.capture());
        ExcelImportRow saved = captor.getValue();
        assertThat(saved.getBatchId()).isEqualTo("BATCH-001");
        assertThat(saved.getExcelRowNumber()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getRawData()).contains("ABC-001");
    }

    @Test
    void updateStage_shouldUpdateWhenRowExists() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);
        row.setStatus("pending");

        when(rowMapper.selectById(1L)).thenReturn(row);

        rowService.updateStage(1L, "create_rspu");

        assertThat(row.getProcessingStage()).isEqualTo("create_rspu");
        verify(rowMapper, times(1)).updateById(row);
    }

    @Test
    void updateStage_shouldIgnoreWhenRowNotExists() {
        when(rowMapper.selectById(1L)).thenReturn(null);

        rowService.updateStage(1L, "create_rspu");

        verify(rowMapper, times(0)).updateById(any(ExcelImportRow.class));
    }

    @Test
    void recordMapping_shouldSerializeMappedFields() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);
        Map<String, String> mappedFields = Map.of("名称", "productName");
        List<String> selectedPriceColumns = List.of("价格-A级布");

        when(rowMapper.selectById(1L)).thenReturn(row);

        rowService.recordMapping(1L, mappedFields, selectedPriceColumns);

        assertThat(row.getMappedFields()).contains("productName");
        assertThat(row.getSelectedPriceColumns()).contains("价格-A级布");
        verify(rowMapper, times(1)).updateById(row);
    }

    @Test
    void markSuccess_shouldUpdateStatusAndGeneratedIds() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);

        when(rowMapper.selectById(1L)).thenReturn(row);

        rowService.markSuccess(1L, "RSPU-001", "VAR-001", List.of("RSKU-001", "RSKU-002"),
            2, List.of("IMG-001"), "TASK-001");

        assertThat(row.getStatus()).isEqualTo("success");
        assertThat(row.getGeneratedRspuId()).isEqualTo("RSPU-001");
        assertThat(row.getGeneratedVariantId()).isEqualTo("VAR-001");
        assertThat(row.getGeneratedRskuIds()).contains("RSKU-001");
        assertThat(row.getExtractedImageCount()).isEqualTo(2);
        assertThat(row.getAiTaskId()).isEqualTo("TASK-001");
        verify(rowMapper, times(1)).updateById(row);
    }

    @Test
    void markFailed_shouldUpdateStatusAndReason() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);

        when(rowMapper.selectById(1L)).thenReturn(row);

        rowService.markFailed(1L, "validate", "品类码无效");

        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getFailureStage()).isEqualTo("validate");
        assertThat(row.getFailureReason()).isEqualTo("品类码无效");
        verify(rowMapper, times(1)).updateById(row);
    }

    @Test
    void markSkipped_shouldUpdateStatusAndReason() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);

        when(rowMapper.selectById(1L)).thenReturn(row);

        rowService.markSkipped(1L, "说明或空行");

        assertThat(row.getStatus()).isEqualTo("skipped");
        assertThat(row.getFailureReason()).isEqualTo("说明或空行");
        verify(rowMapper, times(1)).updateById(row);
    }

    @Test
    void listByBatch_shouldReturnRows() {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);
        row.setBatchId("BATCH-001");

        when(rowMapper.selectByBatchId("BATCH-001")).thenReturn(List.of(row));

        List<ExcelImportRow> result = rowService.listByBatch("BATCH-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBatchId()).isEqualTo("BATCH-001");
    }

    @Test
    void deleteByBatch_shouldDeleteAllRowsOfBatch() {
        // V18：done 批次重新导入前整体清理上一轮行级记录
        rowService.deleteByBatch("BATCH-001");

        verify(rowMapper, times(1)).deleteByBatchId("BATCH-001");
    }
}
