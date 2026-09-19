package com.rsdp.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 营销 Agent 配置（{@code rsdp.marketing-agent}）。
 *
 * <p>控制推荐检索条数、追问轮次上限、SSE 心跳与单次运行超时。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.marketing-agent")
public class MarketingAgentProperties {

    /** 推荐检索返回条数。 */
    private int searchTopN = 20;

    /** 需求追问最大轮次。 */
    private int maxFollowupRounds = 3;

    /** SSE 心跳间隔（秒）。 */
    private int sseHeartbeatSeconds = 15;

    /** 单次 run 超时（秒）。 */
    private int runTimeoutSeconds = 120;

    /** 向量召回开关（P2）：关闭时 MarketingProductQueryService 退化为纯结构化检索。 */
    private boolean vectorRecallEnabled = true;

    /** RRF 融合常数 k（标准值 60）。 */
    private int rrfK = 60;

    /** 向量通道召回倍数（topN × 该倍数 = 向量库候选数）。 */
    private int vectorCandidateMultiplier = 5;

    /**
     * 配套搭配规则（P2 living-room-matching）：锚点品类码 → 配套品类规则列表。
     * 默认客厅口径（与 AiMatchingService.compositionHint 一致）：沙发 → 茶几/柜类/休闲椅。
     */
    private Map<String, List<CompanionRule>> companionRules = new HashMap<>(Map.of(
        "SF", List.of(
            new CompanionRule("TB", "茶几", 1, 0.5),
            new CompanionRule("FC", "柜类", 1, 0.3),
            new CompanionRule("FS", "休闲椅", 2, 0.2))));

    /** 配套品类规则：锚点品类确认后，为该配套品类检索 topN=max 个候选。 */
    @Getter
    @Setter
    public static class CompanionRule {

        /** 配套品类码（category_dict dict_code）。 */
        private String categoryCode;

        /** 品类中文名（展示用）。 */
        private String categoryName;

        /** 该品类候选上限。 */
        private int max = 2;

        /** 预算切片权重（剩余预算按权重比例分配给各配套品类）。 */
        private double weight = 1.0;

        public CompanionRule() {
        }

        public CompanionRule(String categoryCode, String categoryName, int max, double weight) {
            this.categoryCode = categoryCode;
            this.categoryName = categoryName;
            this.max = max;
            this.weight = weight;
        }
    }
}
