package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import static org.mockito.Mockito.never;
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
    void updateStage_shouldIssueSingleUpdateWithoutSelect() {
        // 3.4：行级写放大治理——updateStage 改为按 rowId 单语句 UPDATE，不再先 selectById
        rowService.updateStage(1L, "create_rspu");

        verify(rowMapper, never()).selectById(any(Long.class));
        ArgumentCaptor<UpdateWrapper<ExcelImportRow>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(rowMapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        UpdateWrapper<ExcelImportRow> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("processing_stage").contains("updated_at");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("create_rspu");
    }

    @Test
    void updateStage_shouldNotFailWhenRowMissing() {
        // 行不存在时 UPDATE 命中 0 行，与原"查不到直接返回"语义一致
        when(rowMapper.update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class))).thenReturn(0);

        rowService.updateStage(1L, "create_rspu");

        verify(rowMapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
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
        // 3.4：单语句 UPDATE（不再 selectById + updateById），null 字段条件 set 语义不变
        rowService.markSuccess(1L, "RSPU-001", "VAR-001", List.of("RSKU-001", "RSKU-002"),
            2, List.of("IMG-001"), "TASK-001");

        verify(rowMapper, never()).selectById(any(Long.class));
        ArgumentCaptor<UpdateWrapper<ExcelImportRow>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(rowMapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        UpdateWrapper<ExcelImportRow> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet())
            .contains("status").contains("generated_rspu_id").contains("generated_variant_id")
            .contains("generated_rsku_ids").contains("extracted_image_count").contains("ai_task_id");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue("success").containsValue("RSPU-001").containsValue("VAR-001")
            .containsValue("TASK-001").containsValue(2);
    }

    @Test
    void markFailed_shouldUpdateStatusAndReason() {
        rowService.markFailed(1L, "validate", "品类码无效");

        verify(rowMapper, never()).selectById(any(Long.class));
        ArgumentCaptor<UpdateWrapper<ExcelImportRow>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(rowMapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        UpdateWrapper<ExcelImportRow> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status").contains("failure_stage").contains("failure_reason");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue("failed").containsValue("validate").containsValue("品类码无效");
    }

    @Test
    void markSkipped_shouldUpdateStatusAndReason() {
        rowService.markSkipped(1L, "说明或空行");

        verify(rowMapper, never()).selectById(any(Long.class));
        ArgumentCaptor<UpdateWrapper<ExcelImportRow>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(rowMapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        UpdateWrapper<ExcelImportRow> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status").contains("failure_reason");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue("skipped").containsValue("说明或空行");
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
