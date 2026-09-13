package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品（RSPU）批量导入结果。
 *
 * <p>计数口径：successCount + failedCount + skippedCount = totalRows。
 * 真失败（校验不通过、重复行、落库异常等导致该行未导入）进 failedCount/failures；
 * 冲突跳过（updateIfExists=false 且产品已存在）进 skippedCount；
 * 不阻断导入的行级问题（图片下载/存储失败、场景标签未归一、rspu_code 发号跳过等）仅进 warnings，
 * 不影响行计数。</p>
 */
@Data
public class ProductImportResult {

    /**
     * 读取到的总行数（不含表头）。
     */
    private int totalRows;

    /**
     * 成功导入/更新数。
     */
    private int successCount;

    /**
     * 真失败数（= failures.size()，行未导入）。
     */
    private int failedCount;

    /**
     * 冲突跳过数（updateIfExists=false 且产品已存在，行未导入也不算失败；跳过原因记入 warnings）。
     */
    private int skippedCount;

    /**
     * 真失败明细。
     */
    private List<ProductImportFailure> failures = new ArrayList<>();

    /**
     * 行级警告明细（2.7）：不阻断导入的降级提示，如未归一的场景标签已跳过关联写入。
     * 复用失败明细的结构（行号/外部编码/RSPU ID/原因），不计入 failedCount。
     */
    private List<ProductImportFailure> warnings = new ArrayList<>();
}
