package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档导入提交响应（阶段 3.1 异步化：确认导入后立即返回，批处理在后台执行）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentImportSubmitResult {

    /**
     * 导入批次号，用于轮询 {@code GET /api/v1/products/document-import/{batchId}}。
     */
    private String batchId;
}
