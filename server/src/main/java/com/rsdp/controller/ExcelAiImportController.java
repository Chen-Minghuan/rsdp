package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
import com.rsdp.service.ExcelAiImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel AI 辅助导入接口。
 */
@RestController
@RequestMapping("/api/v1/products/excel-ai-import")
@RequiredArgsConstructor
@Validated
public class ExcelAiImportController {

    private final ExcelAiImportService excelAiImportService;

    /**
     * 上传 Excel 并预览 AI 识别的字段映射。
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiMappingResponse> preview(
        @RequestPart("file") MultipartFile file) {
        return Result.ok(excelAiImportService.previewMapping(file));
    }

    /**
     * 确认字段映射并执行导入。
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiImportResult> importExcel(
        @RequestBody @Valid ExcelAiMappingRequest request) {
        return Result.ok(excelAiImportService.confirmAndImport(request));
    }

    /**
     * 查询导入批次状态。
     */
    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiImportStatusResponse> getStatus(
        @PathVariable @NotBlank String batchId) {
        return Result.ok(excelAiImportService.getStatus(batchId));
    }
}
