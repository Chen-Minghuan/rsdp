package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuFactoryMappingRequest;
import com.rsdp.dto.response.RspuFactoryMappingResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.RspuFactoryMappingService;
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
 * {@link RspuFactoryMappingController} 单元测试。
 */
@WebMvcTest(RspuFactoryMappingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RspuFactoryMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RspuFactoryMappingService mappingService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listByRspu_shouldReturnMappings() throws Exception {
        RspuFactoryMappingResponse response = new RspuFactoryMappingResponse();
        response.setMappingId(1L);
        response.setRspuId("RSPU-001");
        response.setFactoryCode("F001");
        response.setFactoryName("测试工厂");

        when(mappingService.listByRspu("RSPU-001", "广东")).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/products/RSPU-001/factories")
                .param("province", "广东"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].factoryCode").value("F001"));
    }

    @Test
    void saveMapping_shouldReturnOk() throws Exception {
        RspuFactoryMappingRequest request = new RspuFactoryMappingRequest();
        request.setFactoryCode("F001");
        request.setIsPrimary(true);
        request.setMoq(10);

        when(mappingService.saveMapping(any(RspuFactoryMappingRequest.class))).thenReturn(1L);

        mockMvc.perform(post("/api/v1/products/RSPU-001/factories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value(1));

        verify(mappingService, times(1)).saveMapping(any(RspuFactoryMappingRequest.class));
    }

    @Test
    void deleteMapping_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/api/v1/products/RSPU-001/factories/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(mappingService, times(1)).deleteMapping(1L);
    }
}
