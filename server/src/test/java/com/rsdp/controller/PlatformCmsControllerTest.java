package com.rsdp.controller;

import com.rsdp.dto.response.SceneCoverResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PlatformCmsService;
import com.rsdp.service.PlatformImageService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PlatformCmsController} 空间场景封面（V35）单元测试。
 */
@WebMvcTest(PlatformCmsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PlatformCmsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlatformCmsService platformCmsService;

    @MockBean
    private PlatformImageService platformImageService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listSceneCovers_shouldReturnCovers() throws Exception {
        SceneCoverResponse living = new SceneCoverResponse();
        living.setCode("LIVING");
        living.setName("客厅");
        living.setImageId("IMG-1");
        living.setImageUrl("/api/v1/images/IMG-1");
        SceneCoverResponse study = new SceneCoverResponse();
        study.setCode("STUDY");
        study.setName("书房");
        when(platformCmsService.listSceneCovers()).thenReturn(List.of(living, study));

        mockMvc.perform(get("/api/v1/platform/scene-covers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].code").value("LIVING"))
            .andExpect(jsonPath("$.data[0].imageUrl").value("/api/v1/images/IMG-1"))
            .andExpect(jsonPath("$.data[1].code").value("STUDY"))
            .andExpect(jsonPath("$.data[1].imageUrl").doesNotExist());
    }

    @Test
    void updateSceneCover_shouldReturnUpdatedCover() throws Exception {
        SceneCoverResponse updated = new SceneCoverResponse();
        updated.setCode("LIVING");
        updated.setName("客厅");
        updated.setImageId("IMG-9");
        updated.setImageUrl("/api/v1/images/IMG-9");
        when(platformCmsService.updateSceneCover(eq("LIVING"), any())).thenReturn(updated);

        mockMvc.perform(put("/api/v1/platform/scene-covers/LIVING")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageId\":\"IMG-9\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.imageId").value("IMG-9"))
            .andExpect(jsonPath("$.data.imageUrl").value("/api/v1/images/IMG-9"));
    }

    @Test
    void updateSceneCover_nullImage_shouldClearCover() throws Exception {
        SceneCoverResponse cleared = new SceneCoverResponse();
        cleared.setCode("LIVING");
        cleared.setName("客厅");
        when(platformCmsService.updateSceneCover(eq("LIVING"), any())).thenReturn(cleared);

        mockMvc.perform(put("/api/v1/platform/scene-covers/LIVING")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageId\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.imageId").doesNotExist());
    }

    @Test
    void updateSceneCover_nonSceneDict_shouldReturnBusinessError() throws Exception {
        when(platformCmsService.updateSceneCover(eq("PE"), any()))
            .thenThrow(new BusinessException("场景字典项不存在: scene=PE"));

        mockMvc.perform(put("/api/v1/platform/scene-covers/PE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageId\":\"IMG-9\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("场景字典项不存在: scene=PE"));
    }

    @Test
    void updateSceneCover_missingImage_shouldReturnBusinessError() throws Exception {
        when(platformCmsService.updateSceneCover(eq("LIVING"), any()))
            .thenThrow(new BusinessException("图片不存在: IMG-X"));

        mockMvc.perform(put("/api/v1/platform/scene-covers/LIVING")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageId\":\"IMG-X\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("图片不存在: IMG-X"));
    }
}
