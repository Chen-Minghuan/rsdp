package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.PricingRuleUpsertRequest;
import com.rsdp.dto.response.PricingPreviewItemResponse;
import com.rsdp.dto.response.PricingPreviewSummaryResponse;
import com.rsdp.dto.response.PricingRuleResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PricingPreviewService;
import com.rsdp.service.PricingRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PricingController} 单元测试。
 */
@WebMvcTest(PricingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PricingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PricingRuleService pricingRuleService;

    @MockBean
    private PricingPreviewService pricingPreviewService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listRules_shouldReturnRules() throws Exception {
        PricingRuleResponse rule = new PricingRuleResponse();
        rule.setRuleId("PRULE-FS");
        rule.setCategoryCode("FS");
        rule.setCategoryName("沙发");
        rule.setMarkupMultiplier(new BigDecimal("3.0"));
        when(pricingRuleService.listRules()).thenReturn(List.of(rule));

        mockMvc.perform(get("/api/v1/pricing/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].categoryCode").value("FS"))
            .andExpect(jsonPath("$.data[0].markupMultiplier").value(3.0));
    }

    @Test
    void upsertRule_shouldReturnOk() throws Exception {
        PricingRuleUpsertRequest request = new PricingRuleUpsertRequest();
        request.setMarkupMultiplier(new BigDecimal("3.0"));

        PricingRuleResponse rule = new PricingRuleResponse();
        rule.setRuleId("PRULE-FS");
        rule.setCategoryCode("FS");
        rule.setMarkupMultiplier(new BigDecimal("3.0"));
        when(pricingRuleService.upsertRule(eq("FS"), any(PricingRuleUpsertRequest.class))).thenReturn(rule);

        mockMvc.perform(put("/api/v1/pricing/rules/FS")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.markupMultiplier").value(3.0));

        verify(pricingRuleService, times(1)).upsertRule(eq("FS"), any(PricingRuleUpsertRequest.class));
    }

    @Test
    void upsertRule_shouldReturnBadRequestWhenMultiplierMissing() throws Exception {
        mockMvc.perform(put("/api/v1/pricing/rules/FS")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PricingRuleUpsertRequest())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deleteRule_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/api/v1/pricing/rules/FS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(pricingRuleService, times(1)).deleteRule("FS");
    }

    @Test
    void preview_shouldReturnPage() throws Exception {
        PricingPreviewItemResponse row = new PricingPreviewItemResponse();
        row.setRspuId("RSPU-001");
        row.setPriceSource("GLOBAL");
        row.setSalePrice(new BigDecimal("2500.00"));
        when(pricingPreviewService.preview(null, "GLOBAL", null, 1, 20))
            .thenReturn(PageResult.of(1, 1, 20, List.of(row)));

        mockMvc.perform(get("/api/v1/pricing/preview").param("source", "GLOBAL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].priceSource").value("GLOBAL"));

        verify(pricingPreviewService).preview(null, "GLOBAL", null, 1, 20);
    }

    @Test
    void previewSummary_shouldReturnCounts() throws Exception {
        PricingPreviewSummaryResponse summary = new PricingPreviewSummaryResponse();
        summary.setManual(2);
        summary.setCategoryRule(3);
        summary.setGlobal(5);
        summary.setUnpriced(1);
        summary.setBelowCost(1);
        summary.setTotal(11);
        when(pricingPreviewService.summary()).thenReturn(summary);

        mockMvc.perform(get("/api/v1/pricing/preview/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.manual").value(2))
            .andExpect(jsonPath("$.data.categoryRule").value(3))
            .andExpect(jsonPath("$.data.global").value(5))
            .andExpect(jsonPath("$.data.unpriced").value(1))
            .andExpect(jsonPath("$.data.belowCost").value(1))
            .andExpect(jsonPath("$.data.total").value(11));
    }
}
