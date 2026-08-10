package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.service.PlatformLeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官网留资公开提交接口（免登录，/api/v1/public/** 由 SecurityConfig 放行）。
 */
@RestController
@RequestMapping("/api/v1/public/leads")
@RequiredArgsConstructor
@Validated
public class PublicLeadController {

    private final PlatformLeadService platformLeadService;

    /**
     * 提交留资线索（官网 CTA/表单/AI 搭配入口）。
     *
     * @param request 留资请求
     * @return 线索 ID 与初始状态
     */
    @PostMapping
    public Result<LeadCreateResponse> createLead(@Valid @RequestBody LeadCreateRequest request) {
        return Result.ok(platformLeadService.createLead(request));
    }
}
