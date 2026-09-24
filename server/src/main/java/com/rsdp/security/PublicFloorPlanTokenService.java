package com.rsdp.security;

import com.rsdp.exception.ResourceNotFoundException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

/**
 * 官网游客 CAD 户型分析的短期访问凭证服务。
 *
 * <p>凭证只绑定 analysisId 与固定用途，不承载登录身份；用于保护匿名任务状态、
 * CAD 规范预览和户型原图，避免仅凭可猜测 URL 或泄漏的图片 ID 直接访问。</p>
 */
@Component
public class PublicFloorPlanTokenService {

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String PURPOSE_VALUE = "public-floor-plan";

    @Value("${rsdp.jwt.secret:}")
    private String secret;

    @Value("${rsdp.floor-plan.public-token-hours:24}")
    private long expirationHours;

    /**
     * 为游客户型分析生成短期访问凭证。
     *
     * @param analysisId 户型分析 ID
     * @return 签名访问凭证
     */
    public String generate(String analysisId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofHours(expirationHours).toMillis());
        return Jwts.builder()
            .subject(analysisId)
            .claim(PURPOSE_CLAIM, PURPOSE_VALUE)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey())
            .compact();
    }

    /**
     * 验证凭证并解析其绑定的户型分析 ID。
     *
     * @param token 游客访问凭证
     * @return 凭证绑定的 analysisId
     */
    public String resolveAnalysisId(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!PURPOSE_VALUE.equals(claims.get(PURPOSE_CLAIM, String.class))
                || claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw notFound();
            }
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw notFound();
        }
    }

    private SecretKey signingKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置，请设置 rsdp.jwt.secret");
        }
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("游客户型分析不存在或访问凭证已失效");
    }
}
