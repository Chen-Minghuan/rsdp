package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.service.PdfImportService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档批量导入接口。
 *
 * <p>当前支持 PDF 产品目录导入，未来可扩展 PPT、Excel 等非结构化文档。</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class DocumentImportController {

    private final PdfImportService pdfImportService;

    /**
     * 从 PDF 文档批量导入产品。
     *
     * @param file         PDF 文件
     * @param categoryHint 品类提示，如 SF/TB/FC
     * @return 导入批次结果
     * @throws IOException 文件处理失败
     */
    @PostMapping("/document-import")
    public Result<DocumentImportResult> importFromDocument(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "categoryHint", required = false) String categoryHint) throws IOException {
        return Result.ok(pdfImportService.importPdf(file, categoryHint));
    }
}
