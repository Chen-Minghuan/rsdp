package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.service.RspuVariantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RSPU 变体管理接口。
 */
@RestController
@RequestMapping("/api/v1/products/{rspuId}/variants")
@RequiredArgsConstructor
@Validated
public class RspuVariantController {

    private final RspuVariantService variantService;

    /**
     * 为指定 RSPU 创建变体。
     *
     * @param rspuId  RSPU ID
     * @param request 变体创建请求
     * @return 创建的变体
     */
    @PostMapping
    public Result<RspuVariantResponse> createVariant(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                                                     @Valid @RequestBody RspuVariantCreateRequest request) {
        return Result.ok(variantService.createVariant(rspuId, request));
    }

    /**
     * 查询某 RSPU 下的所有变体。
     *
     * @param rspuId RSPU ID
     * @return 变体列表
     */
    @GetMapping
    public Result<List<RspuVariantResponse>> listVariants(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId) {
        return Result.ok(variantService.listVariantsByRspu(rspuId));
    }
}
