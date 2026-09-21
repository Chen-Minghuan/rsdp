package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.dto.RecommendItemResponse;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentRecommendBatch;
import com.rsdp.agent.entity.AgentRecommendItem;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentRecommendBatchMapper;
import com.rsdp.agent.mapper.AgentRecommendItemMapper;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.agent.service.RecommendReasoner;
import com.rsdp.agent.skill.AgentSkill;
import com.rsdp.agent.skill.SkillContext;
import com.rsdp.agent.skill.SkillRegistry;
import com.rsdp.agent.skill.SkillResult;
import com.rsdp.agent.skill.livingroom.LivingRoomMatchingSkill;
import com.rsdp.agent.tool.AgentReadTools;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
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
 * 配套推荐节点（P2）：SkillNode → living-room-matching Skill。
 *
 * <p>加载会话确认项 → 校验 Skill requiredPermissions → 执行 Skill（确定性）→
 * LLM 理由（{@link RecommendReasoner}，失败降级）→ 落批次（skillId/skillVersion/
 * batchType=companion、groupTag=品类码）→ SSE 卡片（前端按 groupTag 分组渲染）。</p>
 *
 * <p>权限铁律：Skill 只能调读工具；本节点自身只做读与推荐留痕，
 * 报价/方案/下单等写操作不进图（REQUEST_QUOTE/EXPORT_SCHEME 意图走 ActionHintNode 引导）。</p>
 */
@Slf4j
@Component
public class CompanionMatchNode extends AbstractAgentNode {

    private static final String INTRO_SYSTEM_PROMPT = """
        你是家居搭配顾问。根据本轮配套推荐结果写一段不超过 80 字的中文引导文案，
        语气自然专业，说明这些是围绕已确认主体产品挑选的配套产品，
        提示用户可以查看卡片、点击「确认这款」把配套产品加入清单，或继续提出调整要求。
        只输出文案本身，不要列表、不要 JSON、不要解释。
        """;

    private static final String FALLBACK_INTRO =
        "围绕您已确认的产品，为您搭配了以下配套产品，可以按品类查看卡片；看中哪款点「确认这款」加入清单。";

    private static final String NO_ANCHOR_TEXT =
        "还没有已确认的主体产品，先挑一款主体产品（如沙发）点「确认这款」，我再帮您搭配配套产品。";

    private static final String NO_MATCH_TEXT =
        "暂时没有为您已确认的产品找到合适的配套产品。您可以调整风格、颜色或预算要求，我再帮您找找看。";

