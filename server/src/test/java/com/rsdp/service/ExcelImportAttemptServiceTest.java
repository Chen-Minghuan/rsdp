package com.rsdp.service;

import com.rsdp.entity.ExcelImportAttempt;
import com.rsdp.entity.ExcelImportAttemptItem;
import com.rsdp.entity.ExcelImportProductGroup;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ExcelImportAttemptItemMapper;
import com.rsdp.mapper.ExcelImportAttemptMapper;
import com.rsdp.supplychain.policy.ImportPolicy;
import com.rsdp.supplychain.policy.ImportPolicyMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelImportAttemptService} 单元测试（设计文档 v3.3 §48-§50，P0-F）。
 *
 * <p>覆盖：Replay-first 创建/竞态收敛、不可变 Policy Hash 校验、Lease CAS 抢占语义、
 * 组级 beginGroup 终态跳过（Recovery 不重复建数据）、行计数累计与组终态判定
 * （§49.2.1 全分支）、Attempt outcome 汇总（§49.2.3 全分支）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportAttemptServiceTest {

    /** 当前 Worker（租约持有者）标识。 */
    private static final String OWNER = "TASK-1";


    @InjectMocks
    private ExcelImportAttemptService attemptService;

    @Mock
    private ExcelImportAttemptMapper attemptMapper;
    @Mock
    private ExcelImportAttemptItemMapper itemMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // 租约守卫 SQL 默认放行（影响 1 行）；租约易主场景由各用例显式 stub 0
        lenient().when(itemMapper.applyRowProgress(anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyString(), anyString(), anyString())).thenReturn(1);
        lenient().when(itemMapper.finalizeItem(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString())).thenReturn(1);
        lenient().when(itemMapper.markRunning(anyString(), anyString(), anyString())).thenReturn(1);
        lenient().when(itemMapper.resetForRerun(anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyString(), anyString())).thenReturn(1);
    }

    // ---------------------------------------------------------------- Replay-first / 创建

    @Test
    void createAttemptWithItems_insertsAttemptAndAllItems() {
        ImportPolicyMaterializer.MaterializedPolicy policy =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        List<ExcelImportProductGroup> groups = List.of(group("G-1"), group("G-2"), group("G-3"));

        ExcelImportAttempt attempt = attemptService.createAttemptWithItems("B-1", 7L, policy, groups,
            "TASK-1", "tester");

        assertEquals("B-1", attempt.getBatchId());
        assertEquals(7L, attempt.getPreviewRevision());
        assertEquals(ExcelImportAttempt.STATUS_PENDING, attempt.getStatus());
        assertEquals(policy.policyPayload(), attempt.getPolicyPayload());
        assertEquals(policy.policyHash(), attempt.getPolicyHash());
        assertEquals(policy.policyHashVersion(), attempt.getPolicyHashVersion());
        assertEquals(ImportPolicy.SCHEMA_VERSION, attempt.getPolicySchemaVersion());
        assertEquals(0, attempt.getRetryCount());
        verify(attemptMapper).insert(attempt);
        ArgumentCaptor<ExcelImportAttemptItem> captor = ArgumentCaptor.forClass(ExcelImportAttemptItem.class);
        verify(itemMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertEquals(List.of("G-1", "G-2", "G-3"),
            captor.getAllValues().stream().map(ExcelImportAttemptItem::getGroupId).toList());
        assertTrue(captor.getAllValues().stream()
            .allMatch(i -> ExcelImportAttemptItem.STATUS_PENDING.equals(i.getStatus())));
    }

    @Test
    void createAttemptWithItems_duplicateKey_returnsExistingAttempt() {
        ImportPolicyMaterializer.MaterializedPolicy policy =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        ExcelImportAttempt existing = new ExcelImportAttempt();
        existing.setAttemptId("IATT-EXISTING");
        existing.setStatus(ExcelImportAttempt.STATUS_RUNNING);
        when(attemptMapper.selectByPolicyKey("B-1", 7L, policy.policyHashVersion(), policy.policyHash()))
            .thenReturn(existing);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_import_attempt"))
            .when(attemptMapper).insert(any(ExcelImportAttempt.class));

        ExcelImportAttempt result = attemptService.createAttemptWithItems("B-1", 7L, policy,
            List.of(group("G-1")), null, "tester");

        assertEquals("IATT-EXISTING", result.getAttemptId());
        verify(itemMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- 不可变 Policy 校验

    @Test
    void verifyAttemptPolicy_acceptsUntampered() {
        ImportPolicyMaterializer.MaterializedPolicy policy =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        ExcelImportAttempt attempt = new ExcelImportAttempt();
        attempt.setAttemptId("IATT-1");
        attempt.setPolicyPayload(policy.policyPayload());
        attempt.setPolicyHashVersion(policy.policyHashVersion());
        attempt.setPolicyHash(policy.policyHash());
        attemptService.verifyAttemptPolicy(attempt); // 不抛即通过
    }

    @Test
    void verifyAttemptPolicy_rejectsTamperedPayload() {
        ImportPolicyMaterializer.MaterializedPolicy policy =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        ExcelImportAttempt attempt = new ExcelImportAttempt();
        attempt.setAttemptId("IATT-1");
        attempt.setPolicyPayload(policy.policyPayload().replace("fail_group", "skip_invalid_target"));
        attempt.setPolicyHashVersion(policy.policyHashVersion());
        attempt.setPolicyHash(policy.policyHash());
        BusinessException e = assertThrows(BusinessException.class,
            () -> attemptService.verifyAttemptPolicy(attempt));
        assertTrue(e.getMessage().contains("policy_hash"));
    }

    // ---------------------------------------------------------------- Lease CAS

    @Test
    void claimForExecution_casSuccess_returnsAttempt() {
        ExcelImportAttempt attempt = new ExcelImportAttempt();
        attempt.setAttemptId("IATT-1");
        attempt.setStatus(ExcelImportAttempt.STATUS_RUNNING);
        when(attemptMapper.claimForExecution(eq("IATT-1"), eq("TASK-9"), anyLong())).thenReturn(1);
        when(attemptMapper.selectById("IATT-1")).thenReturn(attempt);

        Optional<ExcelImportAttempt> claimed = attemptService.claimForExecution("IATT-1", "TASK-9");
        assertTrue(claimed.isPresent());
        assertEquals("IATT-1", claimed.get().getAttemptId());
    }

    @Test
    void claimForExecution_casConflict_returnsEmpty() {
        // running + lease 未过期 / 已 done：CAS 0 行，不返回 Attempt（§49.1）
        when(attemptMapper.claimForExecution(eq("IATT-1"), eq("TASK-9"), anyLong())).thenReturn(0);
        assertTrue(attemptService.claimForExecution("IATT-1", "TASK-9").isEmpty());
        verify(attemptMapper, never()).selectById(anyString());
    }

    // ---------------------------------------------------------------- 组级执行

    @Test
    void beginGroup_terminalStatus_skipsWithoutMarkingRunning() {
        // Recovery 语义（§50.2）：Commit 后崩溃重跑时终态组直接跳过，不重复建数据
        for (String terminal : new String[]{ExcelImportAttemptItem.STATUS_IMPORTED,
            ExcelImportAttemptItem.STATUS_PARTIAL, ExcelImportAttemptItem.STATUS_REJECTED,
            ExcelImportAttemptItem.STATUS_SKIPPED}) {
            ExcelImportAttemptItem item = freshItem(terminal);
            when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
            assertFalse(attemptService.beginGroup("IATT-1", "G-1", OWNER), "终态 " + terminal + " 应跳过");
        }
        verify(itemMapper, never()).markRunning(anyString(), anyString(), anyString());
    }

    @Test
    void beginGroup_nonTerminal_marksRunning() {
        for (String nonTerminal : new String[]{ExcelImportAttemptItem.STATUS_PENDING,
            ExcelImportAttemptItem.STATUS_RUNNING}) {
            ExcelImportAttemptItem item = freshItem(nonTerminal);
            when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
            assertTrue(attemptService.beginGroup("IATT-1", "G-1", OWNER), "非终态 " + nonTerminal + " 应继续执行");
        }
    }

    @Test
    void beginGroup_failed_resetsSummaryForRetry() {
        // 技术失败组重试（§49.2.1 修复）：摘要覆盖为已提交行聚合基线——否则上轮
        // technicalFailures 会让 terminalStatusOf 永久判 failed
        // （首次技术失败、再次成功的完整链路见 retryAfterTechnicalFailure_allRowsSucceed_marksImported）
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_FAILED);
        item.setResultSummary("{\"rowsTotal\":3,\"rowsSucceeded\":2,\"rowsFailed\":1,\"technicalFailures\":1}");
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);

        assertTrue(attemptService.beginGroup("IATT-1", "G-1", OWNER));
        // 无 committedRows → 空基线（计数清零）
        verify(itemMapper).resetForRerun(eq("IATT-1"), eq("G-1"), eq(0), eq(0), eq(0),
            org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"recoveredRows\":[]")
                && !json.contains("technicalFailures")), eq(OWNER));
        verify(itemMapper, never()).markRunning(anyString(), anyString(), anyString());
    }

    @Test
    void beginGroup_staleRunningFromCrash_recoversWithCommittedBaseline() {
        // 崩溃遗留 running 组（租约接管）：与技术失败同规则——已提交行进恢复清单并保留计数，
        // 未提交行重跑；旧 technicalFailures 不再污染终态判定
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        item.setResultSummary("{\"rowsTotal\":2,\"rowsSucceeded\":1,\"rowsFailed\":1,"
            + "\"technicalFailures\":1,\"committedRows\":[{\"row\":2,\"rspuId\":\"RSPU-A\","
            + "\"groupKey\":\"S001\",\"variants\":1,\"quoteOk\":1,\"quoteFailed\":0}]}");
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);

        assertTrue(attemptService.beginGroup("IATT-1", "G-1", OWNER));
        verify(itemMapper).resetForRerun(eq("IATT-1"), eq("G-1"), eq(1), eq(1), eq(0),
            org.mockito.ArgumentMatchers.argThat(json -> json.contains("RSPU-A")
                && json.contains("\"rowsSucceeded\":1") && !json.contains("technicalFailures")),
            eq(OWNER));
        // 服务内存中的 Item 同步为基线摘要（tracker 随后 getItem 读到恢复清单）
        assertTrue(item.getResultSummary().contains("recoveredRows"));
    }

    @Test
    void beginGroup_leaseLost_throws() {
        // 租约守卫（§49.1）：markRunning 影响 0 行 = lease_owner 已易主 → 立即中止
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_PENDING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        when(itemMapper.markRunning("IATT-1", "G-1", OWNER)).thenReturn(0);
        assertThrows(com.rsdp.exception.AttemptLeaseLostException.class,
            () -> attemptService.beginGroup("IATT-1", "G-1", OWNER));
    }

    @Test
    void retryAfterTechnicalFailure_allRowsSucceed_marksImported() {
        // 完整链路：首次技术失败 → 重试复位 → 全部行成功 → 组终态 imported（不再误判 failed）
        ExcelImportAttemptItem failedItem = freshItem(ExcelImportAttemptItem.STATUS_FAILED);
        failedItem.setResultSummary("{\"rowsTotal\":3,\"rowsSucceeded\":2,\"rowsFailed\":1,\"technicalFailures\":1}");
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(failedItem);
        assertTrue(attemptService.beginGroup("IATT-1", "G-1", OWNER));

        // 重跑：复位后的基线摘要（旧 technicalFailures 已剔除），逐行成功
        ExcelImportAttemptItem retried = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        retried.setResultSummary(failedItem.getResultSummary());
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(retried);
        attemptService.applyRowOutcome(retried, 1, 1, 0, false, null, 3, List.of(), false, OWNER, "RSPU-1", "G-1");
        attemptService.applyRowOutcome(retried, 1, 1, 0, false, null, 4, List.of(), false, OWNER, "RSPU-1", "G-1");
        attemptService.applyRowOutcome(retried, 1, 1, 0, false, null, 5, List.of(), true, OWNER, "RSPU-1", "G-1");

        assertEquals(ExcelImportAttemptItem.STATUS_IMPORTED, retried.getStatus());
        verify(itemMapper).finalizeItem(eq("IATT-1"), eq("G-1"),
            eq(ExcelImportAttemptItem.STATUS_IMPORTED), eq("committed"), anyString(), eq(OWNER));
    }

    // ---------------------------------------------------------------- 行计数与组终态（§49.2.1）

    @Test
    void applyRowOutcome_successRow_appendsCommittedEntry() {
        // 成功行（有领域写入）记入 committedRows：组重跑时按 recoveredRows 恢复
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 1, 2, 0, false, null, 5, List.of(), false,
            OWNER, "RSPU-1", "S001");
        String summary = item.getResultSummary();
        assertTrue(summary.contains("\"committedRows\":[{\"row\":5"), summary);
        assertTrue(summary.contains("\"rspuId\":\"RSPU-1\""), summary);
        assertTrue(summary.contains("\"groupKey\":\"S001\""), summary);
    }

    @Test
    void applyRowOutcome_skippedRow_notCommitted() {
        // 跳过行无领域写入：不进 committedRows（重跑时仍走原有跳过分支）
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 0, 0, 0, true, "已存在跳过", 5, List.of(), false,
            OWNER, null, "S001");
        assertFalse(item.getResultSummary().contains("committedRows"));
    }

    @Test
    void applyRowOutcome_lastRow_allSucceeded_finalizesImported() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 1, 2, 0, false, null, 5, List.of(), true, OWNER, "RSPU-1", "G-1");
        assertEquals(ExcelImportAttemptItem.STATUS_IMPORTED, item.getStatus());
        verify(itemMapper).finalizeItem(eq("IATT-1"), eq("G-1"),
            eq(ExcelImportAttemptItem.STATUS_IMPORTED), eq("committed"), anyString(), eq(OWNER));
    }

    @Test
    void applyRowOutcome_quoteRejected_marksPartial() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        // 有行成功但报价被 Policy 拒绝 → partial（§49.2.1）
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 1, 0, 2, false, null, 5, List.of("报价被拒"), true, OWNER, "RSPU-1", "G-1");
        assertEquals(ExcelImportAttemptItem.STATUS_PARTIAL, item.getStatus());
    }

    @Test
    void applyRowOutcome_allSkipped_marksSkipped() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 0, 0, 0, true, "用户标记跳过", 5, List.of(), true, OWNER, null, null);
        assertEquals(ExcelImportAttemptItem.STATUS_SKIPPED, item.getStatus());
        verify(itemMapper).finalizeItem(eq("IATT-1"), eq("G-1"),
            eq(ExcelImportAttemptItem.STATUS_SKIPPED), eq("committed"), anyString(), eq(OWNER));
    }

    @Test
    void applyRowOutcome_notLastRow_onlyProgresses() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.applyRowOutcome(item, 1, 1, 0, false, null, 3, List.of(), false, OWNER, "RSPU-1", "G-1");
        assertEquals(ExcelImportAttemptItem.STATUS_RUNNING, item.getStatus());
        verify(itemMapper).applyRowProgress(eq("IATT-1"), eq("G-1"), eq(1), eq(0), eq(1), eq(0),
            eq("persisting"), anyString(), eq(OWNER));
        verify(itemMapper, never()).finalizeItem(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString());
    }

    @Test
    void recordRowFailure_deterministic_noSuccess_marksRejected() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        // 确定性业务失败（非技术失败）+ 零成功 → rejected
        attemptService.recordRowFailure("IATT-1", "G-1", 5, "材质码未识别", true, true, OWNER);
        assertEquals(ExcelImportAttemptItem.STATUS_REJECTED, item.getStatus());
    }

    @Test
    void recordRowFailure_technical_marksFailed() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.recordRowFailure("IATT-1", "G-1", 5, "DB 连接中断", false, true, OWNER);
        assertEquals(ExcelImportAttemptItem.STATUS_FAILED, item.getStatus());
        verify(itemMapper).finalizeItem(eq("IATT-1"), eq("G-1"),
            eq(ExcelImportAttemptItem.STATUS_FAILED), eq("persisting"), anyString(), eq(OWNER));
    }

    @Test
    void recordRowFailure_terminalItem_ignored() {
        // 行事务回滚后 Item 已被并发流终态化：不再累计（幂等）
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_IMPORTED);
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.recordRowFailure("IATT-1", "G-1", 5, "迟到失败", false, true, OWNER);
        verify(itemMapper, never()).applyRowProgress(anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(itemMapper, never()).finalizeItem(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString());
    }

    @Test
    void repeatedRecovery_preservesAllCommittedRowsAndDeduplicates() throws Exception {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        String committed = """
            {"row":2,"rspuId":"RSPU-1","groupKey":"A","variants":1,"quoteOk":2,"quoteFailed":1}
            """.trim();
        item.setResultSummary("{\"committedRows\":[" + committed + "," + committed + "]}");
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.beginGroup("IATT-1", "G-1", OWNER);
        // 第一次恢复后又提交一行，再次崩溃；两次恢复都不能丢失历史清单。
        attemptService.applyRowOutcome(item, 2, 3, 0, false, null, 3, List.of(),
            false, OWNER, "RSPU-1", "A");
        attemptService.beginGroup("IATT-1", "G-1", OWNER);
        attemptService.beginGroup("IATT-1", "G-1", OWNER);
        var summary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.getResultSummary());
        assertEquals(2, summary.path("committedRows").size());
        assertEquals(2, summary.path("recoveredRows").size());
        assertEquals(2, summary.path("rowsSucceeded").asInt());
        assertEquals(3, item.getVariantSuccessCount());
        assertEquals(5, item.getQuoteSuccessCount());
        assertEquals(1, item.getQuoteFailedCount());
    }

    @Test
    void successAfterFailure_usesLockedStateInsteadOfStaleTracker() throws Exception {
        for (boolean businessFailure : new boolean[]{true, false}) {
            ExcelImportAttemptItem stale = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
            ExcelImportAttemptItem persisted = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
            when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(persisted);
            attemptService.recordRowFailure("IATT-1", "G-1", 2, "前行失败",
                businessFailure, false, OWNER);
            attemptService.applyRowOutcome(stale, 1, 1, 0, false, null, 3, List.of(),
                true, OWNER, "RSPU-1", "A");
            var summary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(persisted.getResultSummary());
            assertEquals(2, summary.path("rowsTotal").asInt());
            assertEquals(1, summary.path("rowsFailed").asInt());
            assertEquals(2, summary.path("failures").get(0).path("row").asInt());
            assertEquals(businessFailure ? ExcelImportAttemptItem.STATUS_PARTIAL
                : ExcelImportAttemptItem.STATUS_FAILED, persisted.getStatus());
        }
    }

    @Test
    void recoveredLastRow_completesGroupWithoutCountingItAgain() {
        ExcelImportAttemptItem item = freshItem(ExcelImportAttemptItem.STATUS_RUNNING);
        item.setResultSummary("{\"rowsTotal\":2,\"rowsSucceeded\":2}");
        when(itemMapper.selectForUpdate("IATT-1", "G-1")).thenReturn(item);
        attemptService.completeGroup("IATT-1", "G-1", OWNER);
        attemptService.completeGroup("IATT-1", "G-1", OWNER);
        assertEquals(ExcelImportAttemptItem.STATUS_IMPORTED, item.getStatus());
        verify(itemMapper).finalizeItem(eq("IATT-1"), eq("G-1"), eq("imported"),
            eq("committed"), org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"rowsTotal\":2")), eq(OWNER));
        verify(itemMapper, never()).applyRowProgress(anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void recordRowFailure_leaseLost_aborts() {
        when(itemMapper.selectForUpdate("IATT-1", "G-1"))
            .thenReturn(freshItem(ExcelImportAttemptItem.STATUS_RUNNING));
        when(itemMapper.applyRowProgress(anyString(), anyString(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyString(), anyString(), anyString())).thenReturn(0);
        assertThrows(com.rsdp.exception.AttemptLeaseLostException.class,
            () -> attemptService.recordRowFailure("IATT-1", "G-1", 2, "失败", false, false, OWNER));
    }

    // ---------------------------------------------------------------- Attempt 汇总（§49.2.3）

    @Test
    void finalizeAttempt_notAllTerminal_returnsEmpty() {
        ExcelImportAttempt attempt = runningAttempt();
        when(attemptMapper.selectById("IATT-1")).thenReturn(attempt);
        when(itemMapper.listByAttempt("IATT-1")).thenReturn(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED),
            freshItem(ExcelImportAttemptItem.STATUS_RUNNING)));
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isEmpty());
        verify(attemptMapper, never()).markDone(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void finalizeAttempt_notRunning_returnsEmpty() {
        ExcelImportAttempt attempt = runningAttempt();
        attempt.setStatus(ExcelImportAttempt.STATUS_DONE);
        when(attemptMapper.selectById("IATT-1")).thenReturn(attempt);
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isEmpty());
    }

    @Test
    void finalizeAttempt_allImportedOrSkipped_outcomeSuccess() {
        stubFinalizable(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED),
            terminalItem("G-2", ExcelImportAttemptItem.STATUS_SKIPPED)));
        Optional<ExcelImportAttempt> done = attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1");
        assertTrue(done.isPresent());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(attemptMapper).markDone(eq("IATT-1"), eq("TASK-1"), outcome.capture(), anyString());
        assertEquals(ExcelImportAttempt.OUTCOME_SUCCESS, outcome.getValue());
    }

    @Test
    void finalizeAttempt_hasPartial_outcomePartialSuccess() {
        stubFinalizable(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED),
            terminalItem("G-2", ExcelImportAttemptItem.STATUS_PARTIAL)));
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isPresent());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(attemptMapper).markDone(eq("IATT-1"), eq("TASK-1"), outcome.capture(), anyString());
        assertEquals(ExcelImportAttempt.OUTCOME_PARTIAL_SUCCESS, outcome.getValue());
    }

    @Test
    void finalizeAttempt_importedPlusRejected_outcomePartialSuccess() {
        stubFinalizable(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED),
            terminalItem("G-2", ExcelImportAttemptItem.STATUS_REJECTED)));
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isPresent());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(attemptMapper).markDone(eq("IATT-1"), eq("TASK-1"), outcome.capture(), anyString());
        assertEquals(ExcelImportAttempt.OUTCOME_PARTIAL_SUCCESS, outcome.getValue());
    }

    @Test
    void finalizeAttempt_rejectedWithOnlySkipped_outcomeBusinessRejected() {
        stubFinalizable(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_REJECTED),
            terminalItem("G-2", ExcelImportAttemptItem.STATUS_SKIPPED)));
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isPresent());
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(attemptMapper).markDone(eq("IATT-1"), eq("TASK-1"), outcome.capture(), anyString());
        assertEquals(ExcelImportAttempt.OUTCOME_BUSINESS_REJECTED, outcome.getValue());
    }

    @Test
    void finalizeAttempt_resultSummaryAggregatesItemCounters() {
        ExcelImportAttemptItem g1 = terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED);
        g1.setVariantSuccessCount(3);
        g1.setQuoteSuccessCount(2);
        g1.setResultSummary("{\"rowsSucceeded\":4,\"rowsFailed\":0,\"rowsSkipped\":0}");
        ExcelImportAttemptItem g2 = terminalItem("G-2", ExcelImportAttemptItem.STATUS_PARTIAL);
        g2.setVariantSuccessCount(1);
        g2.setQuoteFailedCount(2);
        g2.setResultSummary("{\"rowsSucceeded\":1,\"rowsFailed\":1,\"rowsSkipped\":1}");
        stubFinalizable(List.of(g1, g2));

        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isPresent());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(attemptMapper).markDone(eq("IATT-1"), eq("TASK-1"), anyString(), summary.capture());
        String json = summary.getValue();
        assertTrue(json.contains("\"variantSuccessCount\":4"), json);
        assertTrue(json.contains("\"quoteSuccessCount\":2"), json);
        assertTrue(json.contains("\"quoteFailedCount\":2"), json);
        assertTrue(json.contains("\"rowsSucceeded\":5"), json);
        assertTrue(json.contains("\"rowsFailed\":1"), json);
        assertTrue(json.contains("\"rowsSkipped\":1"), json);
        assertTrue(json.contains("\"imported\":1"), json);
        assertTrue(json.contains("\"partial\":1"), json);
    }

    @Test
    void finalizeAttempt_markDoneCasConflict_returnsEmpty() {
        ExcelImportAttempt attempt = runningAttempt();
        when(attemptMapper.selectById("IATT-1")).thenReturn(attempt);
        when(itemMapper.listByAttempt("IATT-1")).thenReturn(List.of(
            terminalItem("G-1", ExcelImportAttemptItem.STATUS_IMPORTED)));
        when(attemptMapper.markDone(eq("IATT-1"), eq("TASK-1"), anyString(), anyString())).thenReturn(0);
        assertTrue(attemptService.finalizeAttemptIfComplete("IATT-1", "TASK-1").isEmpty());
    }

    // ---------------------------------------------------------------- 辅助

    private ExcelImportProductGroup group(String groupId) {
        ExcelImportProductGroup g = new ExcelImportProductGroup();
        g.setGroupId(groupId);
        return g;
    }

    private ExcelImportAttemptItem freshItem(String status) {
        ExcelImportAttemptItem item = new ExcelImportAttemptItem();
        item.setAttemptId("IATT-1");
        item.setGroupId("G-1");
        item.setStatus(status);
        item.setVariantSuccessCount(0);
        item.setVariantFailedCount(0);
        item.setQuoteSuccessCount(0);
        item.setQuoteFailedCount(0);
        item.setResultSummary("{}");
        return item;
    }

    private ExcelImportAttemptItem terminalItem(String groupId, String status) {
        ExcelImportAttemptItem item = freshItem(status);
        item.setGroupId(groupId);
        return item;
    }

    private ExcelImportAttempt runningAttempt() {
        ExcelImportAttempt attempt = new ExcelImportAttempt();
        attempt.setAttemptId("IATT-1");
        attempt.setBatchId("B-1");
        attempt.setPreviewRevision(7L);
        attempt.setStatus(ExcelImportAttempt.STATUS_RUNNING);
        attempt.setPolicySchemaVersion(ImportPolicy.SCHEMA_VERSION);
        attempt.setPolicyHash("hash-1");
        return attempt;
    }

    private void stubFinalizable(List<ExcelImportAttemptItem> items) {
        ExcelImportAttempt attempt = runningAttempt();
        when(attemptMapper.selectById("IATT-1")).thenReturn(attempt);
        when(itemMapper.listByAttempt("IATT-1")).thenReturn(items);
        when(attemptMapper.markDone(eq("IATT-1"), eq("TASK-1"), anyString(), anyString())).thenReturn(1);
    }
}
