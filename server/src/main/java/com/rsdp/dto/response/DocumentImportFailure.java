package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 文档导入失败明细。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentImportFailure {

    /**
     * 失败页码索引，从 0 开始。
     */
    private Integer pageIndex;

    /**
     * 失败原因。
     */
    private String reason;
}
