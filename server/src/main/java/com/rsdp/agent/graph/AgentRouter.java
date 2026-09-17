package com.rsdp.agent.graph;

import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.service.AgentRunContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 意图分流器：RequirementPatchNode 后条件边的路由决策。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>CHITCHAT（或意图无法识别）→ 闲聊节点；</li>
 *   <li>CONFIRM_ITEM → 引导用户点「确认这款」按钮（notice）；</li>
 *   <li>CONFIRM_REQUIREMENT：约束齐备 → 检索推荐；不齐备 → 追问（轮次耗尽也直接检索）；</li>
 *   <li>其余（NEW_REQUIREMENT/REFINE/FEEDBACK_MODIFY）：约束齐备或追问轮次耗尽 → 检索，否则追问。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final ConstraintCompleteness completeness;
    private final MarketingAgentProperties properties;

    /**
     * 计算路由。
     *
     * @param intent LLM 识别意图
     * @param ctx    运行上下文（含约束与追问轮次）
     * @return 路由键（{@link AgentStateKeys} ROUTE_*）
     */
    public String route(String intent, AgentRunContext ctx) {
        if (intent == null || intent.isBlank() || AgentStateKeys.INTENT_CHITCHAT.equals(intent)) {
            return AgentStateKeys.ROUTE_CHITCHAT;
        }
        if (AgentStateKeys.INTENT_CONFIRM_ITEM.equals(intent)) {
            return AgentStateKeys.ROUTE_CONFIRM_HINT;
        }
        boolean complete = completeness.isComplete(ctx.getConstraints());
        if (complete) {
            return AgentStateKeys.ROUTE_SEARCH;
        }
        // 约束不齐备：CONFIRM_REQUIREMENT 也先补齐关键约束；轮次耗尽则直接检索
        boolean followupLeft = ctx.getFollowupCount() < properties.getMaxFollowupRounds();
        return followupLeft ? AgentStateKeys.ROUTE_FOLLOWUP : AgentStateKeys.ROUTE_SEARCH;
    }
}
