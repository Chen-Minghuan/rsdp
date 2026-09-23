package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 户型图分析记录批量删除结果。
 */
@Data
@AllArgsConstructor
public class FloorPlanBatchDeleteResponse {

    /** 成功删除数量。 */
    private int deletedCount;

    /** 删除失败数量。 */
    private int failedCount;

    /** 失败明细。 */
    private List<Failure> failures;

    /**
     * 单条分析记录删除失败明细。
     */
    @Data
    @AllArgsConstructor
    public static class Failure {

        /** 分析批次 ID。 */
        private String analysisId;

        /** 失败原因。 */
        private String reason;
    }
}
