package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 产品批量删除结果。
 */
@Data
@AllArgsConstructor
public class ProductBatchDeleteResponse {

    /** 成功删除数量 */
    private int deletedCount;

    /** 失败数量 */
    private int failedCount;

    /** 失败明细 */
    private List<Failure> failures;

    /**
     * 单个产品删除失败明细。
     */
    @Data
    @AllArgsConstructor
    public static class Failure {

        /** RSPU ID */
        private String rspuId;

        /** 失败原因 */
        private String reason;
    }
}
