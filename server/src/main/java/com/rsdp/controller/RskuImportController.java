package com.rsdp.controller;

import com.alibaba.excel.EasyExcel;
import com.rsdp.common.Result;
import com.rsdp.dto.excel.RskuImportRow;
import com.rsdp.dto.response.RskuImportResult;
import com.rsdp.service.RskuImportService;
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
 * RSKU 报价批量导入接口。
 */
@RestController
@RequestMapping("/api/v1/rsku")
@RequiredArgsConstructor
@Validated
public class RskuImportController {

    private final RskuImportService rskuImportService;

    /**
     * 下载 RSKU 报价导入模板。
     *
     * @param response HTTP 响应
     * @throws IOException IO 异常
     */
    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("rsku_import_template", StandardCharsets.UTF_8)
            .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");

        List<RskuImportRow> templateRows = List.of();
        EasyExcel.write(response.getOutputStream(), RskuImportRow.class)
            .sheet("template")
            .doWrite(templateRows);
    }

    /**
     * 批量导入 RSKU 报价。
     *
     * @param file           Excel 文件
     * @param updateIfExists 存在时是否更新价格，默认 false
     * @return 导入结果
     */
    @PostMapping("/import")
    public Result<RskuImportResult> importRskus(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "updateIfExists", required = false, defaultValue = "false") boolean updateIfExists) {
        return Result.ok(rskuImportService.importRskus(file, updateIfExists));
    }
}
