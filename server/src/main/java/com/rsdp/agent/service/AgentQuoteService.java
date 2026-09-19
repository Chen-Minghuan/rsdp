package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.AgentRskuResolver;
import com.rsdp.agent.dto.AgentQuoteResponse;
import com.rsdp.agent.dto.GenerateQuoteRequest;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentQuote;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentQuoteMapper;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.EffectivePriceRateResolver;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 营销 Agent 报价服务（P2 一键报价）。
 *
 * <p>对已确认产品按正式定价口径生成报价卡片：RSPU → 最低售价 RSKU
 * （{@link AgentRskuResolver}）→ 标准售价 × 生效折扣率（{@link EffectivePriceRateResolver}，
 * 与订单同口径）。报价快照落 agent_quote 并以 quote 消息落会话档案（刷新可恢复）。</p>
 *
 * <p>红线：行快照与响应 DTO 只含售价口径，factoryPrice/cost/margin 绝不出现；
 * 幂等三模式（先查 / DuplicateKey 兜底 / 跨会话 409）与 AgentConfirmService 一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQuoteService {

    /** 报价卡片口径提示。 */
    public static final String PRICE_NOTE = "标准售价口径，正式报价以订单为准";

    private final AgentSessionService sessionService;
    private final AgentConfirmedItemMapper confirmedItemMapper;
    private final AgentQuoteMapper quoteMapper;
    private final AgentRskuResolver rskuResolver;
    private final EffectivePriceRateResolver priceRateResolver;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    /**
     * 生成会话报价。
     *
     * @param sessionId 会话 ID
     * @param request   生成请求（confirmedItemIds 可空 = 全部 confirmed 项；idempotencyKey 必填）
     * @return 报价卡片（幂等命中时返回已存在报价）
     */
    @Transactional
    public AgentQuoteResponse generate(String sessionId, GenerateQuoteRequest request) {
        if (!SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)) {
            throw new BusinessException(403, "缺少生成报价权限: " + Permissions.QUOTE_GENERATE);
        }
        AgentSession session = sessionService.requireAccessibleSession(sessionId);
        if (!"active".equals(session.getStatus())) {
            throw new BusinessException("会话已关闭，无法生成报价");
        }

        // 幂等：已存在直接返回，但必须属于当前会话
        AgentQuote existing = findByIdempotencyKey(request.getIdempotencyKey());
        if (existing != null) {
            if (!sessionId.equals(existing.getSessionId())) {
                throw new BusinessException(409, "幂等键已被其他会话使用");
            }
            return toResponse(existing);
        }

        List<AgentConfirmedItem> confirmed = listQuotableConfirmedItems(sessionId, request.getConfirmedItemIds());
        if (confirmed.isEmpty()) {
            throw new BusinessException("没有可报价的已确认产品");
        }

        // RSPU → 最低售价 RSKU（含售价来源；cost 不出参）
        Map<String, AgentRskuResolver.ResolvedRsku> resolved = rskuResolver.resolveMinPriceRsku(
            confirmed.stream().map(AgentConfirmedItem::getRspuId).toList());
        BigDecimal priceRate = priceRateResolver.resolve();

        List<AgentQuoteResponse.QuoteLine> lines = new ArrayList<>();
        BigDecimal listTotal = BigDecimal.ZERO;
        Integer maxLeadTimeDays = null;
        for (AgentConfirmedItem item : confirmed) {
            AgentQuoteResponse.QuoteLine line = new AgentQuoteResponse.QuoteLine();
            line.setConfirmedItemId(item.getItemId());
            line.setRspuId(item.getRspuId());
            line.setQuantity(item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1);
            Map<String, Object> spec = parseSpec(item.getSpec());
            line.setProductName(specText(spec, "productName"));
            line.setPrimaryImageUrl(specText(spec, "primaryImageUrl"));

            AgentRskuResolver.ResolvedRsku picked = resolved.get(item.getRspuId());
            if (picked == null) {
                line.setQuotable(false);
                lines.add(line);
                continue;
            }
            line.setQuotable(true);
            line.setRskuId(picked.rsku().getRskuId());
            line.setUnitSalePrice(picked.price().salePrice());
            line.setPriceSource(picked.price().source());
            line.setLeadTimeDays(picked.rsku().getLeadTimeDays());
            BigDecimal subtotal = picked.price().salePrice()
                .multiply(BigDecimal.valueOf(line.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
            line.setSubtotal(subtotal);
            listTotal = listTotal.add(subtotal);
            if (picked.rsku().getLeadTimeDays() != null) {
                maxLeadTimeDays = maxLeadTimeDays == null
                    ? picked.rsku().getLeadTimeDays()
                    : Math.max(maxLeadTimeDays, picked.rsku().getLeadTimeDays());
            }
            lines.add(line);
        }
        if (lines.stream().noneMatch(line -> Boolean.TRUE.equals(line.getQuotable()))) {
            throw new BusinessException("所选产品均无法解析售价，暂不能报价");
        }

        BigDecimal dealTotal = listTotal.multiply(priceRate).setScale(2, RoundingMode.HALF_UP);

        AgentQuote quote = new AgentQuote();
        quote.setQuoteId(IdGenerator.generate("AQT"));
        quote.setSessionId(sessionId);
        quote.setItems(writeLines(lines));
        quote.setListTotal(listTotal);
        quote.setPriceRate(priceRate);
        quote.setDealTotal(dealTotal);
        quote.setStatus("generated");
        quote.setIdempotencyKey(request.getIdempotencyKey());
        quote.setCreatedBy(SecurityOperatorContext.currentUserId());
        quote.setCreatedAt(LocalDateTime.now());
        try {
            quoteMapper.insert(quote);
        } catch (DuplicateKeyException e) {
            // 并发下同幂等键插入冲突：返回已存在记录（同上会话归属校验）
            AgentQuote conflicted = findByIdempotencyKey(request.getIdempotencyKey());
            if (conflicted != null) {
                if (!sessionId.equals(conflicted.getSessionId())) {
                    throw new BusinessException(409, "幂等键已被其他会话使用");
                }
                return toResponse(conflicted);
            }
            throw e;
        }

        AgentQuoteResponse response = toResponse(quote);
        // 报价消息落会话档案（前端刷新后恢复报价卡片）
        messageStore.persistAssistantMessage(sessionId, null, "quote", "",
            writeQuietly(metadataOf(response)));
        return response;
    }

    /** 报价响应 → 消息 metadata（与卡片契约一致）。 */
    private Map<String, Object> metadataOf(AgentQuoteResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("quoteId", response.getQuoteId());
        metadata.put("lines", response.getLines());
        metadata.put("listTotal", response.getListTotal());
        metadata.put("priceRate", response.getPriceRate());
        metadata.put("dealTotal", response.getDealTotal());
        metadata.put("maxLeadTimeDays", response.getMaxLeadTimeDays());
        metadata.put("priceNote", response.getPriceNote());
        return metadata;
    }

    /** 会话内 confirmed 项（可按 confirmedItemIds 子集过滤；子集含非本会话项时拦截）。 */
    private List<AgentConfirmedItem> listQuotableConfirmedItems(String sessionId, List<String> confirmedItemIds) {
        List<AgentConfirmedItem> confirmed = confirmedItemMapper.selectList(new QueryWrapper<AgentConfirmedItem>()
            .eq("session_id", sessionId)
            .eq("status", "confirmed")
            .orderByAsc("created_at"));
        if (confirmedItemIds == null || confirmedItemIds.isEmpty()) {
            return confirmed;
        }
        Set<String> wanted = confirmedItemIds.stream().filter(StringUtils::hasText).collect(Collectors.toSet());
        List<AgentConfirmedItem> filtered = confirmed.stream()
            .filter(item -> wanted.contains(item.getItemId()))
            .toList();
        if (filtered.size() != wanted.size()) {
            throw new BusinessException("部分确认项不存在或不属于当前会话");
        }
        return filtered;
    }

    private AgentQuote findByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        return quoteMapper.selectOne(new QueryWrapper<AgentQuote>()
            .eq("idempotency_key", idempotencyKey)
            .last("LIMIT 1"));
    }

    /** 实体 → 响应（items JSON 反序列化为行列表）。 */
    private AgentQuoteResponse toResponse(AgentQuote quote) {
        AgentQuoteResponse response = new AgentQuoteResponse();
        response.setQuoteId(quote.getQuoteId());
        response.setLines(readLines(quote.getItems()));
        response.setListTotal(quote.getListTotal());
        response.setPriceRate(quote.getPriceRate());
        response.setDealTotal(quote.getDealTotal());
        response.setMaxLeadTimeDays(response.getLines().stream()
            .map(AgentQuoteResponse.QuoteLine::getLeadTimeDays)
            .filter(d -> d != null)
            .max(Integer::compareTo)
            .orElse(null));
        response.setGeneratedAt(quote.getCreatedAt());
        response.setPriceNote(PRICE_NOTE);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSpec(String spec) {
        if (!StringUtils.hasText(spec)) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(spec, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            log.warn("确认项 spec 解析失败，按空处理");
            return Map.of();
        }
    }

    private String specText(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String writeLines(List<AgentQuoteResponse.QuoteLine> lines) {
        return writeQuietly(lines);
    }

    private String writeQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("报价快照序列化失败", e);
        }
    }

    private List<AgentQuoteResponse.QuoteLine> readLines(String items) {
        if (!StringUtils.hasText(items)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(items, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("报价行快照解析失败，按空列表返回，quote items={}", items);
            return List.of();
        }
    }
}
