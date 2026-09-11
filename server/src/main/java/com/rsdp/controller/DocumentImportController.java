package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.dto.response.DocumentImportSubmitResult;
import com.rsdp.service.PdfImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档批量导入接口（阶段 3.1：异步批次化）。
 *
 * <p>当前支持 PDF 产品目录导入，未来可扩展 PPT、Excel 等非结构化文档。
 * 提交接口校验 + 落原始文件 + 建批次后立即返回 batchId；处理进度/结果走批次查询接口轮询。</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class DocumentImportController {

    private final PdfImportService pdfImportService;

    /**
     * 从 PDF 文档批量导入产品（异步）：校验通过后创建导入批次并立即返回 batchId。
     *
     * @param file         PDF 文件
     * @param categoryHint 品类提示，如 SF/TB/FC
     * @return 提交结果（batchId）
     * @throws IOException 文件处理失败
     */
    @PostMapping("/document-import")
    public Result<DocumentImportSubmitResult> importFromDocument(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "categoryHint", required = false) String categoryHint) throws IOException {
        return Result.ok(pdfImportService.importPdf(file, categoryHint));
    }

    /**
     * 查询文档导入批次状态/进度/结果（含 taskIds/rspuIds 配对，供前端继续轮询各产品识别任务）。
     *
     * @param batchId 批次 ID
     * @return 批次结果（状态、已处理页数/总页数、建档/失败/跳过计数、失败明细）
     */
    @GetMapping("/document-import/{batchId}")
    public Result<DocumentImportResult> getImportBatch(@PathVariable String batchId) {
        return Result.ok(pdfImportService.getBatchResult(batchId));
    }
}
