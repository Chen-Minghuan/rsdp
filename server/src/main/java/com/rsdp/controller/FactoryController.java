package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.FactoryCreateRequest;
import com.rsdp.dto.request.FactoryLevelCapabilityUpdateRequest;
import com.rsdp.dto.request.FactoryLevelUpdateRequest;
import com.rsdp.dto.request.FactoryUpdateRequest;
import com.rsdp.dto.response.FactoryResponse;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.service.FactoryService;
import com.rsdp.service.RskuService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工厂管理接口。
 */
@RestController
@RequestMapping("/api/v1/factories")
@RequiredArgsConstructor
@Validated
public class FactoryController {

    private final FactoryService factoryService;
    private final RskuService rskuService;

    /**
     * 查询工厂列表。
     *
     * @return 工厂列表
     */
    @GetMapping
    public Result<List<FactoryResponse>> list() {
        return Result.ok(factoryService.listFactories());
    }

    /**
     * 查询工厂详情。
     *
     * @param factoryCode 工厂代码
     * @return 工厂详情
     */
    @GetMapping("/{factoryCode}")
    public Result<FactoryResponse> detail(@PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode) {
        return Result.ok(factoryService.getFactory(factoryCode));
    }

    /**
     * 创建工厂。
     *
     * @param request 创建请求
     * @return 空结果
     */
    @PostMapping
    public Result<Void> create(@Valid @RequestBody FactoryCreateRequest request) {
        factoryService.createFactory(request);
        return Result.ok();
    }

    /**
     * 更新工厂基本信息（部分字段更新）。
     *
     * @param factoryCode 工厂代码
     * @param request     更新请求
     * @return 空结果
     */
    @PutMapping("/{factoryCode}")
    public Result<Void> update(@PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
                               @Valid @RequestBody FactoryUpdateRequest request) {
        factoryService.updateFactory(factoryCode, request);
        return Result.ok();
    }

    /**
     * 更新工厂等级，工厂代码保持不变。
     *
     * @param factoryCode 工厂代码
     * @param request     等级更新请求
     * @return 空结果
     */
    @PutMapping("/{factoryCode}/level")
    public Result<Void> updateLevel(@PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
                                    @Valid @RequestBody FactoryLevelUpdateRequest request) {
        factoryService.updateFactoryLevel(factoryCode, request.getFactoryLevel());
        return Result.ok();
    }

    /**
     * 更新工厂兼做等级。
     *
     * @param factoryCode 工厂代码
     * @param request     兼做等级更新请求
     * @return 空结果
     */
    @PutMapping("/{factoryCode}/capable-levels")
    public Result<Void> updateCapableLevels(@PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode,
                                            @Valid @RequestBody FactoryLevelCapabilityUpdateRequest request) {
        factoryService.updateCapableLevels(factoryCode, request);
        return Result.ok();
    }

    /**
     * 查询某工厂的所有 RSKU 报价。
     *
     * @param factoryCode 工厂代码
     * @return RSKU 列表
     */
    @GetMapping("/{factoryCode}/rsku")
    public Result<List<RskuResponse>> listRsku(@PathVariable @NotBlank(message = "工厂代码不能为空") String factoryCode) {
        return Result.ok(rskuService.listByFactory(factoryCode));
    }
}
