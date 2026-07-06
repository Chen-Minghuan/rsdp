package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.FactoryProductCapabilityCreateRequest;
import com.rsdp.dto.request.FactoryProductCapabilityUpdateRequest;
import com.rsdp.dto.response.FactoryProductCapabilityResponse;
import com.rsdp.service.FactoryCapabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工厂产品能力档案接口。
 */
@RestController
@RequestMapping("/api/v1/factories/{factoryCode}/capabilities")
@RequiredArgsConstructor
@Validated
public class FactoryProductCapabilityController {

    private final FactoryCapabilityService factoryCapabilityService;

    /**
     * 查询指定工厂的能力档案列表。
     *
     * @param factoryCode 工厂编码
     * @return 能力档案列表
     */
    @GetMapping
    public Result<List<FactoryProductCapabilityResponse>> list(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode) {
        return Result.ok(factoryCapabilityService.listByFactory(factoryCode));
    }

    /**
     * 查询单条能力档案。
     *
     * @param factoryCode 工厂编码
     * @param id          能力档案 ID
     * @return 能力档案详情
     */
    @GetMapping("/{id}")
    public Result<FactoryProductCapabilityResponse> get(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
        @PathVariable Long id) {
        FactoryProductCapabilityResponse response = factoryCapabilityService.getById(id);
        if (!factoryCode.equals(response.getFactoryCode())) {
            return Result.error("能力档案不属于该工厂");
        }
        return Result.ok(response);
    }

    /**
     * 手工创建能力档案。
     *
     * @param factoryCode 工厂编码
     * @param request     创建请求
     * @return 创建后的能力档案
     */
    @PostMapping
    public Result<FactoryProductCapabilityResponse> create(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
        @RequestBody @Valid FactoryProductCapabilityCreateRequest request) {
        request.setFactoryCode(factoryCode);
        return Result.ok(factoryCapabilityService.create(request));
    }

    /**
     * 更新能力档案。
     *
     * @param factoryCode 工厂编码
     * @param id          能力档案 ID
     * @param request     更新请求
     * @return 更新后的能力档案
     */
    @PutMapping("/{id}")
    public Result<FactoryProductCapabilityResponse> update(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
        @PathVariable Long id,
        @RequestBody @Valid FactoryProductCapabilityUpdateRequest request) {
        FactoryProductCapabilityResponse existing = factoryCapabilityService.getById(id);
        if (!factoryCode.equals(existing.getFactoryCode())) {
            return Result.error("能力档案不属于该工厂");
        }
        return Result.ok(factoryCapabilityService.update(id, request));
    }

    /**
     * 删除能力档案。
     *
     * @param factoryCode 工厂编码
     * @param id          能力档案 ID
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
        @PathVariable Long id) {
        FactoryProductCapabilityResponse existing = factoryCapabilityService.getById(id);
        if (!factoryCode.equals(existing.getFactoryCode())) {
            return Result.error("能力档案不属于该工厂");
        }
        factoryCapabilityService.delete(id);
        return Result.ok();
    }

    /**
     * 手动同步指定工厂的能力档案。
     *
     * @param factoryCode 工厂编码
     * @return 同步后的能力档案列表
     */
    @PostMapping("/sync")
    public Result<List<FactoryProductCapabilityResponse>> sync(
        @PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode) {
        return Result.ok(factoryCapabilityService.syncByFactory(factoryCode));
    }
}
