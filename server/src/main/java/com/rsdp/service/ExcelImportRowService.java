package com.rsdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ExcelImportRow;
import com.rsdp.mapper.ExcelImportRowMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel 行级导入记录服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportRowService {

    private final ExcelImportRowMapper rowMapper;
    private final ObjectMapper objectMapper;

    /**
     * 初始化一行导入记录。
     *
     * @param batchId        批次 ID
     * @param excelRowNumber Excel 行号
     * @param rowType        行类型
     * @param rawData        原始数据
     * @param parentRowId    父行 ID
     * @return 行记录 ID
     */
    @Transactional
    public Long initRow(String batchId, int excelRowNumber, String rowType,
                        Map<String, String> rawData, Long parentRowId) {
        ExcelImportRow row = new ExcelImportRow();
        row.setBatchId(batchId);
        row.setExcelRowNumber(excelRowNumber);
        row.setRowType(rowType);
        row.setParentRowId(parentRowId);
        row.setRawData(toJson(rawData));
        row.setStatus("pending");
        row.setCreatedAt(LocalDateTime.now());
        rowMapper.insert(row);
        return row.getRowId();
    }

    /**
     * 更新行处理阶段。
     */
    @Transactional
    public void updateStage(Long rowId, String stage) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setProcessingStage(stage);
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 记录 AI 映射字段和价格列。
     */
    @Transactional
    public void recordMapping(Long rowId, Map<String, String> mappedFields, List<String> selectedPriceColumns) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setMappedFields(toJson(mappedFields));
        row.setSelectedPriceColumns(toJson(selectedPriceColumns));
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 标记行处理成功并记录生成的实体。
     */
    @Transactional
    public void markSuccess(Long rowId, String rspuId, String variantId, List<String> rskuIds,
                            Integer imageCount, List<String> imageAssetIds, String aiTaskId) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setStatus("success");
        row.setGeneratedRspuId(rspuId);
        row.setGeneratedVariantId(variantId);
        row.setGeneratedRskuIds(toJson(rskuIds));
        row.setExtractedImageCount(imageCount != null ? imageCount : 0);
        row.setImageAssetIds(toJson(imageAssetIds));
        row.setAiTaskId(aiTaskId);
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 标记行处理失败。
     */
    @Transactional
    public void markFailed(Long rowId, String stage, String reason) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setStatus("failed");
        row.setFailureStage(stage);
        row.setFailureReason(reason);
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 标记行被跳过。
     */
    @Transactional
    public void markSkipped(Long rowId, String reason) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setStatus("skipped");
        row.setFailureReason(reason);
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 查询批次下所有行记录。
     */
    public List<ExcelImportRow> listByBatch(String batchId) {
        return rowMapper.selectByBatchId(batchId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化 Excel 导入行数据失败", e);
            return "[]";
        }
    }
}
