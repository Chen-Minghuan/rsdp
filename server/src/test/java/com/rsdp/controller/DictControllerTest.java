package com.rsdp.controller;

import com.rsdp.dto.request.DictCreateRequest;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.DictService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DictController} 单元测试。
 */
@WebMvcTest(DictController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DictControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DictService dictService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listByType_shouldReturnDicts() throws Exception {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setDictName("真皮");

        when(dictService.listByType("material")).thenReturn(List.of(dict));

        mockMvc.perform(get("/api/v1/dicts/material"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].dictCode").value("PE"))
            .andExpect(jsonPath("$.data[0].dictName").value("真皮"));
    }

    @Test
    void createDict_shouldReturnCreatedDict() throws Exception {
        doAnswer(invocation -> {
            CategoryDict d = invocation.getArgument(0);
            d.setDictType("material");
            d.setDictCode("VELVET");
            d.setDictName("天鹅绒");
            d.setSortOrder(1);
            d.setStatus("active");
            return null;
        }).when(dictService).createDict(any(CategoryDict.class));

        DictCreateRequest request = new DictCreateRequest();
        request.setDictType("material");
        request.setDictCode("velvet");
        request.setDictName("天鹅绒");

        mockMvc.perform(post("/api/v1/dicts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dictCode").value("VELVET"))
            .andExpect(jsonPath("$.data.dictName").value("天鹅绒"));
    }

    @Test
    void createDict_duplicate_shouldReturnBusinessError() throws Exception {
        doAnswer(invocation -> {
            throw new BusinessException("字典项已存在: material=VELVET");
        }).when(dictService).createDict(any(CategoryDict.class));

        DictCreateRequest request = new DictCreateRequest();
        request.setDictType("material");
        request.setDictCode("VELVET");
        request.setDictName("天鹅绒");

        mockMvc.perform(post("/api/v1/dicts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("字典项已存在: material=VELVET"));
    }

    @Test
    void createDict_blankCode_shouldReturnValidationError() throws Exception {
        DictCreateRequest request = new DictCreateRequest();
        request.setDictType("material");
        request.setDictName("天鹅绒");

        mockMvc.perform(post("/api/v1/dicts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
