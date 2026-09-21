package com.rsdp.agent.service;

import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.patch.RequirementConstraints;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次 Agent Run 的内存上下文（按 runId 注册，运行结束移除）。
 *
 * <p>架构约定：SAA OverAllState 只放 ID（sessionId/runId/userMessage），
 * 候选产品、需求约束、累计的 assistant 文本等运行期对象放在本上下文，
 * 图节点经 runId 取用，避免大对象进 checkpoint 序列化。</p>
 */
@Getter
@Setter
public class AgentRunContext {

    private static final Map<String, AgentRunContext> REGISTRY = new ConcurrentHashMap<>();

    private final String sessionId;

    private final String runId;

    private final String operatorUserId;

    private final String userMessage;

    /** 当前需求约束（run 开始时加载，RequirementPatchNode 应用 patch 后更新）。 */
    private volatile RequirementConstraints constraints;

    /** 当前需求版本号（无版本时为 0）。 */
    private volatile int versionNo;

    /** 本轮开始前会话内连续追问轮次。 */
    private int followupCount;

    /** 本轮实际下发的检索条件（ProductSearchNode 写入，用于批次留痕）。 */
    private ProductSearchCriteria searchCriteria;

    /** 本轮检索结果（ProductSearchNode 写入，RecommendNode 消费）。 */
    private ProductSearchResult searchResult;

    /** assistant 文本累计（各节点流式输出时 append，done 时落 agent_message）。 */
    private final StringBuilder assistantText = new StringBuilder();

    /** assistant 消息类型是否为 notice（CONFIRM_ITEM 引导）。 */
    private boolean notice;

    /** assistant 消息是否为追问（metadata 留痕，供追问轮次统计）。 */
    private boolean followup;

    /** 本 run 已执行的节点轨迹（{node, label}，按执行顺序；done 时写入 assistant 消息 metadata）。 */
    private final List<Map<String, String>> steps = new ArrayList<>();

    public AgentRunContext(String sessionId, String runId, String operatorUserId, String userMessage) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.operatorUserId = operatorUserId;
        this.userMessage = userMessage;
    }

    /** 记录节点执行步骤（连续重复节点去重，如循环回边重入同一节点）。 */
    public synchronized void recordStep(String node, String label) {
        if (!steps.isEmpty()) {
            Map<String, String> last = steps.get(steps.size() - 1);
            if (last.get("node").equals(node)) {
                return;
            }
        }
        steps.add(Map.of("node", node, "label", label));
    }

    /** 节点轨迹（只读视图，按执行顺序）。 */
    public synchronized List<Map<String, String>> steps() {
        return List.copyOf(steps);
    }

    /** 追加 assistant 输出文本。 */
    public synchronized void appendAssistantText(String text) {
        if (text != null) {
            assistantText.append(text);
        }
    }

    /** 累计的 assistant 文本。 */
    public synchronized String assistantText() {
        return assistantText.toString();
    }

    /** 注册上下文（run 开始时调用）。 */
    public static void register(AgentRunContext context) {
        REGISTRY.put(context.getRunId(), context);
    }

    /** 按 runId 取上下文；不存在时抛错（节点不应在无上下文时执行）。 */
    public static AgentRunContext require(String runId) {
        AgentRunContext context = REGISTRY.get(runId);
        if (context == null) {
            throw new IllegalStateException("Agent 运行上下文不存在: " + runId);
        }
        return context;
    }

    /** 移除上下文（run 结束 finally 中调用）。 */
    public static void remove(String runId) {
        REGISTRY.remove(runId);
    }
}
