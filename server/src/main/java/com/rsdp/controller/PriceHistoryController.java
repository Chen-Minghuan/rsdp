package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.PriceHistoryService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
@Validated
public class PriceHistoryController {

    private final PriceHistoryService priceHistoryService;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 查询某 RSKU 的价格历史。
     *
     * @param rskuId RSKU ID
     * @return 价格历史列表
     */
    @GetMapping
    public Result<List<PriceHistory>> listPriceHistory(@PathVariable @NotBlank(message = "RSKU ID 不能为空") String rskuId) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null) {
            throw new BusinessException("RSKU 不存在: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new BusinessException("无权访问该 RSKU 的价格历史");
        }
        return Result.ok(priceHistoryService.listByRsku(rskuId));
    }
}
