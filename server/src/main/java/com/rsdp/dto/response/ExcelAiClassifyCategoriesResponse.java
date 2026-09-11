package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 导入：行级品类预分类响应。
 *
 * <p>建议值仅作前端清洗页预填与「AI 建议」标记展示（suggested 语义），
 * 不回写后端；最终品类以确认导入请求中的 rowCategorySelections 为准。</p>
 */
@Data
public class ExcelAiClassifyCategoriesResponse {

    /**
     * 逐行品类建议（覆盖全部数据行，含系统过滤行标记）。
     */
    private List<RowCategorySuggestion> suggestions = new ArrayList<>();

    /**
     * 单行品类建议。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowCategorySuggestion {

        /**
         * Excel 物理行号（1-based），与预览/失败明细行号口径一致。
         */
        private int rowIndex;

        /**
         * 是否为系统过滤行（说明行/重复表头行/组合汇总价行）。
         * 此类行导入时自动跳过，无需确定品类，也不计入「未确定」统计。
         */
        private boolean filtered;

        /**
         * 建议品类码（行内类别列确定性归一命中 / 候选集内 AI 推荐 / SINGLE 默认品类）；
         * 未识别为 null。
         */
        private String suggestedCategoryCode;

        /**
         * 建议来源：dict=行内类别列确定性归一（含用户确认映射/字典/别名）；
         * ai=候选集约束的 AI 推荐；default=SINGLE 默认品类兜底；none=未识别。
         */
        private String source;
    }
}
