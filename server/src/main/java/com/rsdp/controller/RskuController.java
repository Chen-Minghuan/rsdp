package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.request.RskuPriceUpdateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.service.RskuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RSKU 报价接口。
 */
@RestController
@RequestMapping("/api/v1/products/{rspuId}/rsku")
@RequiredArgsConstructor
public class RskuController {

    private final RskuService rskuService;

    /**
     * 查询某 RSPU 下的 RSKU 报价列表。
     *
     * @param rspuId RSPU ID
     * @return RSKU 列表
     */
    @GetMapping
    public Result<List<RskuResponse>> list(@PathVariable String rspuId) {
        return Result.ok(rskuService.listByRspu(rspuId));
    }

    /**
     * 查询单个 RSKU 报价详情。
     *
     * @param rspuId RSPU ID
     * @param rskuId RSKU ID
     * @return RSKU 详情
     */
    @GetMapping("/{rskuId}")
    public Result<RskuResponse> detail(@PathVariable String rspuId,
                                       @PathVariable String rskuId) {
        return Result.ok(rskuService.getRsku(rspuId, rskuId));
    }

    /**
     * 为 RSPU 新增 RSKU 报价。
     *
     * @param rspuId  RSPU ID
     * @param request 报价请求
     * @return 空结果
     */
    @PostMapping
    public Result<Void> create(@PathVariable String rspuId,
                               @Valid @RequestBody RskuCreateRequest request) {
        request.setRspuId(rspuId);
        rskuService.createRsku(request);
        return Result.ok();
    }

    /**
     * 更新 RSKU 出厂价。
     *
     * @param rspuId  RSPU ID
     * @param rskuId  RSKU ID
     * @param request 价格更新请求
     * @return 空结果
     */
    @PutMapping("/{rskuId}/price")
    public Result<Void> updatePrice(@PathVariable String rspuId,
                                    @PathVariable String rskuId,
                                    @Valid @RequestBody RskuPriceUpdateRequest request) {
        rskuService.updateRskuPrice(rskuId, request.getFactoryPrice(), request.getChangeReason());
        return Result.ok();
    }
}
