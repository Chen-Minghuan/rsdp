package com.rsdp.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
