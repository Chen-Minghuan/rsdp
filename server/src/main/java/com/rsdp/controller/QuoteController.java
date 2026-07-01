package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.QuoteGenerateRequest;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.service.QuoteExportService;
import com.rsdp.service.QuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报价单接口。
 */
@RestController
@RequestMapping("/api/v1/quotes")
@RequiredArgsConstructor
@Validated
public class QuoteController {

    private final QuoteService quoteService;
    private final QuoteExportService quoteExportService;

    /**
     * 根据 RSKU 列表生成报价单。
     *
     * @param request 请求
     * @return 报价单
     */
    @PostMapping("/generate")
    public Result<QuoteResponse> generate(@Valid @RequestBody QuoteGenerateRequest request) {
        return Result.ok(quoteService.generateQuote(request.getRskuIds()));
    }

    /**
     * 根据 RSKU 列表导出 Excel 报价单。
     *
     * @param request 请求
     * @return Excel 文件流
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody QuoteGenerateRequest request) {
        byte[] content = quoteExportService.exportQuote(request.getRskuIds());
        String filename = "quote_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(content);
    }
}
