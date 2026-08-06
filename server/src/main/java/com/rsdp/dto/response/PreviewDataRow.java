package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel AI 导入「导入前全量预览」中的单行数据。
 *
 * <p>以原始表头为视角展示，避免复合映射列（如「型号品名」同时映射到
 * externalCode 与 productName）编辑时产生歧义。</p>
 */
@Data
public class PreviewDataRow {

    /**
     * Excel 物理行号（1-based），与导入失败明细中的 rowIndex 口径一致。
     */
    private int rowIndex;

    /**
     * 原始表头 → 单元格值（按列顺序）。
     */
    private Map<String, String> rawValues = new LinkedHashMap<>();

    /**
     * 原始表头 → 系统字段（用于 UI 展示映射关系；未映射列为 null）。
     */
    private Map<String, String> mappedFieldByHeader = new LinkedHashMap<>();

    /**
     * 该行在 Excel 中的内嵌图片信息（轻量元数据，不含 Base64），
     * 用于数据清洗页按行懒加载缩略图，避免大文件预览超时。
     */
    private List<PreviewRowImage> images = new ArrayList<>();

    /**
     * 预览行图片信息。
     */
    @Data
    public static class PreviewRowImage {
        /**
         * 图片唯一键，格式 sheetIndex,physicalRowIndex,colIndex,imageIndex。
         */
        private String imageKey;

        /**
         * 图片所在原始表头列。
         */
        private String columnHeader;

        /**
         * Base64 缩略图，可直接用于 img src；getPreviewData 返回时为 null，按行加载接口再填充。
         */
        private String thumbnailBase64;

        /**
         * 是否为主图候选（通常为产品图样列）。
         */
        private boolean primaryCandidate;

        /**
         * 原始图片大小（字节），用于 UI 提示。
         */
        private long byteSize;
    }
}
