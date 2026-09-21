package com.rsdp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.dto.LlmRecommendation;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.RecommendReasoner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RecommendReasoner} 单元测试（推荐理由校验守卫：rspuId 白名单 + evidenceRefs 白名单）。
 */
class RecommendReasonerTest {

    private AgentChatService chatService;
    private RecommendReasoner reasoner;
    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        chatService = mock(AgentChatService.class);
        reasoner = new RecommendReasoner(chatService, new ObjectMapper());
        ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "帮我推荐");
    }

    @AfterEach
    void tearDown() {
        AgentRunContext.remove("RUN-1");
    }

    private ProductSearchItem candidate(String rspuId) {
        ProductSearchItem item = new ProductSearchItem();
        item.setRspuId(rspuId);
        item.setProductName("产品-" + rspuId);
        return item;
    }

    private LlmRecommendation recommendationOf(List<LlmRecommendation.Item> items) {
        LlmRecommendation recommendation = new LlmRecommendation();
        recommendation.setItems(items);
        return recommendation;
    }

    private LlmRecommendation.Item llmItem(String rspuId, String text, List<String> refs) {
        LlmRecommendation.Item item = new LlmRecommendation.Item();
        item.setRspuId(rspuId);
        LlmRecommendation.Highlight highlight = new LlmRecommendation.Highlight();
        highlight.setText(text);
        highlight.setEvidenceRefs(refs);
        item.setHighlights(List.of(highlight));
        return item;
    }

    private List<RecommendReasoner.ScoredCandidate> run(LlmRecommendation recommendation,
                                                        List<ProductSearchItem> candidates) {
        when(chatService.call(anyString(), anyString())).thenReturn("{}");
        when(chatService.parseJson(anyString(), eq(LlmRecommendation.class))).thenReturn(recommendation);
        Map<String, ProductSearchItem> candidateMap = candidates.stream()
            .collect(Collectors.toMap(ProductSearchItem::getRspuId, Function.identity()));
        return reasoner.generateReasons(ctx, candidates, candidateMap);
    }

    @Test
    void validItemsShouldPassWithReasons() {
        List<RecommendReasoner.ScoredCandidate> scored = run(
            recommendationOf(List.of(llmItem("R1", "颜色符合", List.of("colorPrimaryName")))),
            List.of(candidate("R1")));

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).reason().getHighlights().get(0).getText()).isEqualTo("颜色符合");
    }

    @Test
    void rspuIdNotInCandidatesShouldBeRejected() {
        List<RecommendReasoner.ScoredCandidate> scored = run(
            recommendationOf(List.of(llmItem("R-FAKE", "编造", List.of("productName")))),
            List.of(candidate("R1")));

        assertThat(scored).isEmpty();
    }

    @Test
    void evidenceRefsOutsideSnapshotKeysShouldBeRejected() {
        List<RecommendReasoner.ScoredCandidate> scored = run(
            recommendationOf(List.of(llmItem("R1", "出厂价便宜", List.of("factoryPrice")))),
            List.of(candidate("R1")));

        assertThat(scored).isEmpty();
    }

    @Test
    void duplicateRspuIdShouldKeepFirstOnly() {
        List<RecommendReasoner.ScoredCandidate> scored = run(
            recommendationOf(List.of(
                llmItem("R1", "亮点一", List.of("productName")),
                llmItem("R1", "亮点二", List.of("productName")))),
            List.of(candidate("R1")));

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).reason().getHighlights().get(0).getText()).isEqualTo("亮点一");
    }

    @Test
    void llmFailureShouldReturnEmptyForFallback() {
        when(chatService.call(anyString(), anyString())).thenThrow(new RuntimeException("LLM 故障"));

        List<ProductSearchItem> candidates = List.of(candidate("R1"));
        Map<String, ProductSearchItem> candidateMap = candidates.stream()
            .collect(Collectors.toMap(ProductSearchItem::getRspuId, Function.identity()));

        assertThat(reasoner.generateReasons(ctx, candidates, candidateMap)).isEmpty();
    }

    @Test
    void snapshotOfShouldOnlyContainWhitelistKeys() {
        ProductSearchItem item = candidate("R1");
        item.setRetailPrice(new java.math.BigDecimal("1000"));

        Map<String, Object> snapshot = RecommendReasoner.snapshotOf(item);

        assertThat(snapshot.keySet()).containsExactly("productName", "retailPrice");
    }
}
