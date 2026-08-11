package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.PublicAiMatchSchemeRequest;
import com.rsdp.dto.response.PublicAiMatchAnalyzeResponse;
import com.rsdp.dto.response.PublicAiMatchSchemeResponse;
import com.rsdp.service.PublicAiMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 官网 AI 户型搭配公开接口（免登录，/api/v1/public/** 由 SecurityConfig 放行）。
 *
 * <p>红线：响应绝不包含 RSKU 工厂报价字段。</p>
 */
@RestController
@RequestMapping("/api/v1/public/ai-match")
@RequiredArgsConstructor
@Validated
public class PublicAiMatchController {

    private final PublicAiMatchService publicAiMatchService;

    /**
     * 分析户型图：识别功能空间并解析尺寸标注。
     *
     * @param file 户型图片（jpg/png，≤10MB）
     * @param hint 用户补充说明（可选）
     * @return 空间识别结果
     */
    @PostMapping("/analyze")
    public Result<PublicAiMatchAnalyzeResponse> analyze(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String hint) {
        return Result.ok(publicAiMatchService.analyze(file, hint));
    }

    /**
     * 生成公开安全的 AI 搭配方案（仅零售参考价，无工厂字段）。
     *
     * @param request 搭配请求（风格/预算/尺寸均可空）
     * @return 公开搭配方案
     */
    @PostMapping("/scheme")
    public Result<PublicAiMatchSchemeResponse> generateScheme(
        @Valid @RequestBody PublicAiMatchSchemeRequest request) {
        return Result.ok(publicAiMatchService.generateScheme(request));
    }
}
