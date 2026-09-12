package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
        return initRow(batchId, excelRowNumber, rowType, rawData, parentRowId, null, null);
    }

    /**
     * 初始化一行导入记录，并同时记录 AI 映射字段与选中的价格列。
     *
     * @param batchId             批次 ID
     * @param excelRowNumber      Excel 行号
     * @param rowType             行类型
     * @param rawData             原始数据
     * @param parentRowId         父行 ID
     * @param mappedFields        表头 → 系统字段的映射（可选）
     * @param selectedPriceColumns 选中的价格列原始表头列表（可选）
     * @return 行记录 ID
     */
    @Transactional
    public Long initRow(String batchId, int excelRowNumber, String rowType,
                        Map<String, String> rawData, Long parentRowId,
                        Map<String, String> mappedFields, List<String> selectedPriceColumns) {
        ExcelImportRow row = new ExcelImportRow();
        row.setBatchId(batchId);
        row.setExcelRowNumber(excelRowNumber);
        row.setRowType(rowType);
        row.setParentRowId(parentRowId);
        row.setRawData(toJson(rawData));
        row.setMappedFields(toJson(mappedFields));
        row.setSelectedPriceColumns(toJson(selectedPriceColumns));
        row.setStatus("pending");
        row.setCreatedAt(LocalDateTime.now());
        rowMapper.insert(row);
        return row.getRowId();
    }

    /**
     * 更新行处理阶段。
     *
     * <p>3.4 起改为按 rowId 单语句 UPDATE（不再先 selectById 再 updateById）：
     * 行不存在时 UPDATE 命中 0 行，与原有"查不到直接返回"语义一致；
     * 行级写路径在 500 行批次下由 2 条 SQL/次降为 1 条。</p>
     */
    @Transactional
    public void updateStage(Long rowId, String stage) {
        rowMapper.update(null, new UpdateWrapper<ExcelImportRow>()
            .eq("row_id", rowId)
            .set("processing_stage", stage)
            .set("updated_at", LocalDateTime.now()));
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
     *
     * <p>单语句 UPDATE（updateById 默认非空字段策略：null 字段不写入）。
     * 注意：generated_rsku_ids/image_asset_ids 为 jsonb 列，必须走实体的
     * {@code JsonbTypeHandler}；UpdateWrapper.set 会以 varchar 写入触发
     * PSQLException（2026-09-12 冒烟实测坐实，Mock 单测无法覆盖真实类型转换）。</p>
     */
    @Transactional
    public void markSuccess(Long rowId, String rspuId, String variantId, List<String> rskuIds,
                            Integer imageCount, List<String> imageAssetIds, String aiTaskId) {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(rowId);
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
     * 标记行处理失败（单语句 UPDATE，null 字段不写入，语义同原 selectById + updateById）。
     */
    @Transactional
    public void markFailed(Long rowId, String stage, String reason) {
        rowMapper.update(null, new UpdateWrapper<ExcelImportRow>()
            .eq("row_id", rowId)
            .set("status", "failed")
            .set(stage != null, "failure_stage", stage)
            .set(reason != null, "failure_reason", reason)
            .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 标记行被跳过（单语句 UPDATE，null 字段不写入，语义同原 selectById + updateById）。
     */
    @Transactional
    public void markSkipped(Long rowId, String reason) {
        rowMapper.update(null, new UpdateWrapper<ExcelImportRow>()
            .eq("row_id", rowId)
            .set("status", "skipped")
            .set(reason != null, "failure_reason", reason)
            .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 创建一条仅用于在数据清洗阶段保存图片覆盖的占位行。
     *
     * <p>导入主流程开始时会先删除该批次全部行记录并重建，届时会通过
     * {@link #updateImageOverrides} 把覆盖图恢复到新的正式行记录中。</p>
     *
     * @param batchId        批次 ID
     * @param excelRowNumber Excel 物理行号（1-based）
     * @return 新建行记录 ID
     */
    @Transactional
    public Long createPreviewPlaceholderRow(String batchId, int excelRowNumber) {
        ExcelImportRow row = new ExcelImportRow();
        row.setBatchId(batchId);
        row.setExcelRowNumber(excelRowNumber);
        row.setRowType("preview_placeholder");
        row.setRawData("{}");
        row.setStatus("pending");
        row.setCreatedAt(LocalDateTime.now());
        rowMapper.insert(row);
        return row.getRowId();
    }

    /**
     * 按批次 + Excel 行号查询唯一行记录。
     *
     * @param batchId        批次 ID
     * @param excelRowNumber Excel 物理行号（1-based）
     * @return 行记录；不存在时为 null
     */
    public ExcelImportRow findByBatchAndRowNumber(String batchId, int excelRowNumber) {
        return rowMapper.selectByBatchIdAndRowNumber(batchId, excelRowNumber);
    }

    /**
     * 按主键查询行记录。
     */
    public ExcelImportRow findById(Long rowId) {
        if (rowId == null) {
            return null;
        }
        return rowMapper.selectById(rowId);
    }

    /**
     * 更新行级用户覆盖图片列表。
     *
     * @param rowId            行记录 ID
     * @param imageAssetIds    用户指定的图片 asset ID 列表；null/空表示清空覆盖
     */
    @Transactional
    public void updateImageOverrides(Long rowId, List<String> imageAssetIds) {
        ExcelImportRow row = rowMapper.selectById(rowId);
        if (row == null) {
            return;
        }
        row.setOverrideImageAssetIds(toJson(imageAssetIds));
        row.setUpdatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 查询批次下所有行记录。
     */
    public List<ExcelImportRow> listByBatch(String batchId) {
        return rowMapper.selectByBatchId(batchId);
    }

    /**
     * 清理批次下全部行级记录。
     *
     * <p>done 批次「以更新模式重新导入」时调用：整体删除比逐行覆盖更简单安全——
     * 行记录本质是上一轮导入的结果快照（含唯一约束 batch_id+excel_row_number），
     * 重新导入会按同一批物理行号重建，旧记录无保留价值。</p>
     *
     * @param batchId 批次 ID
     */
    @Transactional
    public void deleteByBatch(String batchId) {
        rowMapper.deleteByBatchId(batchId);
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
