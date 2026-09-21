package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
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
import com.rsdp.agent.service.RecommendReasoner;
import com.rsdp.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐节点：LLM 生成推荐理由（经 {@link RecommendReasoner} 校验）→ 落批次/条目 → SSE 卡片。
 *
 * <p>校验规则（防模型编造）：rspuId 必须在候选集合内；evidenceRefs 必须是
 * snapshot 字段名子集；非法条目剔除，全非法则降级为无理由卡片。</p>
 */
@Slf4j
@Component
public class RecommendNode extends AbstractAgentNode {

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
    private final RecommendReasoner reasoner;
    private final AgentRecommendBatchMapper batchMapper;
    private final AgentRecommendItemMapper itemMapper;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    public RecommendNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                         AgentChatService chatService, RecommendReasoner reasoner,
                         AgentRecommendBatchMapper batchMapper,
                         AgentRecommendItemMapper itemMapper, AgentMessageStore messageStore,
                         ObjectMapper objectMapper) {
        super(eventBus, runRecorder);
        this.chatService = chatService;
        this.reasoner = reasoner;
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
        List<RecommendReasoner.ScoredCandidate> scored = reasoner.generateReasons(ctx, candidates, candidateMap);
        if (scored.isEmpty()) {
            log.info("推荐理由全部非法或 LLM 失败，降级为无理由卡片，runId={}", ctx.getRunId());
            scored = candidates.stream().limit(RecommendReasoner.MAX_CARDS)
                .map(item -> new RecommendReasoner.ScoredCandidate(item, null))
                .toList();
        }

        // 2. 落批次 + 条目（主体选品：batchType=primary；query_criteria 附向量通道留痕）
        AgentRecommendBatch batch = new AgentRecommendBatch();
        batch.setBatchId(IdGenerator.generate("RCB"));
        batch.setSessionId(ctx.getSessionId());
        batch.setVersionNo(ctx.getVersionNo());
        batch.setQueryCriteria(objectMapper.writeValueAsString(Map.of(
            "criteria", ctx.getSearchCriteria() != null ? ctx.getSearchCriteria() : Map.of(),
            "vectorChannel", searchResult.isVectorChannelUsed())));
        batch.setBatchType("primary");
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);

        List<RecommendItemResponse> cardItems = new ArrayList<>();
        int rank = 0;
        for (RecommendReasoner.ScoredCandidate scoredCandidate : scored) {
            rank++;
            AgentRecommendItem entity = new AgentRecommendItem();
            entity.setItemId(IdGenerator.generate("RCI"));
            entity.setBatchId(batch.getBatchId());
            entity.setRspuId(scoredCandidate.candidate().getRspuId());
            entity.setRank(rank);
            entity.setRankScore(scoredCandidate.candidate().getRankScore());
            entity.setSnapshot(objectMapper.writeValueAsString(RecommendReasoner.snapshotOf(scoredCandidate.candidate())));
            entity.setReason(scoredCandidate.reason() != null
                ? objectMapper.writeValueAsString(scoredCandidate.reason()) : null);
            entity.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(entity);

            RecommendItemResponse card = new RecommendItemResponse();
            card.setItemId(entity.getItemId());
            card.setBatchId(batch.getBatchId());
            card.setRspuId(entity.getRspuId());
            card.setRank(rank);
            card.setSnapshot(RecommendReasoner.snapshotOf(scoredCandidate.candidate()));
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
}
