package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.entity.PriceHistory;
import com.rsdp.service.PriceHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 价格历史接口。
 */
@RestController
@RequestMapping("/api/v1/rsku/{rskuId}/price-history")
@RequiredArgsConstructor
public class PriceHistoryController {

    private final PriceHistoryService priceHistoryService;

    /**
     * 查询某 RSKU 的价格历史。
     *
     * @param rskuId RSKU ID
     * @return 价格历史列表
     */
    @GetMapping
    public Result<List<PriceHistory>> listPriceHistory(@PathVariable String rskuId) {
        return Result.ok(priceHistoryService.listByRsku(rskuId));
    }
}
