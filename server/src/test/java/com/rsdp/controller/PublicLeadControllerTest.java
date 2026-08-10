package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PlatformLeadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PublicLeadController} 单元测试。
 */
@WebMvcTest(PublicLeadController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PublicLeadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlatformLeadService platformLeadService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void createLead_shouldReturnLeadId() throws Exception {
        LeadCreateResponse created = new LeadCreateResponse();
        created.setLeadId("LEAD-1");
        created.setStatus("pending");
        when(platformLeadService.createLead(any(LeadCreateRequest.class))).thenReturn(created);

        LeadCreateRequest request = new LeadCreateRequest();
        request.setName("王女士");
        request.setPhone("138****6621");
        request.setSource("site_form");
        request.setIntent("客厅整配");
        request.setBudget("2万");

        mockMvc.perform(post("/api/v1/public/leads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.leadId").value("LEAD-1"))
            .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    void createLead_blankName_shouldReturnValidationError() throws Exception {
        LeadCreateRequest request = new LeadCreateRequest();
        request.setPhone("13800006621");
        request.setSource("site_form");

        mockMvc.perform(post("/api/v1/public/leads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void createLead_blankPhone_shouldReturnValidationError() throws Exception {
        LeadCreateRequest request = new LeadCreateRequest();
        request.setName("王女士");
        request.setSource("site_form");

        mockMvc.perform(post("/api/v1/public/leads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void createLead_invalidSource_shouldReturnBusinessError() throws Exception {
        when(platformLeadService.createLead(any(LeadCreateRequest.class)))
            .thenThrow(new BusinessException("非法的留资来源: hack"));

        LeadCreateRequest request = new LeadCreateRequest();
        request.setName("王女士");
        request.setPhone("13800006621");
        request.setSource("hack");

        mockMvc.perform(post("/api/v1/public/leads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("非法的留资来源: hack"));
    }
}
