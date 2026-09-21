package com.rsdp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.controller.AgentSessionController;
import com.rsdp.agent.dto.AgentSessionResponse;
import com.rsdp.agent.dto.ConfirmedItemResponse;
import com.rsdp.agent.service.AgentConfirmService;
import com.rsdp.agent.service.AgentQuoteService;
import com.rsdp.agent.service.AgentSchemeExportService;
import com.rsdp.agent.service.AgentSessionBusyException;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.agent.service.AgentStreamService;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentSessionController} 单元测试（Web 层契约：创建/越权/参数校验/409 并发）。
 */
@WebMvcTest(AgentSessionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AgentSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentSessionService sessionService;

    @MockBean
    private AgentStreamService streamService;

    @MockBean
    private AgentConfirmService confirmService;

    @MockBean
    private AgentQuoteService quoteService;

    @MockBean
    private AgentSchemeExportService schemeExportService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private AgentSessionResponse sampleSession() {
        AgentSessionResponse response = new AgentSessionResponse();
        response.setSessionId("SES-1");
        response.setStatus("active");
        response.setCurrentVersionNo(0);
        return response;
    }

    @Test
    void createSessionShouldReturnNewSession() throws Exception {
        when(sessionService.create("王女士")).thenReturn(sampleSession());

        mockMvc.perform(post("/api/v1/agent/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"王女士\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.sessionId").value("SES-1"))
            .andExpect(jsonPath("$.data.status").value("active"));
        verify(sessionService).create("王女士");
    }

    @Test
    void createSessionWithoutBodyShouldPassNullCustomerName() throws Exception {
        when(sessionService.create(isNull())).thenReturn(sampleSession());

        mockMvc.perform(post("/api/v1/agent/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        verify(sessionService).create(isNull());
    }

    @Test
    void listSessionsShouldReturnList() throws Exception {
        when(sessionService.listMine()).thenReturn(List.of(sampleSession()));

        mockMvc.perform(get("/api/v1/agent/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].sessionId").value("SES-1"));
    }

    @Test
    void getSessionForbiddenShouldReturn403Code() throws Exception {
        when(sessionService.getDetail("SES-1"))
            .thenThrow(new BusinessException(403, "无权访问该会话"));

        mockMvc.perform(get("/api/v1/agent/sessions/SES-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("无权访问该会话"));
    }

    @Test
    void getSessionNotFoundShouldReturn404Code() throws Exception {
        when(sessionService.getDetail("SES-9"))
            .thenThrow(new ResourceNotFoundException("会话不存在: SES-9"));

        mockMvc.perform(get("/api/v1/agent/sessions/SES-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void confirmWithEmptyBodyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void confirmWithZeroQuantityShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recommendItemId\":\"RI-1\",\"quantity\":0,\"idempotencyKey\":\"k-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void confirmWithValidBodyShouldReturnConfirmedItem() throws Exception {
        ConfirmedItemResponse item = new ConfirmedItemResponse();
        item.setItemId("CFI-1");
        item.setRspuId("RSPU-9");
        when(confirmService.confirm(eq("SES-1"), any())).thenReturn(item);

        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recommendItemId\":\"RI-1\",\"quantity\":2,\"idempotencyKey\":\"k-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.itemId").value("CFI-1"));
    }

    @Test
    void streamMessageBusyShouldReturnHttp409() throws Exception {
        when(streamService.startStream(eq("SES-1"), any()))
            .thenThrow(new AgentSessionBusyException("SESSION_BUSY"));

        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"cm-1\",\"content\":\"帮我找沙发\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("SESSION_BUSY"));
    }

    @Test
    void streamMessageNotFoundShouldReturnHttp404() throws Exception {
        when(streamService.startStream(eq("SES-9"), any()))
            .thenThrow(new ResourceNotFoundException("会话不存在: SES-9"));

        mockMvc.perform(post("/api/v1/agent/sessions/SES-9/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"cm-1\",\"content\":\"帮我找沙发\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void streamMessageWithBlankContentShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/messages/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"cm-1\",\"content\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void closeSessionWithRunningRunShouldReturn409Code() throws Exception {
        when(sessionService.close("SES-1"))
            .thenThrow(new BusinessException(409, "SESSION_BUSY"));

        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/close"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("SESSION_BUSY"));
    }

    @Test
    void deleteSessionShouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/v1/agent/sessions/SES-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        verify(sessionService).delete("SES-1");
    }

    @Test
    void deleteSessionWithRunningRunShouldReturn409Code() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(409, "SESSION_BUSY"))
            .when(sessionService).delete("SES-1");

        mockMvc.perform(delete("/api/v1/agent/sessions/SES-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("SESSION_BUSY"));
    }

    @Test
    void generateQuoteShouldReturnQuoteCard() throws Exception {
        com.rsdp.agent.dto.AgentQuoteResponse quote = new com.rsdp.agent.dto.AgentQuoteResponse();
        quote.setQuoteId("AQT-1");
        quote.setListTotal(new java.math.BigDecimal("7000"));
        when(quoteService.generate(eq("SES-1"), any())).thenReturn(quote);

        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"idem-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.quoteId").value("AQT-1"));
    }

    @Test
    void generateQuoteWithoutIdempotencyKeyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void exportSchemeShouldReturnSchemeRef() throws Exception {
        com.rsdp.agent.dto.AgentSchemeExportResponse exported = new com.rsdp.agent.dto.AgentSchemeExportResponse();
        exported.setSchemeId("SCH-1");
        exported.setSchemeName("Agent方案-SES-1-20260917");
        exported.setDetailUrl("/schemes/SCH-1");
        when(schemeExportService.exportScheme(eq("SES-1"), any())).thenReturn(exported);

        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"idem-2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.schemeId").value("SCH-1"))
            .andExpect(jsonPath("$.data.detailUrl").value("/schemes/SCH-1"));
    }

    @Test
    void exportSchemeWithoutIdempotencyKeyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/sessions/SES-1/scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
