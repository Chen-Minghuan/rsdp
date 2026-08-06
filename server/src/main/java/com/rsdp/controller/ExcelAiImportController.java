package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.ExcelAiMappingRequest;
import com.rsdp.dto.response.ExcelAiImportResult;
import com.rsdp.dto.response.ExcelAiImportStatusResponse;
import com.rsdp.dto.response.ExcelAiMappingResponse;
import com.rsdp.dto.response.ExcelAiPreviewDataResponse;
import com.rsdp.dto.response.PreviewDataRow;
import com.rsdp.entity.ExcelImportRow;
import com.rsdp.service.ExcelAiImportService;
import com.rsdp.service.ExcelImportRowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ExcelImportRowService excelImportRowService;

    /**
     * 上传 Excel 并预览 AI 识别的字段映射。
     *
     * @param file       Excel 文件
     * @param sheetIndex 待解析的工作表索引（可选，默认 0；多 Sheet 文件逐一导入用）
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiMappingResponse> preview(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "sheetIndex", required = false, defaultValue = "0") int sheetIndex) {
        return Result.ok(excelAiImportService.previewMapping(file, sheetIndex));
    }

    /**
     * 确认字段映射并执行导入。
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiImportResult> importExcel(
        @RequestBody @Valid ExcelAiMappingRequest request) {
        excelAiImportService.getAccessibleBatch(request.getBatchId());
        return Result.ok(excelAiImportService.confirmAndImport(request));
    }

    /**
     * 查询导入批次状态。
     */
    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiImportStatusResponse> getStatus(
        @PathVariable @NotBlank String batchId) {
        excelAiImportService.getAccessibleBatch(batchId);
        return Result.ok(excelAiImportService.getStatus(batchId));
    }

    /**
     * 查询导入批次全量预览数据（导入前数据清洗用）。
     */
    @GetMapping("/{batchId}/preview-data")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<ExcelAiPreviewDataResponse> getPreviewData(
        @PathVariable @NotBlank String batchId) {
        excelAiImportService.getAccessibleBatch(batchId);
        return Result.ok(excelAiImportService.getPreviewData(batchId));
    }

    /**
     * 查询导入批次下所有行级记录。
     */
    @GetMapping("/{batchId}/rows")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<List<ExcelImportRow>> listRows(
        @PathVariable @NotBlank String batchId) {
        excelAiImportService.getAccessibleBatch(batchId);
        return Result.ok(excelImportRowService.listByBatch(batchId));
    }

    /**
     * 懒加载指定预览行的内嵌图片缩略图（Base64 data URL）。
     *
     * <p>全量预览接口只返回图片元数据，前端按行进入可视区域时再调用本接口，
     * 避免大文件因一次性生成全部缩略图而超时。</p>
     *
     * @param batchId  导入批次 ID
     * @param rowIndex Excel 展示行号（1-based）
     */
    @GetMapping("/{batchId}/preview-data/rows/{rowIndex}/images")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<List<PreviewDataRow.PreviewRowImage>> getPreviewRowImages(
        @PathVariable @NotBlank String batchId,
        @PathVariable int rowIndex) {
        excelAiImportService.getAccessibleBatch(batchId);
        return Result.ok(excelAiImportService.getPreviewRowImages(batchId, rowIndex));
    }

    /**
     * 上传本地图片作为某行的覆盖图片（数据清洗阶段）。
     *
     * @param batchId 导入批次 ID
     * @param file    图片文件
     * @return 临时图片 key
     */
    @PostMapping("/{batchId}/preview-images")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<String> uploadPreviewImage(
        @PathVariable @NotBlank String batchId,
        @RequestPart("file") MultipartFile file) {
        excelAiImportService.getAccessibleBatch(batchId);
        return Result.ok(excelAiImportService.uploadPreviewImage(batchId, file));
    }

    /**
     * 读取数据清洗阶段上传的临时图片。
     *
     * @param batchId      导入批次 ID
     * @param tempImageKey 临时图片 key
     */
    @GetMapping("/{batchId}/preview-images/{tempImageKey}")
    @PreAuthorize("hasAuthority('product:import')")
    public ResponseEntity<byte[]> getPreviewImage(
        @PathVariable @NotBlank String batchId,
        @PathVariable @NotBlank String tempImageKey) {
        excelAiImportService.getAccessibleBatch(batchId);
        byte[] bytes = excelAiImportService.loadPreviewImage(batchId, tempImageKey);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = MediaType.IMAGE_JPEG_VALUE;
        if (tempImageKey.toLowerCase().endsWith(".png")) {
            contentType = MediaType.IMAGE_PNG_VALUE;
        } else if (tempImageKey.toLowerCase().endsWith(".gif")) {
            contentType = MediaType.IMAGE_GIF_VALUE;
        } else if (tempImageKey.toLowerCase().endsWith(".webp")) {
            contentType = "image/webp";
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + tempImageKey + "\"")
            .body(bytes);
    }

    /**
     * 设置某行的用户覆盖图片列表（覆盖 Excel 内嵌图片）。
     *
     * @param batchId  导入批次 ID
     * @param rowIndex Excel 展示行号（1-based）
     * @param request  临时图片 key 列表
     */
    @PutMapping("/{batchId}/rows/{rowIndex}/images")
    @PreAuthorize("hasAuthority('product:import')")
    public Result<Void> setRowImageOverrides(
        @PathVariable @NotBlank String batchId,
        @PathVariable int rowIndex,
        @RequestBody Map<String, List<String>> request) {
        excelAiImportService.getAccessibleBatch(batchId);
        excelAiImportService.setRowImageOverrides(batchId, rowIndex, request.get("tempImageKeys"));
        return Result.ok();
    }
}
