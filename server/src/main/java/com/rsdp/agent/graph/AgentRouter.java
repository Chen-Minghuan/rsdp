package com.rsdp.agent.graph;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
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
 *   <li>MATCH_COMPANION（P2）：会话有已确认主体 → 配套推荐；无 → 写操作引导（先确认主体）；</li>
 *   <li>REQUEST_QUOTE / EXPORT_SCHEME（P2）→ 写操作引导（写操作不进图，引导点面板按钮）；</li>
 *   <li>CONFIRM_REQUIREMENT：约束齐备 → 检索推荐；不齐备 → 追问（轮次耗尽也直接检索）；</li>
 *   <li>其余（NEW_REQUIREMENT/REFINE/FEEDBACK_MODIFY）：约束齐备或追问轮次耗尽 → 检索，否则追问。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final ConstraintCompleteness completeness;
    private final MarketingAgentProperties properties;
    private final AgentConfirmedItemMapper confirmedItemMapper;

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
        if (AgentStateKeys.INTENT_MATCH_COMPANION.equals(intent)) {
            return hasConfirmedItems(ctx.getSessionId())
                ? AgentStateKeys.ROUTE_COMPANION : AgentStateKeys.ROUTE_ACTION_HINT;
        }
        if (AgentStateKeys.INTENT_REQUEST_QUOTE.equals(intent)
            || AgentStateKeys.INTENT_EXPORT_SCHEME.equals(intent)) {
            return AgentStateKeys.ROUTE_ACTION_HINT;
        }
        boolean complete = completeness.isComplete(ctx.getConstraints());
        if (complete) {
            return AgentStateKeys.ROUTE_SEARCH;
        }
        // 约束不齐备：CONFIRM_REQUIREMENT 也先补齐关键约束；轮次耗尽则直接检索
        boolean followupLeft = ctx.getFollowupCount() < properties.getMaxFollowupRounds();
        return followupLeft ? AgentStateKeys.ROUTE_FOLLOWUP : AgentStateKeys.ROUTE_SEARCH;
    }

    /** 会话是否有 status=confirmed 的确认项（MATCH_COMPANION 的前置条件）。 */
    private boolean hasConfirmedItems(String sessionId) {
        Long count = confirmedItemMapper.selectCount(new QueryWrapper<AgentConfirmedItem>()
            .eq("session_id", sessionId)
            .eq("status", "confirmed"));
        return count != null && count > 0;
    }
}
