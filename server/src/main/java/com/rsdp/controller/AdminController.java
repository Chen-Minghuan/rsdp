package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.config.LoggingRejectedExecutionHandler;
import com.rsdp.dto.response.AsyncMetricsResponse;
import com.rsdp.service.VectorBackfillService;
import com.rsdp.service.VectorBackfillService.BackfillResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadPoolExecutor;

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

    @Autowired
    @Qualifier("taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    /**
     * 触发存量图片向量回填。
     *
     * <p>授权：URL 级规则 {@code /api/v1/admin/**} 要求 ADMIN 角色（见 SecurityConfig）。</p>
     *
     * @param batchSize 单次处理数量，默认 100
     * @return 处理统计
     */
    @PostMapping("/vectors/backfill")
    @Operation(summary = "向量回填", description = "为存量已识别图片生成 embedding 并写入向量库")
    public Result<BackfillResult> backfillVectors(@RequestParam(defaultValue = "100")
                                                  @Min(value = 1, message = "batchSize 不能小于 1")
                                                  @Max(value = 1000, message = "batchSize 不能超过 1000")
                                                  int batchSize) {
        BackfillResult result = vectorBackfillService.backfill(batchSize);
        return Result.ok(result);
    }

    /**
     * 获取异步任务线程池运行时指标。
     */
    @GetMapping("/async/metrics")
    @Operation(summary = "异步线程池指标", description = "查询 taskExecutor 活跃线程数、队列大小等运行时指标")
    public Result<AsyncMetricsResponse> asyncMetrics() {
        ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();
        AsyncMetricsResponse response = new AsyncMetricsResponse();
        response.setCorePoolSize(pool.getCorePoolSize());
        response.setMaxPoolSize(pool.getMaximumPoolSize());
        response.setQueueCapacity(pool.getQueue().remainingCapacity() + pool.getQueue().size());
        response.setActiveCount(pool.getActiveCount());
        response.setQueueSize(pool.getQueue().size());
        response.setCompletedTaskCount(pool.getCompletedTaskCount());
        response.setTaskCount(pool.getTaskCount());
        response.setRejectedPolicy(resolveRejectedPolicyName(pool));
        return Result.ok(response);
    }

    private String resolveRejectedPolicyName(ThreadPoolExecutor pool) {
        var handler = pool.getRejectedExecutionHandler();
        if (handler instanceof LoggingRejectedExecutionHandler loggingHandler) {
            return loggingHandler.getDelegateName();
        }
        return handler != null ? handler.getClass().getSimpleName() : "unknown";
    }
}
