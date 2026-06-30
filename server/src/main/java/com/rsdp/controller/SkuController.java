package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.service.RskuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RSKU 顶层操作接口。
 */
@RestController
@RequestMapping("/api/v1/sku")
@RequiredArgsConstructor
public class SkuController {

    private final RskuService rskuService;

    /**
     * 软删除 RSKU 报价。
     *
     * @param rskuId RSKU ID
     * @return 空结果
     */
    @DeleteMapping("/{rskuId}")
    public Result<Void> delete(@PathVariable String rskuId) {
        rskuService.deleteRsku(rskuId);
        return Result.ok();
    }
}
