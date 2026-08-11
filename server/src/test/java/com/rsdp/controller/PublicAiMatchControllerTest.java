package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.PublicAiMatchSchemeRequest;
import com.rsdp.dto.response.PublicAiMatchAnalyzeResponse;
import com.rsdp.dto.response.PublicAiMatchSchemeResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PublicAiMatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PublicAiMatchController} 单元测试。
 */
@WebMvcTest(PublicAiMatchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PublicAiMatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PublicAiMatchService publicAiMatchService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void analyze_shouldReturnRooms() throws Exception {
        PublicAiMatchAnalyzeResponse response = new PublicAiMatchAnalyzeResponse();
        PublicAiMatchAnalyzeResponse.RoomItem room = new PublicAiMatchAnalyzeResponse.RoomItem();
        room.setRoomType("living_room");
        room.setRoomName("客厅");
        room.setWidthMm(4200);
        room.setDepthMm(3800);
        room.setAreaM2(new BigDecimal("15.96"));
        room.setDimensionText("4200×3800");
        room.setConfidence("high");
        response.setRooms(List.of(room));
        when(publicAiMatchService.analyze(any(), isNull())).thenReturn(response);

        MockMultipartFile file = new MockMultipartFile(
            "file", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        mockMvc.perform(multipart("/api/v1/public/ai-match/analyze").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.rooms[0].roomType").value("living_room"))
            .andExpect(jsonPath("$.data.rooms[0].roomName").value("客厅"))
            .andExpect(jsonPath("$.data.rooms[0].widthMm").value(4200))
            .andExpect(jsonPath("$.data.rooms[0].confidence").value("high"));
    }

    @Test
    void generateScheme_shouldReturnSanitizedScheme() throws Exception {
        PublicAiMatchSchemeResponse response = new PublicAiMatchSchemeResponse();
        response.setReasoning("现代简约客厅搭配");
        response.setTotalRetailPrice(new BigDecimal("5298.00"));
        PublicAiMatchSchemeResponse.Item item = new PublicAiMatchSchemeResponse.Item();
        item.setRspuId("RSPU-001");
        item.setProductName("云朵沙发");
        item.setRetailPrice(new BigDecimal("3999.00"));
        item.setPrimaryImageUrl("/api/v1/images/IMG-1");
        response.setItems(List.of(item));
        when(publicAiMatchService.generateScheme(any(PublicAiMatchSchemeRequest.class)))
            .thenReturn(response);

        PublicAiMatchSchemeRequest request = new PublicAiMatchSchemeRequest();
        request.setStylePreference("MC");
        request.setBudgetLimit(new BigDecimal("20000"));

        mockMvc.perform(post("/api/v1/public/ai-match/scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.reasoning").value("现代简约客厅搭配"))
            .andExpect(jsonPath("$.data.totalRetailPrice").value(5298.00))
            .andExpect(jsonPath("$.data.items[0].rspuId").value("RSPU-001"))
            .andExpect(jsonPath("$.data.items[0].factoryCode").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].factoryPrice").doesNotExist())
            .andExpect(jsonPath("$.data.totalPrice").doesNotExist());
    }

    @Test
    void generateScheme_negativeBudget_shouldReturnValidationError() throws Exception {
        PublicAiMatchSchemeRequest request = new PublicAiMatchSchemeRequest();
        request.setBudgetLimit(new BigDecimal("-100"));

        mockMvc.perform(post("/api/v1/public/ai-match/scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
