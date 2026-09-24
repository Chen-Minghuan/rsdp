package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.ExtendedCategoryProperties;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.CategoryShadowPrediction;
import com.rsdp.entity.AiCategoryShadowResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.KnowledgeProductType;
import com.rsdp.mapper.AiCategoryShadowResultMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

class CategoryShadowServiceTest {

    private ExtendedCategoryProperties properties;
    private DictService dictService;
    private KnowledgeProductTypeService productTypeService;
    private VisionService visionService;
    private AiCategoryShadowResultMapper mapper;
    private CategoryShadowService service;

    @BeforeEach
    void setUp() {
        properties = new ExtendedCategoryProperties();
        dictService = mock(DictService.class);
        productTypeService = mock(KnowledgeProductTypeService.class);
        visionService = mock(VisionService.class);
        mapper = mock(AiCategoryShadowResultMapper.class);
        service = new CategoryShadowService(
            properties, dictService, productTypeService, visionService, mapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "model", "qwen3-vl-plus");
    }

    @Test
    void evaluateAndStore_flagDisabled_shouldHaveNoSideEffects() {
        service.evaluateAndStore("REC-1", "RSPU-1", "IMG-1", "FS", new byte[]{1});

        verifyNoInteractions(dictService, productTypeService, visionService, mapper);
    }

    @Test
    void evaluateAndStore_shadowEnabled_shouldOnlyInsertShadowResult() {
        properties.setShadowEnabled(true);
        CategoryDict fs = new CategoryDict();
        fs.setDictCode("FS");
        when(dictService.listByType("category")).thenReturn(List.of(fs));
        KnowledgeProductType type = new KnowledgeProductType();
        type.setTypeCode("WRITING_DESK");
        type.setBusinessCategoryCode("DK");
        when(productTypeService.listActive()).thenReturn(List.of(type));
        when(visionService.classifyCategoryShadow(any(InputStream.class), anySet(), any()))
            .thenReturn(new CategoryShadowPrediction("DK", "WRITING_DESK", "HIGH", "桌面与书桌支撑可见"));
        AiLabels labels = new AiLabels();
        labels.setSixDimTags(Map.of("A", "一字矩形", "E", "实木"));
        when(visionService.recognizeImage(any(InputStream.class), eq("DK"))).thenReturn(labels);

        service.evaluateAndStore("REC-1", "RSPU-1", "IMG-1", "FS", new byte[]{1, 2});

        ArgumentCaptor<AiCategoryShadowResult> captor = ArgumentCaptor.forClass(AiCategoryShadowResult.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getLegacyCategory()).isEqualTo("FS");
        assertThat(captor.getValue().getExtendedCategory()).isEqualTo("DK");
        assertThat(captor.getValue().getProductType()).isEqualTo("WRITING_DESK");
        assertThat(captor.getValue().getSixDimResult()).contains("一字矩形");
        verify(visionService).recognizeImage(any(InputStream.class), eq("DK"));
    }

    @Test
    void evaluateAndStore_aiFailure_shouldNotEscapeOrPersist() {
        properties.setShadowEnabled(true);
        when(dictService.listByType("category")).thenReturn(List.of());
        when(productTypeService.listActive()).thenThrow(new IllegalStateException("db unavailable"));

        assertThatCode(() -> service.evaluateAndStore(
            "REC-1", "RSPU-1", "IMG-1", "FS", new byte[]{1}))
            .doesNotThrowAnyException();
        verify(mapper, never()).insert(any(AiCategoryShadowResult.class));
    }
}
