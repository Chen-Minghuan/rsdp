package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 导入：品类 Preview Edit 命令的物化结果（P0-B2）。
 *
 * <p>返回新 Revision 号与后端按 §26.1 收敛兜底链计算的每行最终品类
 * （行级选择 → 行内类别列确定性归一 → SINGLE 默认品类），前端以响应为准同步本地状态。</p>
 */
@Data
public class ExcelAiCategoryRevisionResponse {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 新创建的 Preview Revision 号。
     */
    private Long previewRevision;

    /**
     * 逐行最终品类（覆盖全部非系统过滤行；finalCategoryCode 为 null 表示仍未确定）。
     */
    private List<RowFinalCategory> rows = new ArrayList<>();

    /**
     * 单行最终品类。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowFinalCategory {

        /**
         * Excel 物理行号（1-based），与预览/失败明细行号口径一致。
         */
        private int rowIndex;

        /**
         * 物化进 Snapshot 的最终品类字典码；未确定为 null。
         */
        private String finalCategoryCode;
    }
}
