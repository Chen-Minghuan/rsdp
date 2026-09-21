package com.rsdp.agent;

import com.rsdp.agent.service.AgentRunContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentRunContext} 节点轨迹（思考过程）单测。
 */
class AgentRunContextTest {

    @Test
    void recordStepShouldKeepExecutionOrder() {
        AgentRunContext ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "找沙发");

        ctx.recordStep("requirement_patch", "正在理解需求");
        ctx.recordStep("product_search", "正在检索产品");
        ctx.recordStep("recommend", "正在生成推荐");

        assertThat(ctx.steps()).containsExactly(
            java.util.Map.of("node", "requirement_patch", "label", "正在理解需求"),
            java.util.Map.of("node", "product_search", "label", "正在检索产品"),
            java.util.Map.of("node", "recommend", "label", "正在生成推荐"));
    }

    @Test
    void recordStepShouldDeduplicateConsecutiveSameNode() {
        AgentRunContext ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "找沙发");

        ctx.recordStep("product_search", "正在检索产品");
        ctx.recordStep("product_search", "正在检索产品");
        ctx.recordStep("recommend", "正在生成推荐");
        // 非连续重复（循环回边重入）保留
        ctx.recordStep("product_search", "正在检索产品");

        assertThat(ctx.steps()).hasSize(3);
        assertThat(ctx.steps().get(0).get("node")).isEqualTo("product_search");
        assertThat(ctx.steps().get(1).get("node")).isEqualTo("recommend");
        assertThat(ctx.steps().get(2).get("node")).isEqualTo("product_search");
    }

    @Test
    void stepsShouldBeEmptyBeforeAnyNode() {
        AgentRunContext ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "找沙发");

        assertThat(ctx.steps()).isEmpty();
    }
}
