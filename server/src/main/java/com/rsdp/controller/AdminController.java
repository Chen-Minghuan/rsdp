package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.config.LoggingRejectedExecutionHandler;
import com.rsdp.dto.SupplierConfigurationDryRunReport;
import com.rsdp.dto.SupplierIdentityBackfillReport;
import com.rsdp.dto.response.AsyncMetricsResponse;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.SupplierConfigurationMigrationService;
import com.rsdp.service.SupplierIdentityBackfillService;
import com.rsdp.service.VectorBackfillService;
import com.rsdp.service.VectorBackfillService.BackfillResult;
import com.rsdp.service.VectorRebuildService;
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

import java.util.Map;
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
    private final VectorRebuildService vectorRebuildService;
    private final SupplierIdentityBackfillService supplierIdentityBackfillService;
    private final SupplierConfigurationMigrationService supplierConfigurationMigrationService;

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
     * 提交存量图片向量重建异步任务（pgvector 逐图片重建）。
     *
     * <p>授权：URL 级规则 {@code /api/v1/admin/**} 要求 ADMIN 角色（见 SecurityConfig）。
     * 返回任务 ID，进度与结果通过 async_task 表查询。</p>
     *
     * @param batchSize 每轮回填数量，默认 500
     * @return 任务 ID
     */
    @PostMapping("/vectors/rebuild")
    @Operation(summary = "向量重建任务", description = "提交异步任务，按源图逐图片重建 pgvector 向量")
    public Result<String> rebuildVectors(@RequestParam(defaultValue = "500")
                                         @Min(value = 1, message = "batchSize 不能小于 1")
                                         @Max(value = 1000, message = "batchSize 不能超过 1000")
                                         int batchSize) {
        String taskId = vectorRebuildService.submitRebuildTask(batchSize, SecurityOperatorContext.currentUsername());
        return Result.ok(taskId);
    }

    /**
     * 触发 external_code → supplier_product_ref 历史回填（设计文档 v3.3 §9 Phase 2）。
     *
     * <p>授权：URL 级规则 {@code /api/v1/admin/**} 要求 ADMIN 角色（见 SecurityConfig）。</p>
     *
     * <p>运维口径：先 {@code dryRun=true}（默认）审报告，确认分类清单后再显式
     * {@code dryRun=false} 真实回填；回填幂等可重跑。</p>
     *
     * @param dryRun true = 只统计不写库，默认 true
     * @param batchSize 每批装载供应关系明细的 RSPU 数上限，默认 200
     * @param sourceSystem provenance 来源标记，缺省用服务默认值 legacy-backfill
     * @return 回填报告（分类计数 + 逐条明细）
     */
    @PostMapping("/supplier-identity/backfill")
    @Operation(summary = "供应商身份历史回填",
        description = "external_code → supplier_product_ref 历史回填；dryRun=true（默认）只统计不写库")
    public Result<SupplierIdentityBackfillReport> backfillSupplierIdentity(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "200")
            @Min(value = 1, message = "batchSize 不能小于 1")
            @Max(value = 1000, message = "batchSize 不能超过 1000")
            int batchSize,
            @RequestParam(required = false) String sourceSystem) {
        SupplierIdentityBackfillReport report = supplierIdentityBackfillService.backfill(
            new SupplierIdentityBackfillService.BackfillOptions(dryRun, batchSize, sourceSystem));
        return Result.ok(report);
    }

    /**
     * Supplier Configuration Normalizer 升级 Dry Run（v3.3 §18.3，P0-D）。
     *
     * <p>对全部有效 Supplier Configuration 以目标版本重算 Key，分类
     * stable / one-to-one / converged / ambiguous；只读不写库。converged / ambiguous
     * 清零前不得切换 Normalizer Version（报告 isSwitchAllowed=false）。</p>
     *
     * <p>授权：URL 级规则 {@code /api/v1/admin/**} 要求 ADMIN 角色（见 SecurityConfig）。</p>
     *
     * @param targetVersion 目标归一版本
     * @return Dry Run 报告
     */
    @PostMapping("/supplier-configuration/normalizer-dry-run")
    @Operation(summary = "供应配置归一升级 Dry Run",
        description = "以目标版本重算全部有效 Supplier Configuration Key 并分类（stable/one-to-one/converged/ambiguous），只读不写库")
    public Result<SupplierConfigurationDryRunReport> dryRunSupplierConfigurationNormalizer(
            @RequestParam int targetVersion) {
        return Result.ok(supplierConfigurationMigrationService.dryRunNormalizerUpgrade(targetVersion));
    }

    /**
     * TEXT→CODE 受控 Key Migration（v3.3 §18.3，P0-D）：保留 rsku_id，
     * 单事务内锁定原 RSKU、校验目标 CODE Key 未被占用后更新 Key 表达并记审计。
     *
     * <p>授权：URL 级规则 {@code /api/v1/admin/**} 要求 ADMIN 角色（见 SecurityConfig）。</p>
     *
     * @param rskuId    待迁移 RSKU
     * @param gradeCode 治理确认的稳定配置/等级码（原文）
     * @return Migration 结果
     */
    @PostMapping("/supplier-configuration/key-migration")
    @Operation(summary = "供应配置 TEXT→CODE 受控迁移",
        description = "TEXT Key 的 RSKU 保留 rsku_id 升级为 CODE Key；目标 Key 被占用时报冲突不合并")
    public Result<SupplierConfigurationMigrationService.KeyMigrationResult> migrateSupplierConfigurationKey(
            @RequestParam String rskuId,
            @RequestParam String gradeCode) {
        return Result.ok(supplierConfigurationMigrationService.migrateTextKeyToCode(
            rskuId, gradeCode, SecurityOperatorContext.currentUsername()));
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
