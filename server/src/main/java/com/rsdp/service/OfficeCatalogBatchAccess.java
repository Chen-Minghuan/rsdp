package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.OfficeCatalogRequest;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.config.properties.OfficeCatalogProperties;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Scopes historical Office Excel batches to their workspace, factory and original operator. */
@Service
@RequiredArgsConstructor
public class OfficeCatalogBatchAccess {
    private final ExcelImportBatchMapper batches;
    private final OfficeCatalogProperties properties;
    private final DataScopeContext dataScope;

    /** Validates the read-only integration identity against the Office session's workspace and factory. */
    public void verifyHistoryActor(OfficeCatalogRequest.Actor actor) {
        if (!SecurityOperatorContext.hasAuthority("ROLE_OFFICE_CATALOG_READER")
            || !properties.getUsername().equals(SecurityOperatorContext.currentUsername())
            || !properties.getWorkspaceId().equals(actor.workspaceId())
            || !properties.getFactoryCode().equals(actor.factoryCode())
            || !dataScope.currentFactoryCodes().equals(List.of(actor.factoryCode()))) {
            throw new ForbiddenException("无权读取旧 Office 导入记录");
        }
    }

    /** Checks historical batch ownership without granting import permission. */
    public ExcelImportBatch requireHistoryBatch(String batchId, OfficeCatalogRequest.Actor actor) {
        verifyHistoryActor(actor);
        return checkBatch(batchId, actor);
    }

    private ExcelImportBatch checkBatch(String batchId, OfficeCatalogRequest.Actor actor) {
        ExcelImportBatch batch = batches.selectById(batchId);
        if (batch == null || !Objects.equals(batch.getOfficeWorkspaceId(), actor.workspaceId())
            || !Objects.equals(batch.getOfficeActorId(), actor.actorId())
            || !Objects.equals(batch.getFactoryCode(), actor.factoryCode())
            || !Objects.equals(batch.getCreatedBy(), SecurityOperatorContext.currentUserId())) {
            throw new ResourceNotFoundException("未找到此工作区录入人的导入批次");
        }
        return batch;
    }

    /** Reads the operator's legacy Office batches. */
    public List<BatchSummary> listHistory(OfficeCatalogRequest.Actor actor) {
        verifyHistoryActor(actor);
        return batches.selectList(new QueryWrapper<ExcelImportBatch>()
            .select("batch_id", "file_name", "status", "total_rows", "success_count", "failed_count", "created_at")
            .eq("office_workspace_id", actor.workspaceId())
            .eq("office_actor_id", actor.actorId())
            .eq("factory_code", actor.factoryCode())
            .eq("created_by", SecurityOperatorContext.currentUserId())
            .orderByDesc("created_at").last("LIMIT 100"))
            .stream().map(batch -> new BatchSummary(batch.getBatchId(), batch.getFileName(), batch.getStatus(),
                batch.getTotalRows(), batch.getSuccessCount(), batch.getFailedCount(), batch.getCreatedAt()))
            .toList();
    }

    public record BatchSummary(String batchId, String fileName, String status, Integer totalRows,
                               Integer successCount, Integer failedCount, LocalDateTime createdAt) {}
}
