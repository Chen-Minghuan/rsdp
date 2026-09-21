package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.AgentRskuResolver;
import com.rsdp.agent.dto.AgentQuoteResponse;
import com.rsdp.agent.dto.GenerateQuoteRequest;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentQuote;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentQuoteMapper;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentQuoteService;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.EffectivePriceRateResolver;
import com.rsdp.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentQuoteService} 单元测试（报价口径 / 幂等 / 权限 / 成本字段红线守卫）。
 */
@ExtendWith(MockitoExtension.class)
class AgentQuoteServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private AgentConfirmedItemMapper confirmedItemMapper;

    @Mock
    private AgentQuoteMapper quoteMapper;

    @Mock
    private AgentRskuResolver rskuResolver;

    @Mock
    private EffectivePriceRateResolver priceRateResolver;

    @Mock
    private AgentMessageStore messageStore;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private AgentQuoteService service;

    @BeforeEach
    void setUp() {
        service = new AgentQuoteService(sessionService, confirmedItemMapper, quoteMapper,
            rskuResolver, priceRateResolver, messageStore, objectMapper);
    }

    private AgentSession activeSession() {
        AgentSession session = new AgentSession();
        session.setSessionId("SES-1");
        session.setStatus("active");
        return session;
    }

    private AgentConfirmedItem confirmed(String itemId, String rspuId, int quantity) {
        AgentConfirmedItem item = new AgentConfirmedItem();
        item.setItemId(itemId);
        item.setSessionId("SES-1");
        item.setRspuId(rspuId);
        item.setQuantity(quantity);
        item.setStatus("confirmed");
        item.setSpec("{\"productName\":\"产品-" + rspuId + "\",\"primaryImageUrl\":\"/api/v1/images/IMG-1\"}");
        return item;
    }

    private AgentRskuResolver.ResolvedRsku resolved(String rskuId, String salePrice, Integer leadTimeDays) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        rsku.setLeadTimeDays(leadTimeDays);
        return new AgentRskuResolver.ResolvedRsku(rsku, new PricingService.SalePriceDetail(
            new BigDecimal(salePrice), PricingService.SOURCE_MANUAL, null, new BigDecimal("800")));
    }

    private GenerateQuoteRequest request(String idempotencyKey) {
        GenerateQuoteRequest request = new GenerateQuoteRequest();
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private void stubPermitted() {
        // SecurityOperatorContext 静态 mock 在各用例内按需开启
    }

    @Test
    void shouldGenerateQuoteWithSalePriceOnly() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-1");
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 2), confirmed("CFI-2", "RSPU-2", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection()))
                .thenReturn(Map.of(
                    "RSPU-1", resolved("RSKU-1", "2000", 15),
                    "RSPU-2", resolved("RSKU-2", "3000", 30)));
            when(priceRateResolver.resolve()).thenReturn(new BigDecimal("0.9"));

            AgentQuoteResponse response = service.generate("SES-1", request("IDEM-1"));

            // 合计：2000×2 + 3000×1 = 7000；成交价 = 7000 × 0.9 = 6300
            assertThat(response.getListTotal()).isEqualByComparingTo("7000.00");
            assertThat(response.getDealTotal()).isEqualByComparingTo("6300.00");
            assertThat(response.getPriceRate()).isEqualByComparingTo("0.9");
            assertThat(response.getMaxLeadTimeDays()).isEqualTo(30);
            assertThat(response.getLines()).hasSize(2);
            assertThat(response.getPriceNote()).contains("正式报价以订单为准");

            // 红线：序列化后不含任何成本字段
            String json = writeJson(response);
            assertThat(json).doesNotContain("factoryPrice", "cost", "margin", "800");

            // 报价快照落库 + quote 消息落档案
            ArgumentCaptor<AgentQuote> captor = ArgumentCaptor.forClass(AgentQuote.class);
            verify(quoteMapper).insert(captor.capture());
            assertThat(captor.getValue().getItems()).doesNotContain("factoryPrice", "cost", "margin");
            assertThat(captor.getValue().getStatus()).isEqualTo("generated");
            verify(messageStore).persistAssistantMessage(eq("SES-1"), isNull(), eq("quote"), anyString(), anyString());
        }
    }

    @Test
    void unquotableLineShouldBeMarkedAndExcludedFromTotal() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-1");
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1), confirmed("CFI-2", "RSPU-2", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection()))
                .thenReturn(Map.of("RSPU-1", resolved("RSKU-1", "2000", 15)));
            when(priceRateResolver.resolve()).thenReturn(new BigDecimal("1"));

            AgentQuoteResponse response = service.generate("SES-1", request("IDEM-2"));

            assertThat(response.getLines().get(1).getQuotable()).isFalse();
            assertThat(response.getLines().get(1).getUnitSalePrice()).isNull();
            assertThat(response.getListTotal()).isEqualByComparingTo("2000.00");
        }
    }

    @Test
    void idempotentHitShouldReturnExistingQuote() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            AgentQuote existing = new AgentQuote();
            existing.setQuoteId("AQT-1");
            existing.setSessionId("SES-1");
            existing.setItems("[{\"rspuId\":\"RSPU-1\",\"quotable\":true}]");
            existing.setListTotal(new BigDecimal("1000"));
            existing.setPriceRate(BigDecimal.ONE);
            existing.setDealTotal(new BigDecimal("1000"));
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

            AgentQuoteResponse response = service.generate("SES-1", request("IDEM-3"));

            assertThat(response.getQuoteId()).isEqualTo("AQT-1");
            assertThat(response.getLines()).hasSize(1);
            // 幂等命中不再走解析与落库
            org.mockito.Mockito.verifyNoInteractions(rskuResolver, messageStore);
        }
    }

    @Test
    void idempotencyKeyUsedByOtherSessionShould409() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            AgentQuote existing = new AgentQuote();
            existing.setSessionId("SES-OTHER");
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

            assertThatThrownBy(() -> service.generate("SES-1", request("IDEM-4")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键已被其他会话使用");
        }
    }

    @Test
    void duplicateKeyOnInsertShouldFallbackToExisting() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-1");
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            AgentQuote conflicted = new AgentQuote();
            conflicted.setQuoteId("AQT-9");
            conflicted.setSessionId("SES-1");
            conflicted.setItems("[]");
            // 先查不存在；插入冲突；重查命中
            when(quoteMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(null)
                .thenReturn(conflicted);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection()))
                .thenReturn(Map.of("RSPU-1", resolved("RSKU-1", "2000", null)));
            when(priceRateResolver.resolve()).thenReturn(BigDecimal.ONE);
            org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_agent_quote_idem"))
                .when(quoteMapper).insert(any(AgentQuote.class));

            AgentQuoteResponse response = service.generate("SES-1", request("IDEM-5"));

            assertThat(response.getQuoteId()).isEqualTo("AQT-9");
        }
    }

    @Test
    void noConfirmedItemsShouldThrow() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

            assertThatThrownBy(() -> service.generate("SES-1", request("IDEM-6")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可报价的已确认产品");
        }
    }

    @Test
    void allLinesUnquotableShouldThrow() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection())).thenReturn(Map.of());
            when(priceRateResolver.resolve()).thenReturn(BigDecimal.ONE);

            assertThatThrownBy(() -> service.generate("SES-1", request("IDEM-7")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不能报价");
        }
    }

    @Test
    void subsetWithForeignItemShouldThrow() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(true);
            when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
            when(quoteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1)));
            GenerateQuoteRequest request = request("IDEM-8");
            request.setConfirmedItemIds(List.of("CFI-1", "CFI-FOREIGN"));

            assertThatThrownBy(() -> service.generate("SES-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前会话");
        }
    }

    @Test
    void missingQuotePermissionShould403() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.QUOTE_GENERATE)).thenReturn(false);

            assertThatThrownBy(() -> service.generate("SES-1", request("IDEM-9")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quote:generate");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
