package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 导入「导入前全量预览」响应。
 */
@Data
public class ExcelAiPreviewDataResponse {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 总行数。
     */
    private int totalRows;

    /**
     * 原始表头列表（按列顺序）。
     */
    private List<String> headers = new ArrayList<>();

    /**
     * 商品级字段原始表头（用于右侧商品级表单）。
     */
    private List<String> productHeaders = new ArrayList<>();

    /**
     * 变体级字段原始表头（用于变体表格列）。
     */
    private List<String> variantHeaders = new ArrayList<>();

    /**
     * 全量数据行（兼容旧接口，保留到前端重构完成后再移除）。
     */
    private List<PreviewDataRow> rows = new ArrayList<>();

    /**
     * 按商品（externalCode，供应商源商品编码/型号）聚合后的商品组列表。
     */
    private List<PreviewDataGroup> groups = new ArrayList<>();

    /**
     * 批次当前 Preview Revision 指针（P0-B2；尚无快照的旧批次为 null），
     * 前端恢复清洗页上下文时作为后续编辑命令的 expectedRevision 基准。
     */
    private Long previewRevision;
}
