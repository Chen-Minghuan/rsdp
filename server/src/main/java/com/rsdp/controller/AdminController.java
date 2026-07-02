package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.service.VectorBackfillService;
import com.rsdp.service.VectorBackfillService.BackfillResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Validated
@Tag(name = "管理后台", description = "系统运维与管理接口")
public class AdminController {

    private final VectorBackfillService vectorBackfillService;

    /**
     * 触发存量图片向量回填。
     *
     * @param batchSize 单次处理数量，默认 100
     * @return 处理统计
     */
    // TODO: 接入认证授权后，应限制为管理员角色才可调用该运维接口。
    @PostMapping("/vectors/backfill")
    @Operation(summary = "向量回填", description = "为存量已识别图片生成 embedding 并写入向量库")
    public Result<BackfillResult> backfillVectors(@RequestParam(defaultValue = "100")
                                                  @Min(value = 1, message = "batchSize 不能小于 1")
                                                  @Max(value = 1000, message = "batchSize 不能超过 1000")
                                                  int batchSize) {
        BackfillResult result = vectorBackfillService.backfill(batchSize);
        return Result.ok(result);
    }
}
