package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.AgentRskuResolver;
import com.rsdp.agent.dto.AgentQuoteResponse;
import com.rsdp.agent.dto.AgentSchemeExportResponse;
import com.rsdp.agent.dto.ExportSchemeRequest;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentQuote;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentQuoteMapper;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.SchemeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营销 Agent 方案导出服务（P2 对话外下单出口）。
 *
 * <p>把会话 confirmed 项（或报价快照行）经 {@link SchemeService#createScheme} 导出为方案，
 * 用户跳转方案详情页走既有报价单/下单流程——Agent 会话内不直接创建订单。</p>
 *
 * <p>幂等：同会话同幂等键重复提交返回首个导出结果（scheme 消息 metadata 留痕反查）；
 * 报价快照路径（quoteId）保证报价=方案口径一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSchemeExportService {

    private static final DateTimeFormatter NAME_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AgentSessionService sessionService;
    private final AgentConfirmedItemMapper confirmedItemMapper;
    private final AgentQuoteMapper quoteMapper;
    private final AgentRskuResolver rskuResolver;
    private final SchemeService schemeService;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    /**
     * 导出会话确认清单为方案。
     *
     * @param sessionId 会话 ID
     * @param request   导出请求（quoteId 可空；idempotencyKey 必填）
     * @return 方案导出结果（幂等命中时返回首个导出结果）
     */
    @Transactional
    public AgentSchemeExportResponse exportScheme(String sessionId, ExportSchemeRequest request) {
        if (!SecurityOperatorContext.hasAuthority(Permissions.SCHEME_CREATE)) {
            throw new BusinessException(403, "缺少生成方案权限: " + Permissions.SCHEME_CREATE);
        }
        AgentSession session = sessionService.requireAccessibleSession(sessionId);
        if (!"active".equals(session.getStatus())) {
            throw new BusinessException("会话已关闭，无法生成方案");
        }

        // 幂等：同会话同键返回首个导出结果（并发下由 SchemeService 方案名唯一约束与请求方重试兜底）
        AgentSchemeExportResponse replay = findExistingExport(sessionId, request.getIdempotencyKey());
        if (replay != null) {
            return replay;
        }

        AgentQuote quote = null;
        List<SchemeItemRequest> items;
        if (StringUtils.hasText(request.getQuoteId())) {
            // 报价快照路径：报价=方案口径一致；不可报价行不带入方案
            quote = quoteMapper.selectById(request.getQuoteId());
            if (quote == null || !sessionId.equals(quote.getSessionId())) {
                throw new BusinessException("报价不存在或不属于当前会话");
            }
            items = itemsFromQuote(quote);
        } else {
            // 现场重解析路径：confirmed 项 → 最低售价 RSKU
            items = itemsFromConfirmed(sessionId);
        }
        if (items.isEmpty()) {
            throw new BusinessException("没有可导出的方案项（所选产品均无法解析供应报价）");
        }

        String schemeName = StringUtils.hasText(request.getSchemeName())
            ? request.getSchemeName().trim()
            : defaultSchemeName(sessionId);
        SchemeCreateRequest createRequest = new SchemeCreateRequest();
        createRequest.setSchemeName(schemeName);
        createRequest.setItems(items);
        SchemeResponse scheme = schemeService.createScheme(createRequest);

        if (quote != null) {
            quote.setStatus("exported");
            quoteMapper.updateById(quote);
        }

        AgentSchemeExportResponse response = new AgentSchemeExportResponse();
        response.setSchemeId(scheme.getSchemeId());
        response.setSchemeName(scheme.getSchemeName());
        response.setItemCount(items.size());
        response.setDetailUrl("/schemes/" + scheme.getSchemeId());

        // 方案消息落会话档案（前端刷新后恢复；幂等反查依据）
        messageStore.persistAssistantMessage(sessionId, null, "scheme", "",
            writeQuietly(metadataOf(response, request.getIdempotencyKey())));
        return response;
    }

    /** 报价快照行 → 方案项（只取可报价行）。 */
    private List<SchemeItemRequest> itemsFromQuote(AgentQuote quote) {
        List<AgentQuoteResponse.QuoteLine> lines = readLines(quote.getItems());
        List<SchemeItemRequest> items = new ArrayList<>();
        int sortOrder = 0;
        for (AgentQuoteResponse.QuoteLine line : lines) {
            if (!Boolean.TRUE.equals(line.getQuotable()) || !StringUtils.hasText(line.getRskuId())) {
                continue;
            }
            SchemeItemRequest item = new SchemeItemRequest();
            item.setRspuId(line.getRspuId());
            item.setRskuId(line.getRskuId());
            item.setQuantity(line.getQuantity() != null && line.getQuantity() > 0 ? line.getQuantity() : 1);
            item.setSortOrder(++sortOrder);
            items.add(item);
        }
        return items;
    }

    /** confirmed 项现场重解析 → 方案项（无法解析供应报价的产品跳过并记日志）。 */
    private List<SchemeItemRequest> itemsFromConfirmed(String sessionId) {
        List<AgentConfirmedItem> confirmed = confirmedItemMapper.selectList(new QueryWrapper<AgentConfirmedItem>()
            .eq("session_id", sessionId)
            .eq("status", "confirmed")
            .orderByAsc("created_at"));
        if (confirmed.isEmpty()) {
            throw new BusinessException("没有可导出的已确认产品");
        }
        Map<String, AgentRskuResolver.ResolvedRsku> resolved = rskuResolver.resolveMinPriceRsku(
            confirmed.stream().map(AgentConfirmedItem::getRspuId).toList());
        List<SchemeItemRequest> items = new ArrayList<>();
        int sortOrder = 0;
        for (AgentConfirmedItem confirmedItem : confirmed) {
            AgentRskuResolver.ResolvedRsku picked = resolved.get(confirmedItem.getRspuId());
            if (picked == null) {
                log.info("导出方案跳过无法解析供应报价的产品，rspuId={}, sessionId={}",
                    confirmedItem.getRspuId(), sessionId);
                continue;
            }
            SchemeItemRequest item = new SchemeItemRequest();
            item.setRspuId(confirmedItem.getRspuId());
            item.setRskuId(picked.rsku().getRskuId());
            item.setQuantity(confirmedItem.getQuantity() != null && confirmedItem.getQuantity() > 0
                ? confirmedItem.getQuantity() : 1);
            item.setSortOrder(++sortOrder);
            items.add(item);
        }
        return items;
    }

    /** 默认方案名：Agent方案-{会话前缀}-{时间戳}（绕同用户方案名唯一约束）。 */
    private String defaultSchemeName(String sessionId) {
        String prefix = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
        return "Agent方案-" + prefix + "-" + LocalDateTime.now().format(NAME_TIME_FORMAT);
    }

    /** 幂等反查：会话 scheme 消息 metadata 中匹配幂等键的首条导出记录。 */
    @SuppressWarnings("unchecked")
    private AgentSchemeExportResponse findExistingExport(String sessionId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        for (AgentMessage message : messageStore.listBySession(sessionId)) {
            if (!"scheme".equals(message.getMessageType()) || !StringUtils.hasText(message.getMetadata())) {
                continue;
            }
            try {
                Map<String, Object> metadata = objectMapper.readValue(message.getMetadata(), Map.class);
                if (!idempotencyKey.equals(metadata.get("idempotencyKey"))) {
                    continue;
                }
                AgentSchemeExportResponse response = new AgentSchemeExportResponse();
                response.setSchemeId(textOrNull(metadata.get("schemeId")));
                response.setSchemeName(textOrNull(metadata.get("schemeName")));
                Object itemCount = metadata.get("itemCount");
                response.setItemCount(itemCount instanceof Number n ? n.intValue() : null);
                response.setDetailUrl(textOrNull(metadata.get("detailUrl")));
                return response;
            } catch (Exception e) {
                log.warn("方案消息 metadata 解析失败，跳过，messageId={}", message.getMessageId());
            }
        }
        return null;
    }

    private Map<String, Object> metadataOf(AgentSchemeExportResponse response, String idempotencyKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemeId", response.getSchemeId());
        metadata.put("schemeName", response.getSchemeName());
        metadata.put("itemCount", response.getItemCount());
        metadata.put("detailUrl", response.getDetailUrl());
        metadata.put("idempotencyKey", idempotencyKey);
        return metadata;
    }

    private String textOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private List<AgentQuoteResponse.QuoteLine> readLines(String items) {
        if (!StringUtils.hasText(items)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(items, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("报价行快照解析失败，按空列表处理");
            return List.of();
        }
    }

    private String writeQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("方案导出快照序列化失败", e);
        }
    }
}
