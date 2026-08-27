package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公开接口限流配置（{@code rsdp.rate-limit}）。
 *
 * <p>免登录公开接口（AI 户型搭配、留资提交）按客户端 IP 做固定窗口限流，
 * 防止恶意脚本刷量消耗付费 AI 额度或灌爆留资表。单实例内存实现，
 * 与当前单后端部署形态匹配；多实例部署时需换 Redis 等共享计数。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.rate-limit")
public class RateLimitProperties {

    /** 总开关：false 时公开接口不限流（本地联调可关闭）。 */
    private boolean enabled = true;

    /** AI 户型搭配（/api/v1/public/ai-match/**）每 IP 每窗口允许次数（每次调用烧付费模型）。 */
    private int aiMatchPermits = 10;

    /** 留资提交（POST /api/v1/public/leads）每 IP 每窗口允许次数。 */
    private int leadsPermits = 5;

    /** 窗口时长（秒），固定窗口到期后计数重置。 */
    private int windowSeconds = 60;
}
