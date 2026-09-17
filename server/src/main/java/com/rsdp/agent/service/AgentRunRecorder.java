package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.agent.entity.AgentRun;
import com.rsdp.agent.mapper.AgentRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * agent_run 运行记录读写（节点进度/终态/checkpoint 回填）。
 */
@Component
@RequiredArgsConstructor
public class AgentRunRecorder {

    /** SAA 框架版本标识（留痕用）。 */
    public static final String FRAMEWORK_VERSION = "saa-graph-1.1.2.3";

    private final AgentRunMapper runMapper;

    /** 创建 running 状态的运行记录。 */
    public void start(AgentRun run) {
        run.setStatus("running");
        run.setFrameworkVersion(FRAMEWORK_VERSION);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
    }

    /** 记录当前执行节点（排障用，失败不影响主流程）。 */
    public void updateCurrentNode(String runId, String node) {
        try {
            runMapper.update(null, new UpdateWrapper<AgentRun>()
                .eq("run_id", runId)
                .set("current_node", node));
        } catch (Exception e) {
            // 进度留痕失败不阻断运行
        }
    }

    /** 标记运行成功结束（仅 running 终态可流转，防与超时标记竞态互相覆盖）。 */
    public void markDone(String runId) {
        runMapper.update(null, new UpdateWrapper<AgentRun>()
            .eq("run_id", runId)
            .eq("status", "running")
            .set("status", "done")
            .set("finished_at", LocalDateTime.now()));
    }

    /** 标记运行失败（仅 running 终态可流转）。 */
    public void markFailed(String runId, String errorCode) {
        runMapper.update(null, new UpdateWrapper<AgentRun>()
            .eq("run_id", runId)
            .eq("status", "running")
            .set("status", "failed")
            .set("error_code", errorCode)
            .set("finished_at", LocalDateTime.now()));
    }

    /** 回填框架 checkpoint 标识。 */
    public void updateCheckpointId(String runId, String checkpointId) {
        if (checkpointId == null) {
            return;
        }
        try {
            runMapper.update(null, new UpdateWrapper<AgentRun>()
                .eq("run_id", runId)
                .set("checkpoint_id", checkpointId));
        } catch (Exception e) {
            // checkpoint 留痕失败不阻断
        }
    }
}
