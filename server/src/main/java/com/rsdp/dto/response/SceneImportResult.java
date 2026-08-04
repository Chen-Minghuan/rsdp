package com.rsdp.dto.response;

import com.rsdp.dto.ProductBoundingBox;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景图拆分录入批次结果。
 */
@Data
public class SceneImportResult {

    /** 批次 ID。 */
    private String batchId;

    /** AI 检测到的家具单品总数。 */
    private int totalProducts;

    /** 成功建档数量。 */
    private int successCount;

    /** 失败数量。 */
    private int failedCount;

    /** 逐件明细。 */
    private List<SceneImportProduct> products = new ArrayList<>();

    /**
     * 单件产品的录入明细。
     */
    @Data
    public static class SceneImportProduct {

        /** AI 检测的位置框（相对原图比例坐标）。 */
        private ProductBoundingBox bbox;

        /** 最终使用的品类码。 */
        private String categoryCode;

        /** AI 给出的简短名称（展示参考）。 */
        private String label;

        /** 录入状态：success / failed。 */
        private String status;

        /** 创建的 RSPU ID（成功时）。 */
        private String rspuId;

        /** 异步识别任务 ID（成功时）。 */
        private String taskId;

        /** 裁剪图 ID（成功时，可用于 /api/v1/images/{imageId} 预览）。 */
        private String imageId;

        /** 失败原因（失败时）。 */
        private String error;
    }
}
