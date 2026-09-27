package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.entity.ExcelImportAttempt;
import com.rsdp.entity.ExcelImportAttemptItem;
import com.rsdp.entity.ExcelImportProductGroup;
import com.rsdp.exception.AttemptLeaseLostException;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ExcelImportAttemptItemMapper;
import com.rsdp.mapper.ExcelImportAttemptMapper;
import com.rsdp.supplychain.policy.ImportPolicyMaterializer;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Import Attempt 执行内核（设计文档 v3.3 §48-§50，P0-F）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>Replay-first（§50.0）：{@link #findByPolicyKey} 按
 *       {@code batch + revision + policy_hash_version + policy_hash} 定位既有 Attempt；
 *       done → 直接返回存储的 outcome + result_summary（原样重放，不检查当前 Revision）；
 *       pending / running / failed → 同一 Attempt 恢复或续跑，不创建第二个逻辑 Attempt；</li>
 *   <li>不可变 Policy（§49）：{@link #createAttemptWithItems} 把物化后的 policy_payload +
 *       policy_hash 与 Attempt / AttemptItems 在同一事务写入；执行前
 *       {@link #verifyAttemptPolicy} 重放校验 Hash；</li>
 *   <li>Lease / Heartbeat（§49.1）：{@link #claimForExecution} CAS 抢占，
 *       running + lease 过期可被 Recovery 重新抢占；</li>
 *   <li>组级执行（§31/§50.2）：{@link #beginGroup}（终态组跳过——Recovery 不重复建数据）、
 *       行事务内 {@link #applyRowOutcome}（领域写入与计数/终态同事务）、
 *       行回滚后 {@link #recordRowFailure}（独立事务——技术失败与业务拒绝分流）；</li>
 *   <li>Outcome 汇总（§49.2.3）：{@link #finalizeAttemptIfComplete} 从 AttemptItem 终态
 *       聚合 outcome + result_summary，进入 done 时同一事务物化。</li>
 * </ul>
 *
 * <p>事务边界：行成功/跳过路径由调用方（ExcelAiImportService 行事务）内嵌调用，
 * 与领域写入同生共死；行失败与 Attempt 级终态使用独立事务（{@link TransactionTemplate}），
 * 符合「事务回滚后的技术失败才用独立事务记 failed」（§31）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportAttemptService {

    /** Lease 时长（秒）：行循环心跳逐行续约，远大于单行耗时即可。 */
    public static final long LEASE_SECONDS = 300;

    /** 单 Item 结果摘要中保留的失败/说明条目上限（防爆）。 */
    private static final int MAX_ITEM_NOTES = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExcelImportAttemptMapper attemptMapper;
    private final ExcelImportAttemptItemMapper itemMapper;
    private final PlatformTransactionManager transactionManager;

    // ---------------------------------------------------------------- Replay-first（§50.0）

    /**
     * Replay-first 第 4 步：按 batch + revision + policy hash 查找既有 Attempt。
     */
    public Optional<ExcelImportAttempt> findByPolicyKey(String batchId, long previewRevision,
                                                        int policyHashVersion, String policyHash) {
        return Optional.ofNullable(attemptMapper.selectByPolicyKey(batchId, previewRevision,
            policyHashVersion, policyHash));
    }

    /**
     * done Attempt 重放校验（§50.0）：存储的 Policy Hash 完整性必须通过，
     * 否则视为快照/策略被篡改，拒绝重放。
     *
     * @throws BusinessException Hash 失配（409 语义）
     */
    public void verifyAttemptPolicy(ExcelImportAttempt attempt) {
        boolean ok;
        try {
            ok = ImportPolicyMaterializer.verify(attempt.getPolicyPayload(),
                attempt.getPolicyHashVersion(), attempt.getPolicyHash());
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            throw new BusinessException(409, "Import Attempt 的 Policy 完整性校验失败"
                + "（policy_hash 不匹配，attemptId=" + attempt.getAttemptId() + "），禁止重放/续跑");
        }
    }

    /**
     * 创建 Attempt + 全部 AttemptItems（与 confirm 事务同事务调用，§50.0 末分支：
     * INSERT immutable Attempt + AttemptItems）。
     *
     * <p>并发同键撞 uk_import_attempt 时按既有 Attempt 处理（Replay-first 竞态收敛）。</p>
     *
     * @param batchId    批次 ID
     * @param revision   已确认 Revision
     * @param policy     物化 Policy（正文 + Hash）
     * @param groups     该 Revision 的全部 Product Group
     * @param taskId     驱动任务 ID（可空，重试换绑由 rebindTask 负责）
     * @param createdBy  操作人
     * @return 新建或并发下既有的 Attempt
     */
    public ExcelImportAttempt createAttemptWithItems(String batchId, long revision,
                                                     ImportPolicyMaterializer.MaterializedPolicy policy,
                                                     List<ExcelImportProductGroup> groups,
                                                     String taskId, String createdBy) {
        ExcelImportAttempt attempt = new ExcelImportAttempt();
        attempt.setAttemptId(IdGenerator.generate("IATT"));
        attempt.setBatchId(batchId);
        attempt.setPreviewRevision(revision);
        attempt.setPolicySchemaVersion(com.rsdp.supplychain.policy.ImportPolicy.SCHEMA_VERSION);
        attempt.setPolicyPayload(policy.policyPayload());
        attempt.setPolicyHashVersion(policy.policyHashVersion());
        attempt.setPolicyHash(policy.policyHash());
        attempt.setStatus(ExcelImportAttempt.STATUS_PENDING);
        attempt.setResultSummary("{}");
        attempt.setTaskId(taskId);
        attempt.setRetryCount(0);
        attempt.setCreatedBy(createdBy);
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            // Replay-first 竞态：并发 confirm 同键 → 返回既有 Attempt（§50：不能有第二个逻辑 Attempt）
            ExcelImportAttempt existing = attemptMapper.selectByPolicyKey(batchId, revision,
                policy.policyHashVersion(), policy.policyHash());
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        for (ExcelImportProductGroup group : groups) {
            ExcelImportAttemptItem item = new ExcelImportAttemptItem();
            item.setAttemptId(attempt.getAttemptId());
            item.setBatchId(batchId);
            item.setPreviewRevision(revision);
            item.setGroupId(group.getGroupId());
            item.setStatus(ExcelImportAttemptItem.STATUS_PENDING);
            itemMapper.insert(item);
        }
        log.info("Import Attempt 已创建: attemptId={}, batchId={}, revision={}, groups={}, policyHash={}",
            attempt.getAttemptId(), batchId, revision, groups.size(), policy.policyHash());
        return attempt;
    }

    // ---------------------------------------------------------------- Lease / Heartbeat（§49.1）

    /**
     * 原子抢占执行租约（独立事务）：pending / failed / running+lease 过期 → running。
     *
     * @param attemptId Attempt ID
     * @param owner     执行 Worker 标识（异步任务 ID）
     * @return 抢占成功的 Attempt；已有活跃执行或已 done 返回 empty
     */
    public Optional<ExcelImportAttempt> claimForExecution(String attemptId, String owner) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int updated = attemptMapper.claimForExecution(attemptId, owner, LEASE_SECONDS);
            if (updated == 0) {
                return Optional.<ExcelImportAttempt>empty();
            }
            return Optional.of(attemptMapper.selectById(attemptId));
        });
    }

    /**
     * 运行期心跳续约；lease 易主/离开 running 时返回 false（调用方应中止执行）。
     */
    public boolean heartbeat(String attemptId, String owner) {
        try {
            Boolean ok = new TransactionTemplate(transactionManager).execute(status ->
                attemptMapper.heartbeat(attemptId, owner, LEASE_SECONDS) == 1);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("Attempt 心跳续约失败（不阻断当前行）: attemptId={}", attemptId, e);
            return true; // 心跳失败不阻断导入（与批次心跳口径一致）；lease 竞争由下一次 claim 兜底
        }
    }

    // ---------------------------------------------------------------- 组级执行（§31/§50.2）

    /**
     * 组执行开始（独立短事务）：SELECT FOR UPDATE 锁定 Item；终态
     * （imported/partial/rejected/skipped）返回 false——Recovery 直接跳过，不重复建数据
     * （§50.2 Commit 后崩溃场景）；failed（技术失败重试）/ running（崩溃遗留、
     * 租约接管）组以 committedRows 聚合基线复位后整组重跑——已提交行由调用方按
     * recoveredRows 跳过领域重做并恢复组上下文，未提交行正常执行；
     * 租约已易主抛 {@link AttemptLeaseLostException}。
     *
     * @param leaseOwner 当前 Worker 标识（租约守卫）
     * @return true = 本 Worker 应执行该组；false = 终态跳过
     */
    public boolean beginGroup(String attemptId, String groupId, String leaseOwner) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            ExcelImportAttemptItem item = itemMapper.selectForUpdate(attemptId, groupId);
            if (item == null) {
                throw new BusinessException("AttemptItem 不存在: attemptId=" + attemptId + ", groupId=" + groupId);
            }
            if (isTerminal(item.getStatus())) {
                return false;
            }
            if (ExcelImportAttemptItem.STATUS_PENDING.equals(item.getStatus())) {
                if (itemMapper.markRunning(attemptId, groupId, leaseOwner) == 0) {
                    throw new AttemptLeaseLostException(attemptId, leaseOwner);
                }
                return true;
            }
            // failed（技术失败重试）或 running（崩溃遗留、租约接管）：整组重跑，
            // 但已提交行不重做——摘要覆盖为 committedRows 聚合基线，恢复行只恢复组上下文
            RecoveryBase base = buildRecoveryBase(item);
            if (itemMapper.resetForRerun(attemptId, groupId, base.variantSuccess(),
                base.quoteSuccess(), base.quoteFailed(), base.summary(), leaseOwner) == 0) {
                throw new AttemptLeaseLostException(attemptId, leaseOwner);
            }
            item.setResultSummary(base.summary());
            item.setVariantSuccessCount(base.variantSuccess());
            item.setVariantFailedCount(0);
            item.setQuoteSuccessCount(base.quoteSuccess());
            item.setQuoteFailedCount(base.quoteFailed());
            return true;
        }));
    }

    /**
     * 从旧摘要的 committedRows 聚合重跑基线：恢复行清单（recoveredRows，含 rspuId/groupKey
     * 供调用方恢复组上下文）+ 已提交行的计数口径（行数/变体/报价）。
     * committedRows 按行号去重后完整保留，连续恢复始终从它派生基线。无已提交行时为空基线。
     */
    private RecoveryBase buildRecoveryBase(ExcelImportAttemptItem item) {
        ObjectNode base = MAPPER.createObjectNode();
        ArrayNode recovered = base.putArray("recoveredRows");
        ArrayNode committed = base.putArray("committedRows");
        Map<Integer, JsonNode> committedByRow = new LinkedHashMap<>();
        for (JsonNode c : readItemSummary(item).path("committedRows")) {
            if (c.path("row").isInt()) {
                committedByRow.put(c.path("row").asInt(), c);
            }
        }
        int rowsTotal = 0, variantOk = 0, quoteOk = 0, quoteFailed = 0;
        for (JsonNode c : committedByRow.values()) {
            // 保留完整计数和上下文，下一次崩溃恢复仍从同一清单生成基线。
            committed.add(c.deepCopy());
            ObjectNode r = recovered.addObject();
            r.put("row", c.path("row").asInt());
            r.put("rspuId", c.path("rspuId").asText(null));
            r.put("groupKey", c.path("groupKey").asText(null));
            rowsTotal++;
            variantOk += c.path("variants").asInt(0);
            quoteOk += c.path("quoteOk").asInt(0);
            quoteFailed += c.path("quoteFailed").asInt(0);
        }
        base.put("rowsTotal", rowsTotal);
        base.put("rowsSucceeded", rowsTotal);
        base.put("quotePolicyRejected", quoteFailed);
        return new RecoveryBase(base.toString(), variantOk, quoteOk, quoteFailed);
    }

    /** 重跑基线（已提交行聚合）：覆盖后的摘要 + 三个计数列。 */
    private record RecoveryBase(String summary, int variantSuccess, int quoteSuccess, int quoteFailed) {
    }

    /**
     * 行成功/跳过进度（调用方行事务内嵌调用——与该行领域写入同事务提交/回滚，§31）。
     *
     * @param item              当前 Item 标识（累计前在调用方行事务内重新加锁读取）
     * @param variantDelta      本行新增持久化 Variant 数
     * @param quoteOkDelta      本行报价成功数
     * @param quoteFailedDelta  本行报价被拒绝数（聚合阻断 / Policy 冲突 / upsert 失败）
     * @param rowSkipped        本行是否整体跳过（用户跳过行 / Issue Target 过滤 / 已存在跳过）
     * @param skipReason        跳过原因（可空）
     * @param rowIndex          展示行号（结果定位）
     * @param notes             本行需要保留进 Item 摘要的说明（有界截断）
     * @param lastRowOfGroup    是否本组最后一行：是则同事务物化组终态（§49.2.2）
     * @param leaseOwner        当前 Worker 标识（租约守卫；守卫失败抛 AttemptLeaseLostException，
     *                          当前行事务随调用方回滚）
     * @param rspuId            本行产出的 RSPU（成功行非空；null 表示无领域写入，
     *                          不进入 committedRows 恢复清单）
     * @param groupKey          本行所属逻辑产品组键
     */
    public void applyRowOutcome(ExcelImportAttemptItem item, int variantDelta,
                                int quoteOkDelta, int quoteFailedDelta,
                                boolean rowSkipped, String skipReason,
                                int rowIndex, List<String> notes, boolean lastRowOfGroup,
                                String leaseOwner, String rspuId, String groupKey) {
        String attemptId = item.getAttemptId();
        item = itemMapper.selectForUpdate(attemptId, item.getGroupId());
        if (item == null || !ExcelImportAttemptItem.STATUS_RUNNING.equals(item.getStatus())) {
            throw new AttemptLeaseLostException(attemptId, leaseOwner);
        }
        ObjectNode summary = readItemSummary(item);
        summary.put("rowsTotal", summary.path("rowsTotal").asInt(0) + 1);
        if (rowSkipped) {
            summary.put("rowsSkipped", summary.path("rowsSkipped").asInt(0) + 1);
            appendNote(summary, "notes", "行 " + rowIndex + " 跳过: " + (skipReason == null ? "" : skipReason));
        } else {
            summary.put("rowsSucceeded", summary.path("rowsSucceeded").asInt() + 1);
        }
        summary.put("quotePolicyRejected",
            summary.path("quotePolicyRejected").asInt(0) + quoteFailedDelta);
        if (notes != null) {
            for (String note : notes) {
                appendNote(summary, "notes", "行 " + rowIndex + ": " + note);
            }
        }
        // 领域写入已随本行事务提交的成功行记入 committedRows（含 rspuId/groupKey）：
        // 组重跑（技术失败重试 / 崩溃接管）时这些行按 recoveredRows 跳过重做，
        // 仅恢复组上下文——「导入前已存在」的行不在此列（无本批次领域写入），重跑仍走
        // 原有「已存在跳过」分支，两口径不混淆
        if (rspuId != null) {
            ObjectNode committed = summary.withArray("committedRows").addObject();
            committed.put("row", rowIndex);
            committed.put("rspuId", rspuId);
            if (groupKey != null) {
                committed.put("groupKey", groupKey);
            }
            committed.put("variants", variantDelta);
            committed.put("quoteOk", quoteOkDelta);
            committed.put("quoteFailed", quoteFailedDelta);
        }
        String newSummary = summary.toString();
        if (itemMapper.applyRowProgress(item.getAttemptId(), item.getGroupId(),
            variantDelta, 0, quoteOkDelta, quoteFailedDelta, "persisting", newSummary,
            leaseOwner) == 0) {
            // 租约已易主：抛出后当前行事务整体回滚（领域写入不留痕），由新持有者重跑
            throw new AttemptLeaseLostException(item.getAttemptId(), leaseOwner);
        }
        item.setVariantSuccessCount(item.getVariantSuccessCount() + variantDelta);
        item.setQuoteSuccessCount(item.getQuoteSuccessCount() + quoteOkDelta);
        item.setQuoteFailedCount(item.getQuoteFailedCount() + quoteFailedDelta);
        item.setResultSummary(newSummary);
        if (lastRowOfGroup) {
            finalizeItemInCurrentTx(item, summary, leaseOwner);
        }
    }

    /**
     * 行失败（行事务回滚后独立事务调用，§31：事务回滚后的技术失败用独立事务记 failed；
     * 确定性业务拒绝累计进摘要以供组终态判 rejected/partial）。
     *
     * @param attemptId    Attempt ID
     * @param groupId      Group ID
     * @param rowIndex     展示行号
     * @param message      失败原因
     * @param deterministic true = 确定性业务失败（BusinessException）；false = 技术失败
     * @param lastRowOfGroup 是否本组最后一行
     */
    public void recordRowFailure(String attemptId, String groupId, int rowIndex, String message,
                                 boolean deterministic, boolean lastRowOfGroup, String leaseOwner) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ExcelImportAttemptItem item = itemMapper.selectForUpdate(attemptId, groupId);
            if (item == null || isTerminal(item.getStatus())) {
                return;
            }
            ObjectNode summary = readItemSummary(item);
            summary.put("rowsTotal", summary.path("rowsTotal").asInt(0) + 1);
            summary.put("rowsFailed", summary.path("rowsFailed").asInt(0) + 1);
            if (!deterministic) {
                summary.put("technicalFailures", summary.path("technicalFailures").asInt(0) + 1);
            }
            ArrayNode failureNodes = summary.withArray("failures");
            if (failureNodes.size() < MAX_ITEM_NOTES) {
                ObjectNode failure = failureNodes.addObject();
                failure.put("row", rowIndex);
                failure.put("message", abbreviate(message));
            }
            item.setResultSummary(summary.toString());
            if (lastRowOfGroup) {
                finalizeItemInCurrentTx(item, summary, leaseOwner);
            } else {
                if (itemMapper.applyRowProgress(attemptId, groupId, 0, 0, 0, 0,
                    "persisting", summary.toString(), leaseOwner) == 0) {
                    throw new AttemptLeaseLostException(attemptId, leaseOwner);
                }
            }
        });
    }

    /**
     * 组末行统一收尾：普通行已在行事务内完成；恢复跳过的末行在此锁定最新累计结果并物化终态。
     *
     * @param attemptId Attempt ID
     * @param groupId 产品组 ID
     * @param leaseOwner 当前 Worker 租约标识
     */
    public void completeGroup(String attemptId, String groupId, String leaseOwner) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ExcelImportAttemptItem item = itemMapper.selectForUpdate(attemptId, groupId);
            if (item == null) {
                throw new BusinessException("AttemptItem 不存在: attemptId=" + attemptId + ", groupId=" + groupId);
            }
            if (ExcelImportAttemptItem.STATUS_RUNNING.equals(item.getStatus())) {
                finalizeItemInCurrentTx(item, readItemSummary(item), leaseOwner);
            }
        });
    }

    /** 组终态物化（当前事务内）：状态判定只看累计摘要（§49.2.1）；租约守卫失败抛出租约异常。 */
    private void finalizeItemInCurrentTx(ExcelImportAttemptItem item, ObjectNode summary,
                                         String leaseOwner) {
        String terminal = terminalStatusOf(item, summary);
        String checkpoint = ExcelImportAttemptItem.STATUS_FAILED.equals(terminal) ? "persisting" : "committed";
        summary.put("terminalStatus", terminal);
        if (itemMapper.finalizeItem(item.getAttemptId(), item.getGroupId(), terminal, checkpoint,
            summary.toString(), leaseOwner) == 0) {
            throw new AttemptLeaseLostException(item.getAttemptId(), leaseOwner);
        }
        item.setStatus(terminal);
    }

    /**
     * 组终态判定（§49.2.1）：
     * 技术失败 → failed（可重试）；全部跳过 → skipped；零成功且业务失败 → rejected；
     * 有成功但存在业务失败/被拒 Quote → partial；否则 imported。
     */
    private String terminalStatusOf(ExcelImportAttemptItem item, ObjectNode summary) {
        if (summary.path("technicalFailures").asInt(0) > 0) {
            return ExcelImportAttemptItem.STATUS_FAILED;
        }
        int succeeded = summary.path("rowsSucceeded").asInt(0);
        int failed = summary.path("rowsFailed").asInt(0);
        int skipped = summary.path("rowsSkipped").asInt(0);
        int quoteFailed = item.getQuoteFailedCount() == null ? 0 : item.getQuoteFailedCount();
        if (succeeded == 0 && failed == 0) {
            return ExcelImportAttemptItem.STATUS_SKIPPED;
        }
        if (succeeded == 0) {
            return ExcelImportAttemptItem.STATUS_REJECTED;
        }
        if (failed > 0 || quoteFailed > 0) {
            return ExcelImportAttemptItem.STATUS_PARTIAL;
        }
        return skipped > 0 && succeeded == 0 ? ExcelImportAttemptItem.STATUS_SKIPPED
            : ExcelImportAttemptItem.STATUS_IMPORTED;
    }

    // ---------------------------------------------------------------- Attempt 终态（§49.2.3）

    /**
     * Attempt 汇总终态：全部 Item 终态时聚合 outcome + result_summary 并物化 done
     * （同事务，§49.2.3）；尚有未完成 Item 时返回 empty。
     *
     * <p>outcome 规则（§49.2.3）：全部 imported/skipped → success；存在 partial，或
     * imported 与 rejected 并存 → partial_success；≥1 rejected 且其余仅
     * rejected/skipped → business_rejected。</p>
     *
     * @param attemptId Attempt ID
     * @param taskId    完成时的驱动任务（结果定位信息）
     * @return done 的 Attempt；未完成返回 empty
     */
    public Optional<ExcelImportAttempt> finalizeAttemptIfComplete(String attemptId, String taskId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ExcelImportAttempt attempt = attemptMapper.selectById(attemptId);
            if (attempt == null || !ExcelImportAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
                return Optional.<ExcelImportAttempt>empty();
            }
            List<ExcelImportAttemptItem> items = itemMapper.listByAttempt(attemptId);
            int imported = 0, partial = 0, rejected = 0, skipped = 0;
            int variantOk = 0, variantFailed = 0, quoteOk = 0, quoteFailed = 0;
            int rowsSucceeded = 0, rowsFailed = 0, rowsSkipped = 0;
            for (ExcelImportAttemptItem item : items) {
                switch (item.getStatus()) {
                    case ExcelImportAttemptItem.STATUS_IMPORTED -> imported++;
                    case ExcelImportAttemptItem.STATUS_PARTIAL -> partial++;
                    case ExcelImportAttemptItem.STATUS_REJECTED -> rejected++;
                    case ExcelImportAttemptItem.STATUS_SKIPPED -> skipped++;
                    default -> {
                        return Optional.<ExcelImportAttempt>empty(); // pending/running/failed 未完结
                    }
                }
                variantOk += nullToZero(item.getVariantSuccessCount());
                variantFailed += nullToZero(item.getVariantFailedCount());
                quoteOk += nullToZero(item.getQuoteSuccessCount());
                quoteFailed += nullToZero(item.getQuoteFailedCount());
                JsonNode summary = readTreeOrEmpty(item.getResultSummary());
                rowsSucceeded += summary.path("rowsSucceeded").asInt(0);
                rowsFailed += summary.path("rowsFailed").asInt(0);
                rowsSkipped += summary.path("rowsSkipped").asInt(0);
            }
            String outcome;
            if (rejected > 0 && imported == 0 && partial == 0) {
                outcome = ExcelImportAttempt.OUTCOME_BUSINESS_REJECTED;
            } else if (partial > 0 || (rejected > 0 && imported > 0)) {
                outcome = ExcelImportAttempt.OUTCOME_PARTIAL_SUCCESS;
            } else {
                outcome = ExcelImportAttempt.OUTCOME_SUCCESS;
            }
            ObjectNode summary = MAPPER.createObjectNode();
            summary.put("batchId", attempt.getBatchId());
            summary.put("previewRevision", attempt.getPreviewRevision());
            summary.put("policyHash", attempt.getPolicyHash());
            summary.put("policySchemaVersion", attempt.getPolicySchemaVersion());
            summary.put("taskId", taskId);
            ObjectNode groupCounts = summary.putObject("groupCounts");
            groupCounts.put("imported", imported);
            groupCounts.put("partial", partial);
            groupCounts.put("rejected", rejected);
            groupCounts.put("skipped", skipped);
            summary.put("variantSuccessCount", variantOk);
            summary.put("variantFailedCount", variantFailed);
            summary.put("quoteSuccessCount", quoteOk);
            summary.put("quoteFailedCount", quoteFailed);
            summary.put("rowsSucceeded", rowsSucceeded);
            summary.put("rowsFailed", rowsFailed);
            summary.put("rowsSkipped", rowsSkipped);
            summary.put("completedAt", LocalDateTime.now().toString());
            int updated = attemptMapper.markDone(attemptId, taskId, outcome, summary.toString());
            if (updated == 0) {
                return Optional.<ExcelImportAttempt>empty(); // 并发 finalize / lease 易主
            }
            ExcelImportAttempt done = attemptMapper.selectById(attemptId);
            log.info("Import Attempt 已完成: attemptId={}, outcome={}, groups(imported={}, partial={}, "
                    + "rejected={}, skipped={})", attemptId, outcome, imported, partial, rejected, skipped);
            return Optional.of(done);
        });
    }

    /**
     * Attempt 技术失败终态（独立事务，容错）：批次执行致命失败时调用。
     */
    public void markAttemptFailedQuietly(String attemptId, String leaseOwner,
                                         String errorCode, String errorMessage) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                attemptMapper.markFailed(attemptId, leaseOwner, errorCode, abbreviate(errorMessage)));
        } catch (Exception e) {
            log.error("标记 Attempt 失败状态异常: attemptId={}", attemptId, e);
        }
    }

    /** 重试/恢复时换绑驱动任务。 */
    public void rebindTask(String attemptId, String taskId) {
        attemptMapper.rebindTask(attemptId, taskId);
    }

    /** 读取 Attempt（执行链路校验用）。 */
    public ExcelImportAttempt getAttempt(String attemptId) {
        return attemptMapper.selectById(attemptId);
    }

    /** 读取 Item（行事务内嵌调用前由调用方读取最新值）。 */
    public ExcelImportAttemptItem getItem(String attemptId, String groupId) {
        List<ExcelImportAttemptItem> items = itemMapper.listByAttempt(attemptId);
        return items.stream().filter(i -> i.getGroupId().equals(groupId)).findFirst().orElse(null);
    }

    public static boolean isTerminal(String itemStatus) {
        return ExcelImportAttemptItem.STATUS_IMPORTED.equals(itemStatus)
            || ExcelImportAttemptItem.STATUS_PARTIAL.equals(itemStatus)
            || ExcelImportAttemptItem.STATUS_REJECTED.equals(itemStatus)
            || ExcelImportAttemptItem.STATUS_SKIPPED.equals(itemStatus);
    }

    /**
     * 聚合 Attempt 全部 Item 的行级失败明细（批次结果失败明细的权威来源，§49.2.3）。
     *
     * @return 行号升序的失败明细（rowIndex + reason）
     */
    public List<com.rsdp.dto.response.ExcelAiImportFailure> aggregateItemFailures(String attemptId) {
        List<com.rsdp.dto.response.ExcelAiImportFailure> failures = new ArrayList<>();
        for (ExcelImportAttemptItem item : itemMapper.listByAttempt(attemptId)) {
            JsonNode summary = readTreeOrEmpty(item.getResultSummary());
            JsonNode nodes = summary.path("failures");
            if (!nodes.isArray()) {
                continue;
            }
            for (JsonNode node : nodes) {
                failures.add(new com.rsdp.dto.response.ExcelAiImportFailure(
                    node.path("row").asInt(), node.path("message").asText("")));
            }
        }
        failures.sort(java.util.Comparator.comparing(f -> f.getRowIndex() == null ? 0 : f.getRowIndex()));
        return failures;
    }

    // ---------------------------------------------------------------- 内部工具

    private ObjectNode readItemSummary(ExcelImportAttemptItem item) {
        JsonNode node = readTreeOrEmpty(item.getResultSummary());
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("rowsTotal", node.path("rowsTotal").asInt(0));
        summary.put("rowsSucceeded", node.path("rowsSucceeded").asInt(0));
        summary.put("rowsFailed", node.path("rowsFailed").asInt(0));
        summary.put("rowsSkipped", node.path("rowsSkipped").asInt(0));
        summary.put("technicalFailures", node.path("technicalFailures").asInt(0));
        summary.put("quotePolicyRejected", node.path("quotePolicyRejected").asInt(0));
        if (node.path("notes").isArray()) {
            summary.set("notes", node.path("notes"));
        }
        if (node.path("failures").isArray()) {
            summary.set("failures", node.path("failures"));
        }
        // 恢复语义字段透传：committedRows（已提交行，重跑基线来源）与
        // recoveredRows（本轮重跑的恢复清单，组内后续行仍需据此判定跳过）
        if (node.path("committedRows").isArray()) {
            summary.set("committedRows", node.path("committedRows"));
        }
        if (node.path("recoveredRows").isArray()) {
            summary.set("recoveredRows", node.path("recoveredRows"));
        }
        return summary;
    }

    private JsonNode readTreeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private void appendNote(ObjectNode summary, String arrayField, String note) {
        ArrayNode array = summary.withArray(arrayField);
        if (array.size() < MAX_ITEM_NOTES) {
            array.add(note);
        }
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
