package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.QuoteGenerateRequest;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.service.QuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
public class QuoteController {

    private final QuoteService quoteService;

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
}
