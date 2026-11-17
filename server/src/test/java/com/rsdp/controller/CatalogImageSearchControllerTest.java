package com.rsdp.controller;

import com.rsdp.service.CatalogSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogImageSearchControllerTest {

    @Test
    void imageEndpointsReturnSearchIdWithTheirOwnScope() throws Exception {
        CatalogSearchService search = mock(CatalogSearchService.class);
        when(search.startImageSearch(any(), eq(true))).thenReturn("public-id");
        when(search.startImageSearch(any(), eq(false))).thenReturn("staff-id");
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogImageSearchController(search)).build();
        var image = new MockMultipartFile("image", "sofa.jpg", "image/jpeg", new byte[] {1});

        mvc.perform(multipart("/api/v1/public/products/image-search").file(image))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("public-id"));
        mvc.perform(multipart("/api/v1/products/image-search").file(image))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("staff-id"));
        verify(search).startImageSearch(any(), eq(true));
        verify(search).startImageSearch(any(), eq(false));
    }
}
