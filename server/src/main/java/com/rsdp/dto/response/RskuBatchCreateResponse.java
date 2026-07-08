package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量创建 RSKU 报价结果。
 */
@Data
public class RskuBatchCreateResponse {

    private int successCount;
    private int failedCount;
    private List<String> rskuIds = new ArrayList<>();
    private List<FailureDetail> failures = new ArrayList<>();

    @Data
    public static class FailureDetail {
        private String factoryCode;
        private String reason;

        public FailureDetail(String factoryCode, String reason) {
            this.factoryCode = factoryCode;
            this.reason = reason;
        }
    }
}
