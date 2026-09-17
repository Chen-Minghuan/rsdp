package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.dto.LlmRecommendation;
import com.rsdp.agent.dto.RecommendItemResponse;
import com.rsdp.agent.entity.AgentRecommendBatch;
import com.rsdp.agent.entity.AgentRecommendItem;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentRecommendBatchMapper;
import com.rsdp.agent.mapper.AgentRecommendItemMapper;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐节点：LLM 生成推荐理由（严格 JSON）→ Java 校验 → 落批次/条目 → SSE 卡片。
 *
 * <p>校验规则（防模型编造）：rspuId 必须在候选集合内；evidenceRefs 必须是
 * snapshot 字段名子集（productName/categoryPath/primaryImageUrl/colorPrimaryName/
 * material/sizeText/retailPrice）；非法条目剔除，全非法则降级为无理由卡片。</p>
 */
@Slf4j
@Component
public class RecommendNode extends AbstractAgentNode {

    /** snapshot 字段名白名单（与前端 ProductSnapshot 一致，evidenceRefs 只允许引用这些字段）。 */
    public static final Set<String> SNAPSHOT_KEYS = Set.of(
        "productName", "categoryPath", "primaryImageUrl", "colorPrimaryName",
        "material", "sizeText", "retailPrice");

    /** 卡片数量上限（LLM 输出与降级共用）。 */
    private static final int MAX_CARDS = 6;

    private static final String REASON_SYSTEM_PROMPT = """
        你是家居选品推荐助手。基于候选产品列表挑选最匹配用户需求的产品并给出推荐理由，只输出严格 JSON，不要 markdown 代码块，不要解释文字：
        {"items":[{"rspuId":"候选中的rspuId","highlights":[{"text":"一句亮点","evidenceRefs":["字段名"]}]}]}
        规则：
        - rspuId 必须原样取自候选列表，不得编造
        - evidenceRefs 只能从候选 JSON 的这些字段名中选：productName, categoryPath, primaryImageUrl, colorPrimaryName, material, sizeText, retailPrice
        - 每条亮点必须有 evidenceRefs 支撑，只能陈述候选信息中存在的事实，不得编造
        - 挑选 3-6 款最匹配的，按匹配度从高到低排序；同款产品不重复推荐
        """;

    private static final String INTRO_SYSTEM_PROMPT = """
        你是家居选品顾问。根据本轮推荐结果写一段不超过 80 字的中文引导文案，
        语气自然专业，提示用户可以查看卡片、继续提出调整要求，或点击「确认这款」锁定产品。
        只输出文案本身，不要列表、不要 JSON、不要解释。
        """;

    private static final String FALLBACK_INTRO =
        "为您找到以下匹配的产品，可以查看卡片详情；看中哪款点「确认这款」，也可以继续告诉我调整方向。";

    private static final String NO_MATCH_TEXT =
        "当前产品库里暂时没有完全符合这些条件的产品。您可以放宽预算、尺寸或颜色要求，我再帮您找找看。";