    private final AgentConfirmedItemMapper confirmedItemMapper;
    private final SkillRegistry skillRegistry;
    private final AgentReadTools readTools;
    private final RecommendReasoner reasoner;
    private final AgentChatService chatService;
    private final AgentRecommendBatchMapper batchMapper;
    private final AgentRecommendItemMapper itemMapper;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    public CompanionMatchNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                              AgentConfirmedItemMapper confirmedItemMapper,
                              SkillRegistry skillRegistry, AgentReadTools readTools,
                              RecommendReasoner reasoner, AgentChatService chatService,
                              AgentRecommendBatchMapper batchMapper,
                              AgentRecommendItemMapper itemMapper,
                              AgentMessageStore messageStore, ObjectMapper objectMapper) {
        super(eventBus, runRecorder);
        this.confirmedItemMapper = confirmedItemMapper;
        this.skillRegistry = skillRegistry;
        this.readTools = readTools;
        this.reasoner = reasoner;
        this.chatService = chatService;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.messageStore = messageStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_COMPANION_MATCH;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) throws Exception {
        List<AgentConfirmedItem> confirmed = confirmedItemMapper.selectList(new QueryWrapper<AgentConfirmedItem>()
            .eq("session_id", ctx.getSessionId())
            .eq("status", "confirmed")
            .orderByAsc("created_at"));
        if (confirmed.isEmpty()) {
            // 路由层已拦截，此处为防御性兜底
            ctx.appendAssistantText(NO_ANCHOR_TEXT);
            eventBus.emitToken(ctx.getRunId(), NO_ANCHOR_TEXT);
            return Map.of();
        }

        AgentSkill skill = skillRegistry.require(LivingRoomMatchingSkill.SKILL_ID);
        // requiredPermissions 由挂 Skill 的 Graph 节点执行前校验（Skill 自身不感知权限体系）
        for (String permission : skill.metadata().requiredPermissions()) {
            if (!SecurityOperatorContext.hasAuthority(permission)) {
                throw new BusinessException(403, "缺少执行配套推荐所需权限: " + permission);
            }
        }

        SkillResult result = skill.execute(new SkillContext(
            ctx.getSessionId(), ctx.getRunId(), ctx.getConstraints(), confirmed, Map.of(), readTools));
        List<SkillResult.CompanionGroup> groups = result.groups() != null ? result.groups() : List.of();
        if (groups.isEmpty()) {
            log.info("配套推荐无结果，trace={}，runId={}", result.trace(), ctx.getRunId());
            ctx.appendAssistantText(NO_MATCH_TEXT);
            eventBus.emitToken(ctx.getRunId(), NO_MATCH_TEXT);
            return Map.of();
        }

        // LLM 理由（候选为全部分组展平；失败/全非法降级无理由卡片）
        List<ProductSearchItem> candidates = groups.stream()
            .flatMap(group -> group.items().stream())
            .toList();
        Map<String, ProductSearchItem> candidateMap = candidates.stream()
            .collect(Collectors.toMap(ProductSearchItem::getRspuId, Function.identity(), (a, b) -> a));
        List<RecommendReasoner.ScoredCandidate> scored = reasoner.generateReasons(ctx, candidates, candidateMap);
        Map<String, RecommendItemResponse.Reason> reasonByRspuId = scored.stream()
            .filter(s -> s.reason() != null)
            .collect(Collectors.toMap(s -> s.candidate().getRspuId(), RecommendReasoner.ScoredCandidate::reason, (a, b) -> a));

        // 落批次 + 条目（batchType=companion，skill 溯源，groupTag=品类码）
        AgentRecommendBatch batch = new AgentRecommendBatch();
        batch.setBatchId(IdGenerator.generate("RCB"));
        batch.setSessionId(ctx.getSessionId());
        batch.setVersionNo(ctx.getVersionNo());
        batch.setQueryCriteria(objectMapper.writeValueAsString(result.trace()));
        batch.setSkillId(skill.metadata().id());
        batch.setSkillVersion(skill.metadata().version());
        batch.setBatchType("companion");
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);

        List<RecommendItemResponse> cardItems = new ArrayList<>();
        int rank = 0;
        for (SkillResult.CompanionGroup group : groups) {
            for (ProductSearchItem item : group.items()) {
                rank++;
                RecommendItemResponse.Reason reason = reasonByRspuId.get(item.getRspuId());

                AgentRecommendItem entity = new AgentRecommendItem();
                entity.setItemId(IdGenerator.generate("RCI"));
                entity.setBatchId(batch.getBatchId());
                entity.setRspuId(item.getRspuId());
                entity.setRank(rank);
                entity.setRankScore(item.getRankScore());
                entity.setSnapshot(objectMapper.writeValueAsString(RecommendReasoner.snapshotOf(item)));
                entity.setReason(reason != null ? objectMapper.writeValueAsString(reason) : null);
                entity.setGroupTag(group.categoryCode());
                entity.setCreatedAt(LocalDateTime.now());
                itemMapper.insert(entity);

                RecommendItemResponse card = new RecommendItemResponse();
                card.setItemId(entity.getItemId());
                card.setBatchId(batch.getBatchId());
                card.setRspuId(entity.getRspuId());
                card.setRank(rank);
                card.setGroupTag(group.categoryCode());
                card.setSnapshot(RecommendReasoner.snapshotOf(item));
                card.setReason(reason);
                cardItems.add(card);
            }
        }

        messageStore.persistAssistantMessage(ctx.getSessionId(), ctx.getRunId(), "cards", "",
            objectMapper.writeValueAsString(Map.of(
                "batchId", batch.getBatchId(), "batchType", "companion", "items", cardItems)));

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
                "本轮配套推荐产品：" + names + "\n用户需求：" + ctx.getUserMessage(),
                token -> eventBus.emitToken(ctx.getRunId(), token));
        } catch (Exception e) {
            log.error("配套引导文案生成失败，使用兜底文案，runId={}", ctx.getRunId(), e);
            text = null;
        }
        if (text == null || text.isBlank()) {
            text = FALLBACK_INTRO;
            eventBus.emitToken(ctx.getRunId(), text);
        }
        ctx.appendAssistantText(text.trim());
    }
}
