package com.rsdp.controller;

import com.rsdp.dto.request.DictCreateRequest;
import com.rsdp.dto.response.SixDimSchemaResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.DictService;
import com.rsdp.service.SixDimSchemaService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    private SixDimSchemaService sixDimSchemaService;

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

    @Test
    void listTypeSummary_shouldReturnSummary() throws Exception {
        when(dictService.listTypeSummary()).thenReturn(List.of(
            new com.rsdp.dto.response.DictTypeSummaryResponse("fabric", 12L),
            new com.rsdp.dto.response.DictTypeSummaryResponse("material", 19L)));

        mockMvc.perform(get("/api/v1/dicts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].dictType").value("fabric"))
            .andExpect(jsonPath("$.data[0].count").value(12));
    }

    @Test
    void listByType_allTrue_shouldIncludeDisabled() throws Exception {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("fabric");
        dict.setDictCode("WB");
        dict.setDictName("网布");
        dict.setStatus("disabled");

        when(dictService.listAllByType("fabric")).thenReturn(List.of(dict));

        mockMvc.perform(get("/api/v1/dicts/fabric?all=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].status").value("disabled"));
    }

    @Test
    void updateDict_shouldReturnUpdatedDict() throws Exception {
        CategoryDict updated = new CategoryDict();
        updated.setDictType("material");
        updated.setDictCode("LE");
        updated.setDictName("头层皮革");
        updated.setStatus("active");
        updated.setAliases("[\"真皮\",\"牛皮\"]");

        when(dictService.updateDict(eq("material"), eq("LE"), eq("头层皮革"), any(), any(), any()))
            .thenReturn(updated);
        when(dictService.parseAliases("[\"真皮\",\"牛皮\"]")).thenReturn(List.of("真皮", "牛皮"));

        mockMvc.perform(put("/api/v1/dicts/material/LE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dictName\":\"头层皮革\",\"aliases\":[\"真皮\",\"牛皮\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dictName").value("头层皮革"))
            .andExpect(jsonPath("$.data.aliases[0]").value("真皮"));
    }

    @Test
    void updateDictStatus_shouldReturnUpdatedStatus() throws Exception {
        CategoryDict updated = new CategoryDict();
        updated.setDictType("fabric");
        updated.setDictCode("WB");
        updated.setDictName("网布");
        updated.setStatus("disabled");

        when(dictService.updateDictStatus("fabric", "WB", "disabled")).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/dicts/fabric/WB/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("disabled"));
    }

    @Test
    void updateDictStatus_invalidStatus_shouldReturnValidationError() throws Exception {
        mockMvc.perform(patch("/api/v1/dicts/fabric/WB/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"deleted\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 六维标签维度定义（V25） ----------

    private SixDimSchemaResponse sfSchema() {
        java.util.Map<String, SixDimSchemaResponse.DimDefinition> dims = new java.util.LinkedHashMap<>();
        dims.put("A", new SixDimSchemaResponse.DimDefinition("轮廓形态", "整体造型"));
        dims.put("C", new SixDimSchemaResponse.DimDefinition("扶手特征", "扶手形态"));
        return new SixDimSchemaResponse("SF", "沙发", dims);
    }

    @Test
    void getSixDimSchema_withCategoryCode_shouldReturnSingleSchema() throws Exception {
        when(sixDimSchemaService.getSchema("SF")).thenReturn(sfSchema());

        mockMvc.perform(get("/api/v1/dicts/six-dim-schema").param("categoryCode", "SF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.categoryCode").value("SF"))
            .andExpect(jsonPath("$.data.dims.A.label").value("轮廓形态"));
    }

    @Test
    void getSixDimSchema_withoutCategoryCode_shouldReturnAllSchemas() throws Exception {
        when(sixDimSchemaService.listAllSchemas()).thenReturn(List.of(sfSchema()));

        mockMvc.perform(get("/api/v1/dicts/six-dim-schema"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].categoryCode").value("SF"));
    }

    @Test
    void updateSixDimSchema_shouldReturnUpdatedSchema() throws Exception {
        when(sixDimSchemaService.updateDim(eq("SF"), eq("C"), eq("扶手形态特征"), eq("新说明")))
            .thenReturn(sfSchema());

        mockMvc.perform(put("/api/v1/dicts/six-dim-schema/sf/c")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"扶手形态特征\",\"description\":\"新说明\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.categoryCode").value("SF"));
    }

    @Test
    void updateSixDimSchema_blankLabel_shouldReturnValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/dicts/six-dim-schema/SF/C")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"\",\"description\":\"新说明\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