    private final AgentChatService chatService;
    private final AgentRecommendBatchMapper batchMapper;
    private final AgentRecommendItemMapper itemMapper;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    public RecommendNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                         AgentChatService chatService, AgentRecommendBatchMapper batchMapper,
                         AgentRecommendItemMapper itemMapper, AgentMessageStore messageStore,
                         ObjectMapper objectMapper) {
        super(eventBus, runRecorder);
        this.chatService = chatService;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.messageStore = messageStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_RECOMMEND;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) throws Exception {
        ProductSearchResult searchResult = ctx.getSearchResult();
        List<ProductSearchItem> candidates = searchResult != null && searchResult.getItems() != null
            ? searchResult.getItems() : List.of();
        if (candidates.isEmpty()) {
            ctx.appendAssistantText(NO_MATCH_TEXT);
            eventBus.emitToken(ctx.getRunId(), NO_MATCH_TEXT);
            return Map.of();
        }

        Map<String, ProductSearchItem> candidateMap = candidates.stream()
            .collect(Collectors.toMap(ProductSearchItem::getRspuId, Function.identity(), (a, b) -> a));

        // 1. LLM 生成推荐理由（失败/全非法 → 降级无理由卡片）
        List<ScoredCandidate> scored = generateReasons(ctx, candidates, candidateMap);
        if (scored.isEmpty()) {
            log.info("推荐理由全部非法或 LLM 失败，降级为无理由卡片，runId={}", ctx.getRunId());
            scored = candidates.stream().limit(MAX_CARDS)
                .map(item -> new ScoredCandidate(item, null))
                .toList();
        }

        // 2. 落批次 + 条目
        AgentRecommendBatch batch = new AgentRecommendBatch();
        batch.setBatchId(IdGenerator.generate("RCB"));
        batch.setSessionId(ctx.getSessionId());
        batch.setVersionNo(ctx.getVersionNo());
        batch.setQueryCriteria(objectMapper.writeValueAsString(ctx.getSearchCriteria()));
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);

        List<RecommendItemResponse> cardItems = new ArrayList<>();
        int rank = 0;
        for (ScoredCandidate scoredCandidate : scored) {
            rank++;
            AgentRecommendItem entity = new AgentRecommendItem();
            entity.setItemId(IdGenerator.generate("RCI"));
            entity.setBatchId(batch.getBatchId());
            entity.setRspuId(scoredCandidate.candidate().getRspuId());
            entity.setRank(rank);
            entity.setSnapshot(objectMapper.writeValueAsString(snapshotOf(scoredCandidate.candidate())));
            entity.setReason(scoredCandidate.reason() != null
                ? objectMapper.writeValueAsString(scoredCandidate.reason()) : null);
            entity.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(entity);

            RecommendItemResponse card = new RecommendItemResponse();
            card.setItemId(entity.getItemId());
            card.setBatchId(batch.getBatchId());
            card.setRspuId(entity.getRspuId());
            card.setRank(rank);
            card.setSnapshot(snapshotOf(scoredCandidate.candidate()));
            card.setReason(scoredCandidate.reason());
            cardItems.add(card);
        }

        // 3. 卡片消息落库（前端刷新后恢复）
        messageStore.persistAssistantMessage(ctx.getSessionId(), ctx.getRunId(), "cards", "",
            objectMapper.writeValueAsString(Map.of("batchId", batch.getBatchId(), "items", cardItems)));

        // 4. 引导文案（流式）→ 卡片事件
        streamIntro(ctx, cardItems);
        eventBus.emitCards(ctx.getRunId(), batch.getBatchId(), cardItems);
        return Map.of();
    }

    /** LLM 生成推荐理由并逐项校验；全部非法返回空列表。 */
    private List<ScoredCandidate> generateReasons(AgentRunContext ctx, List<ProductSearchItem> candidates,
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

    /** 流式生成引导文案并累计为 assistant 文本。 */
    private void streamIntro(AgentRunContext ctx, List<RecommendItemResponse> cardItems) {
        String names = cardItems.stream()
            .map(item -> String.valueOf(item.getSnapshot().get("productName")))
            .collect(Collectors.joining("、"));
        String text;
        try {
            text = chatService.streamCollect(INTRO_SYSTEM_PROMPT,
                "本轮推荐产品：" + names + "\n用户需求：" + ctx.getUserMessage(),
                token -> eventBus.emitToken(ctx.getRunId(), token));
        } catch (Exception e) {
            log.error("引导文案生成失败，使用兜底文案，runId={}", ctx.getRunId(), e);
            text = null;
        }
        if (text == null || text.isBlank()) {
            text = FALLBACK_INTRO;
            eventBus.emitToken(ctx.getRunId(), text);
        }
        ctx.appendAssistantText(text.trim());
    }

    private String buildReasonUserPrompt(AgentRunContext ctx, List<ProductSearchItem> candidates) throws Exception {
        List<Map<String, Object>> candidateJson = new ArrayList<>();
        for (ProductSearchItem item : candidates) {
            Map<String, Object> map = new LinkedHashMap<>(snapshotOf(item));
            map.put("rspuId", item.getRspuId());
            map.put("matchedConditions", item.getMatchedConditions());
            candidateJson.add(map);
        }
        return "用户需求约束："
            + (ctx.getConstraints() != null ? ctx.getConstraints().toJson() : "{}")
            + "\n用户最新消息：" + ctx.getUserMessage()
            + "\n候选产品列表：\n" + objectMapper.writeValueAsString(candidateJson);
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
    private record ScoredCandidate(ProductSearchItem candidate, RecommendItemResponse.Reason reason) {
    }
}
