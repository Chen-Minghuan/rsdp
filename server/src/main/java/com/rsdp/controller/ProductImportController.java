package com.rsdp.controller;

import com.alibaba.excel.EasyExcel;
import com.rsdp.common.Result;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.response.ProductImportResult;
import com.rsdp.service.ProductImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 产品（RSPU）批量导入接口。
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class ProductImportController {

    private final ProductImportService productImportService;

    /**
     * 下载产品导入模板。
     *
     * @param response HTTP 响应
     * @throws IOException IO 异常
     */
    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("product_import_template", StandardCharsets.UTF_8)
            .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");

        List<ProductImportRow> templateRows = List.of();
        EasyExcel.write(response.getOutputStream(), ProductImportRow.class)
            .sheet("template")
            .doWrite(templateRows);
    }

    /**
     * 批量导入产品。
     *
     * @param file           Excel 文件
     * @param updateIfExists 存在时是否更新，默认 false
     * @return 导入结果
     */
    @PostMapping("/import")
    public Result<ProductImportResult> importProducts(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "updateIfExists", required = false, defaultValue = "false") boolean updateIfExists) {
        return Result.ok(productImportService.importProducts(file, updateIfExists));
    }
}
