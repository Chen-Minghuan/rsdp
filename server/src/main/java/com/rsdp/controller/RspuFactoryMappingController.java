package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RspuFactoryMappingRequest;
import com.rsdp.dto.response.RspuFactoryMappingResponse;
import com.rsdp.service.RspuFactoryMappingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RSPU-工厂关联接口。
 */
@RestController
@RequestMapping("/api/v1/products/{rspuId}/factories")
@RequiredArgsConstructor
public class RspuFactoryMappingController {

    private final RspuFactoryMappingService mappingService;

    /**
     * 查询某 RSPU 的所有工厂关联（可按发货地省份筛选）。
     */
    @GetMapping
    public Result<List<RspuFactoryMappingResponse>> listByRspu(
        @PathVariable String rspuId,
        @RequestParam(required = false) String province) {
        return Result.ok(mappingService.listByRspu(rspuId, province));
    }

    /**
     * 创建/更新 RSPU-工厂关联。
     */
    @PostMapping
    public Result<Long> saveMapping(@PathVariable String rspuId,
                                     @Valid @RequestBody RspuFactoryMappingRequest request) {
        request.setRspuId(rspuId);
        return Result.ok(mappingService.saveMapping(request));
    }

    /**
     * 删除关联。
     */
    @DeleteMapping("/{mappingId}")
    public Result<Void> deleteMapping(@PathVariable String rspuId, @PathVariable Long mappingId) {
        mappingService.deleteMapping(mappingId);
        return Result.ok();
    }
}
