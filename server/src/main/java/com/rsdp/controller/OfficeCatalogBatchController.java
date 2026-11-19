package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.OfficeCatalogRequest;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportRow;
import com.rsdp.service.ExcelImportRowService;
import com.rsdp.service.OfficeCatalogBatchAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Read-only access to historical Office Excel import results. */
@RestController
@RequestMapping("/api/v1/products/office/batches")
@RequiredArgsConstructor
public class OfficeCatalogBatchController {
    private final ExcelImportRowService rows;
    private final OfficeCatalogBatchAccess access;

    /** Displays Office history after the catalog writer has been retired. */
    @GetMapping
    public Result<List<OfficeCatalogBatchAccess.BatchSummary>> readHistory(
        @Valid @ModelAttribute OfficeCatalogRequest.Actor actor) {
        return Result.ok(access.listHistory(actor));
    }

    /** The read-only connection may inspect its own historical results. */
    @GetMapping("/{batchId}/status")
    public Result<HistoricalStatus> readStatus(@PathVariable String batchId,
                                              @Valid @ModelAttribute OfficeCatalogRequest.Actor actor) {
        ExcelImportBatch batch = access.requireHistoryBatch(batchId, actor);
        return Result.ok(new HistoricalStatus(batch.getBatchId(), batch.getFileName(), batch.getStatus(),
            rows.listByBatch(batchId)));
    }

    public record HistoricalStatus(String batchId, String fileName, String status, List<ExcelImportRow> rows) {}
}
