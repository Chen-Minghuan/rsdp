package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.AgentRskuResolver;
import com.rsdp.agent.dto.AgentSchemeExportResponse;
import com.rsdp.agent.dto.ExportSchemeRequest;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentQuote;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentQuoteMapper;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentSchemeExportService;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.PricingService;
import com.rsdp.service.SchemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSchemeExportService} 单元测试（导出转换 / 幂等 / 权限守卫）。
 */
@ExtendWith(MockitoExtension.class)
class AgentSchemeExportServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private AgentConfirmedItemMapper confirmedItemMapper;

    @Mock
    private AgentQuoteMapper quoteMapper;

    @Mock
    private AgentRskuResolver rskuResolver;

    @Mock
    private SchemeService schemeService;

    @Mock
    private AgentMessageStore messageStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentSchemeExportService service;

    @BeforeEach
    void setUp() {
        service = new AgentSchemeExportService(sessionService, confirmedItemMapper, quoteMapper,
            rskuResolver, schemeService, messageStore, objectMapper);
    }

    private AgentSession activeSession() {
        AgentSession session = new AgentSession();
        session.setSessionId("SES-12345678");
        session.setStatus("active");
        return session;
    }

    private AgentConfirmedItem confirmed(String itemId, String rspuId, int quantity) {
        AgentConfirmedItem item = new AgentConfirmedItem();
        item.setItemId(itemId);
        item.setRspuId(rspuId);
        item.setQuantity(quantity);
        item.setStatus("confirmed");
        return item;
    }

    private AgentRskuResolver.ResolvedRsku resolved(String rskuId) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        return new AgentRskuResolver.ResolvedRsku(rsku, new PricingService.SalePriceDetail(
            new BigDecimal("2000"), PricingService.SOURCE_MANUAL, null, null));
    }

    private ExportSchemeRequest request(String idempotencyKey) {
        ExportSchemeRequest request = new ExportSchemeRequest();
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private void permitSchemeCreate(org.mockito.MockedStatic<SecurityOperatorContext> mocked) {
        mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.SCHEME_CREATE)).thenReturn(true);
    }

    @Test
    void shouldExportConfirmedItemsAsScheme() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of());
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 2), confirmed("CFI-2", "RSPU-2", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection()))
                .thenReturn(Map.of("RSPU-1", resolved("RSKU-1"), "RSPU-2", resolved("RSKU-2")));
            SchemeResponse scheme = new SchemeResponse();
            scheme.setSchemeId("SCH-1");
            scheme.setSchemeName("Agent方案-SES-1234-20260917120000");
            when(schemeService.createScheme(any(SchemeCreateRequest.class))).thenReturn(scheme);

            AgentSchemeExportResponse response = service.exportScheme("SES-12345678", request("IDEM-1"));

            assertThat(response.getSchemeId()).isEqualTo("SCH-1");
            assertThat(response.getItemCount()).isEqualTo(2);
            assertThat(response.getDetailUrl()).isEqualTo("/schemes/SCH-1");

            ArgumentCaptor<SchemeCreateRequest> captor = ArgumentCaptor.forClass(SchemeCreateRequest.class);
            verify(schemeService).createScheme(captor.capture());
            assertThat(captor.getValue().getItems()).hasSize(2);
            assertThat(captor.getValue().getItems().get(0).getRskuId()).isEqualTo("RSKU-1");
            assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(2);
            assertThat(captor.getValue().getSchemeName()).startsWith("Agent方案-SES-1234");

            // 方案消息落档案（含幂等键）
            verify(messageStore).persistAssistantMessage(eq("SES-12345678"), isNull(), eq("scheme"),
                anyString(), org.mockito.ArgumentMatchers.contains("IDEM-1"));
        }
    }

    @Test
    void unresolvableProductShouldBeSkippedButResolvableKept() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of());
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1), confirmed("CFI-2", "RSPU-2", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection()))
                .thenReturn(Map.of("RSPU-1", resolved("RSKU-1")));
            SchemeResponse scheme = new SchemeResponse();
            scheme.setSchemeId("SCH-2");
            when(schemeService.createScheme(any(SchemeCreateRequest.class))).thenReturn(scheme);

            AgentSchemeExportResponse response = service.exportScheme("SES-12345678", request("IDEM-2"));

            assertThat(response.getItemCount()).isEqualTo(1);
        }
    }

    @Test
    void quoteSnapshotShouldDriveItemsAndMarkQuoteExported() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of());
            AgentQuote quote = new AgentQuote();
            quote.setQuoteId("AQT-1");
            quote.setSessionId("SES-12345678");
            quote.setStatus("generated");
            quote.setItems("["
                + "{\"rspuId\":\"RSPU-1\",\"rskuId\":\"RSKU-1\",\"quantity\":2,\"quotable\":true},"
                + "{\"rspuId\":\"RSPU-2\",\"rskuId\":null,\"quantity\":1,\"quotable\":false}"
                + "]");
            when(quoteMapper.selectById("AQT-1")).thenReturn(quote);
            SchemeResponse scheme = new SchemeResponse();
            scheme.setSchemeId("SCH-3");
            when(schemeService.createScheme(any(SchemeCreateRequest.class))).thenReturn(scheme);

            ExportSchemeRequest request = request("IDEM-3");
            request.setQuoteId("AQT-1");
            AgentSchemeExportResponse response = service.exportScheme("SES-12345678", request);

            // 不可报价行不带入方案
            assertThat(response.getItemCount()).isEqualTo(1);
            ArgumentCaptor<SchemeCreateRequest> captor = ArgumentCaptor.forClass(SchemeCreateRequest.class);
            verify(schemeService).createScheme(captor.capture());
            assertThat(captor.getValue().getItems().get(0).getRskuId()).isEqualTo("RSKU-1");
            assertThat(captor.getValue().getItems().get(0).getQuantity()).isEqualTo(2);
            // 报价置为已导出
            ArgumentCaptor<AgentQuote> quoteCaptor = ArgumentCaptor.forClass(AgentQuote.class);
            verify(quoteMapper).updateById(quoteCaptor.capture());
            assertThat(quoteCaptor.getValue().getStatus()).isEqualTo("exported");
        }
    }

    @Test
    void quoteFromOtherSessionShouldThrow() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of());
            AgentQuote quote = new AgentQuote();
            quote.setSessionId("SES-OTHER");
            when(quoteMapper.selectById("AQT-1")).thenReturn(quote);

            ExportSchemeRequest request = request("IDEM-4");
            request.setQuoteId("AQT-1");
            assertThatThrownBy(() -> service.exportScheme("SES-12345678", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前会话");
        }
    }

    @Test
    void idempotentReplayShouldReturnFirstExport() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            AgentMessage schemeMessage = new AgentMessage();
            schemeMessage.setMessageType("scheme");
            schemeMessage.setMetadata("{\"schemeId\":\"SCH-OLD\",\"schemeName\":\"旧方案\","
                + "\"itemCount\":2,\"detailUrl\":\"/schemes/SCH-OLD\",\"idempotencyKey\":\"IDEM-5\"}");
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of(schemeMessage));

            AgentSchemeExportResponse response = service.exportScheme("SES-12345678", request("IDEM-5"));

            assertThat(response.getSchemeId()).isEqualTo("SCH-OLD");
            assertThat(response.getItemCount()).isEqualTo(2);
            org.mockito.Mockito.verifyNoInteractions(schemeService, rskuResolver);
        }
    }

    @Test
    void allItemsUnresolvableShouldThrow() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            permitSchemeCreate(mocked);
            when(sessionService.requireAccessibleSession("SES-12345678")).thenReturn(activeSession());
            when(messageStore.listBySession("SES-12345678")).thenReturn(List.of());
            when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(confirmed("CFI-1", "RSPU-1", 1)));
            when(rskuResolver.resolveMinPriceRsku(anyCollection())).thenReturn(Map.of());

            assertThatThrownBy(() -> service.exportScheme("SES-12345678", request("IDEM-6")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可导出的方案项");
        }
    }

    @Test
    void missingSchemePermissionShould403() {
        try (var mocked = org.mockito.Mockito.mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority(Permissions.SCHEME_CREATE)).thenReturn(false);

            assertThatThrownBy(() -> service.exportScheme("SES-12345678", request("IDEM-7")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scheme:create");
        }
    }
}
