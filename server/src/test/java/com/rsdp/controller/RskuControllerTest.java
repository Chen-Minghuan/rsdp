package com.rsdp.controller;

import com.rsdp.dto.request.RskuPriceUpdateRequest;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.service.RskuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RskuController} 单元测试。
 */
@WebMvcTest(RskuController.class)
@Import(GlobalExceptionHandler.class)
class RskuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RskuService rskuService;

    @Test
    void updatePrice_shouldReturnOk() throws Exception {
        RskuPriceUpdateRequest request = new RskuPriceUpdateRequest();
        request.setFactoryPrice(new BigDecimal("1500"));
        request.setChangeReason("原材料涨价");

        mockMvc.perform(put("/api/v1/products/RSPU-001/rsku/RSKU-001/price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(rskuService).updateRskuPrice(eq("RSPU-001"), eq("RSKU-001"), eq(new BigDecimal("1500")), eq("原材料涨价"));
    }

    @Test
    void updatePrice_shouldReturnNotFoundWhenRspuIdMismatch() throws Exception {
        RskuPriceUpdateRequest request = new RskuPriceUpdateRequest();
        request.setFactoryPrice(new BigDecimal("1500"));

        doThrow(new ResourceNotFoundException("RSKU 不属于该产品"))
            .when(rskuService).updateRskuPrice(any(), any(), any(), any());

        mockMvc.perform(put("/api/v1/products/RSPU-001/rsku/RSKU-001/price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("RSKU 不属于该产品"));
    }

    @Test
    void updatePrice_shouldReturnBadRequestWhenPriceMissing() throws Exception {
        RskuPriceUpdateRequest request = new RskuPriceUpdateRequest();

        mockMvc.perform(put("/api/v1/products/RSPU-001/rsku/RSKU-001/price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
