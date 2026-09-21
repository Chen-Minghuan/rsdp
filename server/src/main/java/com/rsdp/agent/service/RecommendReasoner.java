package com.rsdp.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.dto.LlmRecommendation;
import com.rsdp.agent.dto.RecommendItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐理由生成器（P2 从 RecommendNode 抽取的公共组件，RecommendNode 与
 * CompanionMatchNode 共用）。
 *
 * <p>LLM 生成推荐理由（严格 JSON）→ Java 校验（防模型编造）：rspuId 必须在候选集合内；
 * evidenceRefs 必须是 snapshot 字段名子集；非法条目剔除，全非法返回空列表（调用方降级
 * 为无理由卡片）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendReasoner {

    /** snapshot 字段名白名单（与前端 ProductSnapshot 一致，evidenceRefs 只允许引用这些字段）。 */
    public static final Set<String> SNAPSHOT_KEYS = Set.of(
        "productName", "categoryPath", "primaryImageUrl", "colorPrimaryName",
        "material", "sizeText", "retailPrice");

    /** 卡片数量上限（LLM 输出与降级共用）。 */
    public static final int MAX_CARDS = 6;

    private static final String REASON_SYSTEM_PROMPT = """
        你是家居选品推荐助手。基于候选产品列表挑选最匹配用户需求的产品并给出推荐理由，只输出严格 JSON，不要 markdown 代码块，不要解释文字：
        {"items":[{"rspuId":"候选中的rspuId","highlights":[{"text":"一句亮点","evidenceRefs":["字段名"]}]}]}
        规则：
        - rspuId 必须原样取自候选列表，不得编造
        - evidenceRefs 只能从候选 JSON 的这些字段名中选：productName, categoryPath, primaryImageUrl, colorPrimaryName, material, sizeText, retailPrice
        - 每条亮点必须有 evidenceRefs 支撑，只能陈述候选信息中存在的事实，不得编造
        - 挑选 3-6 款最匹配的，按匹配度从高到低排序；同款产品不重复推荐
        """;

    private final AgentChatService chatService;
    private final ObjectMapper objectMapper;

    /** LLM 生成推荐理由并逐项校验；全部非法或 LLM 失败返回空列表（调用方降级）。 */
    public List<ScoredCandidate> generateReasons(AgentRunContext ctx, List<ProductSearchItem> candidates,
                                                 Map<String, ProductSearchItem> candidateMap) {
        String raw;
        try {
            raw = chatService.call(REASON_SYSTEM_PROMPT, buildReasonUserPrompt(ctx, candidates));
        } catch (Exception e) {
            log.error("推荐理由 LLM 调用失败，runId={}", ctx.getRunId(), e);
            return List.of();
        }
        LlmRecommendation recommendation = chatService.parseJson(raw, LlmRecommendation.class);
        if (recommendation == null || recommendation.getItems() == null) {
            return List.of();
        }
        List<ScoredCandidate> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LlmRecommendation.Item item : recommendation.getItems()) {
            if (scored.size() >= MAX_CARDS) {
                break;
            }
            if (item == null || !StringUtils.hasText(item.getRspuId())) {
                continue;
            }
            String rspuId = item.getRspuId().trim();
            ProductSearchItem candidate = candidateMap.get(rspuId);
            if (candidate == null || !seen.add(rspuId)) {
                // rspuId 不在候选集合或重复：剔除
                log.info("剔除非法推荐条目（rspuId 不在候选或重复）：{}", rspuId);
                continue;
            }
            List<RecommendItemResponse.Highlight> highlights = validHighlights(item.getHighlights());
            if (highlights.isEmpty()) {
                log.info("剔除无有效亮点的推荐条目：{}", rspuId);
                continue;
            }
            RecommendItemResponse.Reason reason = new RecommendItemResponse.Reason();
            reason.setHighlights(highlights);
            scored.add(new ScoredCandidate(candidate, reason));
        }
        return scored;
    }

    /** 过滤有效亮点：text 非空且 evidenceRefs 为 snapshot 字段名子集。 */
    private List<RecommendItemResponse.Highlight> validHighlights(List<LlmRecommendation.Highlight> raw) {
        if (raw == null) {
            return List.of();
        }
        List<RecommendItemResponse.Highlight> result = new ArrayList<>();
        for (LlmRecommendation.Highlight highlight : raw) {
            if (highlight == null || !StringUtils.hasText(highlight.getText())
                || highlight.getEvidenceRefs() == null || highlight.getEvidenceRefs().isEmpty()) {
                continue;
            }
            List<String> refs = highlight.getEvidenceRefs().stream()
                .filter(StringUtils::hasText).map(String::trim).toList();
            if (refs.isEmpty() || !SNAPSHOT_KEYS.containsAll(refs)) {
                continue;
            }
            RecommendItemResponse.Highlight valid = new RecommendItemResponse.Highlight();
            valid.setText(highlight.getText().trim());
            valid.setEvidenceRefs(refs);
            result.add(valid);
        }
        return result;
    }

    private String buildReasonUserPrompt(AgentRunContext ctx, List<ProductSearchItem> candidates) {
        List<Map<String, Object>> candidateJson = new ArrayList<>();
        for (ProductSearchItem item : candidates) {
            Map<String, Object> map = new LinkedHashMap<>(snapshotOf(item));
            map.put("rspuId", item.getRspuId());
            map.put("matchedConditions", item.getMatchedConditions());
            candidateJson.add(map);
        }
        String candidatesJson;
        try {
            candidatesJson = objectMapper.writeValueAsString(candidateJson);
        } catch (Exception e) {
            throw new IllegalStateException("候选产品序列化失败", e);
        }
        return "用户需求约束："
            + (ctx.getConstraints() != null ? ctx.getConstraints().toJson() : "{}")
            + "\n用户最新消息：" + ctx.getUserMessage()
            + "\n候选产品列表：\n" + candidatesJson;
    }

    /** ProductSearchItem → snapshot Map（key 与前端 ProductSnapshot 一致，null 字段不输出）。 */
    public static Map<String, Object> snapshotOf(ProductSearchItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfNotNull(snapshot, "productName", item.getProductName());
        putIfNotNull(snapshot, "categoryPath", item.getCategoryPath());
        putIfNotNull(snapshot, "primaryImageUrl", item.getPrimaryImageUrl());
        putIfNotNull(snapshot, "colorPrimaryName", item.getColorPrimaryName());
        putIfNotNull(snapshot, "material", item.getMaterial());
        putIfNotNull(snapshot, "sizeText", item.getSizeText());
        putIfNotNull(snapshot, "retailPrice", item.getRetailPrice());
        return snapshot;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** 校验后的候选 + 推荐理由（reason 为 null 表示降级无理由卡片）。 */
    public record ScoredCandidate(ProductSearchItem candidate, RecommendItemResponse.Reason reason) {
    }
}
