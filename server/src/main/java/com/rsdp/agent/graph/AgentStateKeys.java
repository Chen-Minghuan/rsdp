package com.rsdp.agent.graph;

/**
 * Agent 图常量：节点名 / 状态键 / 路由键 / 意图 / 节点展示文案。
 */
public final class AgentStateKeys {

    private AgentStateKeys() {
    }

    // ==================== 节点名 ====================
    public static final String NODE_REQUIREMENT_PATCH = "requirement_patch";
    public static final String NODE_FOLLOWUP = "followup";
    public static final String NODE_PRODUCT_SEARCH = "product_search";
    public static final String NODE_RECOMMEND = "recommend";
    public static final String NODE_CHITCHAT = "chitchat";
    public static final String NODE_CONFIRM_HINT = "confirm_hint";

    // ==================== OverAllState 键（只放 ID 与小字符串） ====================
    public static final String STATE_SESSION_ID = "sessionId";
    public static final String STATE_RUN_ID = "runId";
    public static final String STATE_USER_MESSAGE = "userMessage";
    public static final String STATE_INTENT = "intent";

    // ==================== 条件边路由键 ====================
    public static final String ROUTE_FOLLOWUP = "route_followup";
    public static final String ROUTE_SEARCH = "route_search";
    public static final String ROUTE_CHITCHAT = "route_chitchat";
    public static final String ROUTE_CONFIRM_HINT = "route_confirm_hint";

    // ==================== 意图（LLM 输出） ====================
    public static final String INTENT_NEW_REQUIREMENT = "NEW_REQUIREMENT";
    public static final String INTENT_REFINE = "REFINE";
    public static final String INTENT_FEEDBACK_MODIFY = "FEEDBACK_MODIFY";
    public static final String INTENT_CONFIRM_REQUIREMENT = "CONFIRM_REQUIREMENT";
    public static final String INTENT_CONFIRM_ITEM = "CONFIRM_ITEM";
    public static final String INTENT_CHITCHAT = "CHITCHAT";

    // ==================== 节点展示文案（SSE node 事件 label） ====================
    public static final String LABEL_REQUIREMENT_PATCH = "正在理解需求";
    public static final String LABEL_FOLLOWUP = "正在生成追问";
    public static final String LABEL_PRODUCT_SEARCH = "正在检索产品";
    public static final String LABEL_RECOMMEND = "正在生成推荐";
    public static final String LABEL_CHITCHAT = "正在回复";
    public static final String LABEL_CONFIRM_HINT = "正在回复";

    /** 节点名 → 展示文案。 */
    public static String labelOf(String node) {
        return switch (node) {
            case NODE_REQUIREMENT_PATCH -> LABEL_REQUIREMENT_PATCH;
            case NODE_FOLLOWUP -> LABEL_FOLLOWUP;
            case NODE_PRODUCT_SEARCH -> LABEL_PRODUCT_SEARCH;
            case NODE_RECOMMEND -> LABEL_RECOMMEND;
            case NODE_CHITCHAT -> LABEL_CHITCHAT;
            case NODE_CONFIRM_HINT -> LABEL_CONFIRM_HINT;
            default -> node;
        };
    }
}
