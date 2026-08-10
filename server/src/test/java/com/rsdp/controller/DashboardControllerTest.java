package com.rsdp.controller;

import com.rsdp.dto.response.DashboardSummaryResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DashboardController} 单元测试。
 */
@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void summary_shouldReturnAggregates() throws Exception {
        DashboardSummaryResponse summary = new DashboardSummaryResponse();
        summary.setRspuTotal(1286L);
        summary.setRskuTotal(3514L);
        summary.setAiPassRate(new BigDecimal("96.2"));
        summary.setMonthOrderAmount(new BigDecimal("184000.00"));
        summary.setTodayLeadCount(12L);
        when(dashboardService.summary()).thenReturn(summary);

        mockMvc.perform(get("/api/v1/dashboard/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.rspuTotal").value(1286))
            .andExpect(jsonPath("$.data.rskuTotal").value(3514))
            .andExpect(jsonPath("$.data.aiPassRate").value(96.2))
            .andExpect(jsonPath("$.data.monthOrderAmount").value(184000.00))
            .andExpect(jsonPath("$.data.todayLeadCount").value(12));
    }
}
