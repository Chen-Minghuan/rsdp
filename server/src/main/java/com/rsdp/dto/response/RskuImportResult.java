package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RSKU 报价批量导入结果。
 */
@Data
public class RskuImportResult {

    /**
     * 读取到的总行数（不含表头）。
     */
    private int totalRows;

    /**
     * 成功导入/更新数。
     */
    private int successCount;

    /**
     * 失败数。
     */
    private int failedCount;

    /**
     * 失败明细。
     */
    private List<RskuImportFailure> failures = new ArrayList<>();
}
