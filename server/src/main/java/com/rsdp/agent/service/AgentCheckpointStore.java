package com.rsdp.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * Agent checkpoint 存储适配层。
 *
 * <p>业务代码（图装配/运行记录）只面向本类，不直接依赖 SAA 的 PostgresSaver 类型。
 * threadId = runId：每轮用户消息即一次完整 Run（run 边界即 HITL 边界），
 * 不做图内中断恢复，checkpoint 仅用于运行留痕与排查。</p>
 *
 * <p>接线方式：复用应用主 DataSource，PostgresSaver 自建 GraphThread/GraphCheckpoint
 * 两张表（CREATE_IF_NOT_EXISTS）；构建失败（如库不可达）时降级 MemorySaver 并告警，
 * run 元信息仍以 agent_run 表为准，不影响主流程。</p>
 */
@Slf4j
@Component
public class AgentCheckpointStore {

    private final BaseCheckpointSaver saver;

    public AgentCheckpointStore(DataSource dataSource) {
        BaseCheckpointSaver resolved;
        try {
            resolved = PostgresSaver.builder()
                .datasource(dataSource)
                // stateSerializer 缺省与 StateGraph 默认一致（SpringAIJacksonStateSerializer）
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
            log.info("Agent checkpoint 使用 PostgresSaver（GraphThread/GraphCheckpoint 表）");
        } catch (Exception e) {
            // 降级说明：checkpoint 仅作留痕，run 状态以 agent_run 表为准，降级不阻断业务
            log.error("PostgresSaver 初始化失败，Agent checkpoint 降级为 MemorySaver（重启后 checkpoint 丢失）", e);
            resolved = new MemorySaver();
        }
        this.saver = resolved;
    }

    /** 底层 saver（图装配备 checkpoint 用）。 */
    public BaseCheckpointSaver saver() {
        return saver;
    }

    /**
     * 取某 thread（= runId）最新 checkpoint ID，用于回填 agent_run.checkpoint_id。
     *
     * @param threadId 线程 ID（runId）
     * @return 最新 checkpoint ID；无则空
     */
    public Optional<String> latestCheckpointId(String threadId) {
        try {
            return saver.get(RunnableConfig.builder().threadId(threadId).build())
                .map(checkpoint -> checkpoint.getId());
        } catch (Exception e) {
            log.warn("读取 checkpoint 失败，threadId={}", threadId, e);
            return Optional.empty();
        }
    }
}
