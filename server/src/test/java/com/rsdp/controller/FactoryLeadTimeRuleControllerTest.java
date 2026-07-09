package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.FactoryLeadTimeRuleRequest;
import com.rsdp.dto.response.FactoryLeadTimeRuleResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.FactoryLeadTimeRuleService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FactoryLeadTimeRuleController} 单元测试。
 */
@WebMvcTest(FactoryLeadTimeRuleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class FactoryLeadTimeRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FactoryLeadTimeRuleService ruleService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listByFactory_shouldReturnRules() throws Exception {
        FactoryLeadTimeRuleResponse response = new FactoryLeadTimeRuleResponse();
        response.setRuleId(1L);
        response.setFactoryCode("F001");
        response.setCategoryCode("FS");
        response.setBaseDays(30);

        when(ruleService.listByFactory("F001")).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/factories/F001/lead-time-rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].baseDays").value(30));
    }

    @Test
    void saveRule_shouldReturnOk() throws Exception {
        FactoryLeadTimeRuleRequest request = new FactoryLeadTimeRuleRequest();
        request.setBaseDays(30);
        request.setCategoryCode("FS");
        request.setMaterialGradeCode("FABRIC_A");

        when(ruleService.saveRule(any(FactoryLeadTimeRuleRequest.class))).thenReturn(1L);

        mockMvc.perform(post("/api/v1/factories/F001/lead-time-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value(1));

        verify(ruleService, times(1)).saveRule(any(FactoryLeadTimeRuleRequest.class));
    }

    @Test
    void saveRule_shouldReturnBadRequestWhenBaseDaysMissing() throws Exception {
        FactoryLeadTimeRuleRequest request = new FactoryLeadTimeRuleRequest();

        mockMvc.perform(post("/api/v1/factories/F001/lead-time-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deleteRule_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/api/v1/factories/F001/lead-time-rules/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(ruleService, times(1)).deleteRule(1L);
    }

    @Test
    void calculate_shouldReturnLeadTimeDays() throws Exception {
        when(ruleService.calculateLeadTime("F001", "FS", "FABRIC_A", "standard", 1))
            .thenReturn(30);

        mockMvc.perform(get("/api/v1/factories/F001/lead-time-rules/calculate")
                .param("categoryCode", "FS")
                .param("materialGradeCode", "FABRIC_A")
                .param("processType", "standard")
                .param("quantity", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value(30));

        verify(ruleService, times(1)).calculateLeadTime("F001", "FS", "FABRIC_A", "standard", 1);
    }
}
