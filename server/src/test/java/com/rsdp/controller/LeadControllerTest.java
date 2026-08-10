package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.LeadAssignRequest;
import com.rsdp.dto.request.LeadFollowLogRequest;
import com.rsdp.dto.request.LeadStatusRequest;
import com.rsdp.dto.response.LeadAssigneeResponse;
import com.rsdp.dto.response.LeadListItemResponse;
import com.rsdp.dto.response.LeadSourceStatsResponse;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LeadController} 单元测试。
 */
@WebMvcTest(LeadController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class LeadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlatformLeadService platformLeadService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private LeadListItemResponse sampleItem() {
        LeadListItemResponse item = new LeadListItemResponse();
        item.setLeadId("LEAD-1");
        item.setName("王女士");
        item.setPhoneMasked("138****6621");
        item.setSource("site_form");
        item.setStatus("pending");
        item.setFollowLogCount(0);
        return item;
    }

    @Test
    void listLeads_shouldReturnMaskedPage() throws Exception {
        when(platformLeadService.listLeads(isNull(), isNull(), anyInt(), anyInt()))
            .thenReturn(PageResult.of(1, 1, 10, List.of(sampleItem())));

        mockMvc.perform(get("/api/v1/leads"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].phoneMasked").value("138****6621"));
    }

    @Test
    void listLeads_withFilters_shouldPassParams() throws Exception {
        when(platformLeadService.listLeads(any(), any(), anyInt(), anyInt()))
            .thenReturn(PageResult.of(0, 1, 10, List.of()));

        mockMvc.perform(get("/api/v1/leads")
                .param("status", "pending")
                .param("source", "ai_match")
                .param("page", "2")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.Mockito.verify(platformLeadService)
            .listLeads(eq("pending"), eq("ai_match"), eq(2), eq(20));
    }

    @Test
    void sourceStats_shouldReturnStats() throws Exception {
        LeadSourceStatsResponse stats = new LeadSourceStatsResponse();
        stats.setAiMatch(3L);
        stats.setSiteForm(5L);
        stats.setDesignBooking(2L);
        stats.setPending(7L);
        when(platformLeadService.sourceStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/leads/source-stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.aiMatch").value(3))
            .andExpect(jsonPath("$.data.siteForm").value(5))
            .andExpect(jsonPath("$.data.designBooking").value(2))
            .andExpect(jsonPath("$.data.pending").value(7));
    }

    @Test
    void assignees_shouldReturnCandidates() throws Exception {
        LeadAssigneeResponse assignee = new LeadAssigneeResponse();
        assignee.setUsername("admin");
        assignee.setNickname("管理员");
        when(platformLeadService.listAssignees()).thenReturn(List.of(assignee));

        mockMvc.perform(get("/api/v1/leads/assignees"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].username").value("admin"));
    }

    @Test
    void assign_shouldReturnUpdatedItem() throws Exception {
        LeadListItemResponse item = sampleItem();
        item.setAssignee("admin");
        when(platformLeadService.assign(eq("LEAD-1"), eq("admin"))).thenReturn(item);

        LeadAssignRequest request = new LeadAssignRequest();
        request.setAssignee("admin");

        mockMvc.perform(put("/api/v1/leads/LEAD-1/assign")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.assignee").value("admin"));
    }

    @Test
    void assign_blankAssignee_shouldReturnValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/leads/LEAD-1/assign")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void appendFollowLog_shouldReturnUpdatedItem() throws Exception {
        LeadListItemResponse item = sampleItem();
        item.setFollowLogCount(1);
        when(platformLeadService.appendFollowLog(eq("LEAD-1"), anyString())).thenReturn(item);

        LeadFollowLogRequest request = new LeadFollowLogRequest();
        request.setContent("已电话沟通");

        mockMvc.perform(post("/api/v1/leads/LEAD-1/follow-logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.followLogCount").value(1));
    }

    @Test
    void updateStatus_shouldReturnUpdatedItem() throws Exception {
        LeadListItemResponse item = sampleItem();
        item.setStatus("contacted");
        when(platformLeadService.updateStatus(eq("LEAD-1"), eq("contacted"))).thenReturn(item);

        LeadStatusRequest request = new LeadStatusRequest();
        request.setStatus("contacted");

        mockMvc.perform(put("/api/v1/leads/LEAD-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("contacted"));
    }

    @Test
    void updateStatus_illegalValue_shouldReturnValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/leads/LEAD-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"archived\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void updateStatus_backwardTransition_shouldReturnBusinessError() throws Exception {
        when(platformLeadService.updateStatus(eq("LEAD-1"), eq("pending")))
            .thenThrow(new BusinessException("线索状态仅允许向前流转（pending → contacted → done），当前状态: contacted"));

        LeadStatusRequest request = new LeadStatusRequest();
        request.setStatus("pending");

        mockMvc.perform(put("/api/v1/leads/LEAD-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("仅允许向前流转")));
    }

    @Test
    void anyLeadEndpoint_serviceThrowsNotFound_shouldReturn404Code() throws Exception {
        when(platformLeadService.updateStatus(anyString(), anyString()))
            .thenThrow(new com.rsdp.exception.ResourceNotFoundException("留资线索不存在: LEAD-9"));

        LeadStatusRequest request = new LeadStatusRequest();
        request.setStatus("contacted");

        mockMvc.perform(put("/api/v1/leads/LEAD-9/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404));
    }
}
