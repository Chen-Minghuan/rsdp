package com.rsdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteFolderResponse;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.FavoriteFolderService;
import com.rsdp.service.FavoriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FavoriteController} 单元测试。
 */
@WebMvcTest(FavoriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class FavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FavoriteService favoriteService;

    @MockBean
    private FavoriteFolderService favoriteFolderService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void list_shouldReturnFavorites() throws Exception {
        FavoriteResponse response = new FavoriteResponse();
        response.setFavoriteId("FAV-00000001");
        response.setRspuId("RSPU-00000001");
        response.setFolderId("FAVD-00000001");
        response.setGroupName("默认分组");
        response.setProductName("测试产品");
        response.setCreatedAt(LocalDateTime.now());

        when(favoriteService.list(anyString(), eq(false))).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/favorites").param("folderId", "FAVD-00000001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].favoriteId").value("FAV-00000001"))
            .andExpect(jsonPath("$.data[0].rspuId").value("RSPU-00000001"))
            .andExpect(jsonPath("$.data[0].folderId").value("FAVD-00000001"));
    }

    @Test
    void listFolders_shouldReturnFolders() throws Exception {
        FavoriteFolderResponse folder = new FavoriteFolderResponse();
        folder.setFolderId("FAVD-00000001");
        folder.setFolderName("客厅灵感");
        folder.setFavoriteCount(2);
        when(favoriteFolderService.listMyFolders()).thenReturn(List.of(folder));

        mockMvc.perform(get("/api/v1/favorites/folders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].folderId").value("FAVD-00000001"))
            .andExpect(jsonPath("$.data[0].favoriteCount").value(2));
    }

    @Test
    void check_shouldReturnFavoriteRspuIds() throws Exception {
        when(favoriteService.check(List.of("RSPU-00000001", "RSPU-00000002")))
            .thenReturn(List.of("RSPU-00000001"));

        mockMvc.perform(get("/api/v1/favorites/check")
                .param("rspuIds", "RSPU-00000001,RSPU-00000002"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0]").value("RSPU-00000001"));
    }

    @Test
    void add_shouldReturnCreatedFavorite() throws Exception {
        FavoriteResponse response = new FavoriteResponse();
        response.setFavoriteId("FAV-00000001");
        response.setRspuId("RSPU-00000001");

        when(favoriteService.add(any(FavoriteRequest.class))).thenReturn(response);

        FavoriteRequest request = new FavoriteRequest();
        request.setRspuId("RSPU-00000001");
        request.setGroupName("默认分组");

        mockMvc.perform(post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.favoriteId").value("FAV-00000001"));
    }

    @Test
    void remove_shouldReturnOk() throws Exception {
        doNothing().when(favoriteService).remove(eq("RSPU-00000001"));

        mockMvc.perform(delete("/api/v1/favorites/RSPU-00000001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void add_blankRspuId_shouldReturnValidationError() throws Exception {
        FavoriteRequest request = new FavoriteRequest();
        request.setGroupName("默认分组");

        mockMvc.perform(post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
