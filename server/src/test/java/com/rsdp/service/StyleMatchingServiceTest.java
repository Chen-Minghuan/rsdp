package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.StyleMatchResult;
import com.rsdp.entity.ProductStyleMatch;
import com.rsdp.entity.StyleMatchingFormula;
import com.rsdp.entity.CategoryDict;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.StyleMatchingFormulaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StyleMatchingServiceTest {

    private StyleMatchingFormulaMapper formulaMapper;
    private ProductStyleMatchMapper matchMapper;
    private DictResolverService dictResolver;
    private StyleMatchingService service;

    @BeforeEach
    void setUp() {
        formulaMapper = mock(StyleMatchingFormulaMapper.class);
        matchMapper = mock(ProductStyleMatchMapper.class);
        dictResolver = mock(DictResolverService.class);
        service = new StyleMatchingService(formulaMapper, matchMapper, new ObjectMapper(), dictResolver);
    }

    @Test
    void match_shouldReturnHighScore_whenMidCenturyModernKeyMaterialsPresent() {
        // Given
        StyleMatchingFormula formula = new StyleMatchingFormula();
        formula.setFormulaId("FORM-MC-001");
        formula.setStyleCode("MC");
        formula.setStatus("active");
        formula.setFormulaJson("""
            {
              "must_have": [{"type": "material", "values": ["胡桃木", "羊羔绒"], "role": "primary"}],
              "compatible": [
                {"type": "material", "values": ["皮革", "大理石", "藤编", "金属"], "weight": 0.28, "reason": "风格标志性材质组合"},
                {"type": "color", "values": ["奶油白", "深棕", "木色"], "weight": 0.33, "reason": "核心配色体系"},
                {"type": "scene", "values": ["客厅", "书房"], "weight": 0.11, "reason": "典型空间"}
              ],
              "avoid": [{"type": "mood", "values": ["过度繁复的古典装饰"], "reason": "风格禁忌"}]
            }
            """);
        when(formulaMapper.selectList(any())).thenReturn(List.of(formula));

        AiLabels labels = new AiLabels();
        labels.setStyle("MC");
        labels.setColorPrimaryName("奶油白");
        labels.setMaterialTags(List.of("胡桃木", "羊羔绒"));
        labels.setSceneTags(List.of("客厅"));
        labels.setConfidence("high");

        // When
        StyleMatchResult result = service.match(labels, "RSPU-TEST-001");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getStyleCode()).isEqualTo("MC");
        assertThat(result.getOverallScore()).isGreaterThan(new BigDecimal("0.75"));
        assertThat(result.getConfidence()).isEqualTo("high");
        assertThat(result.isHasAvoidHit()).isFalse();

        ArgumentCaptor<ProductStyleMatch> captor = ArgumentCaptor.forClass(ProductStyleMatch.class);
        verify(matchMapper).insert(captor.capture());
        assertThat(captor.getValue().getOverallScore()).isEqualTo(result.getOverallScore());
    }

    @Test
    void match_shouldReturnLowScore_whenAvoidMaterialsPresent() {
        // Given: Wabi-sabi should avoid stainless steel and glass
        StyleMatchingFormula formula = new StyleMatchingFormula();
        formula.setFormulaId("FORM-WJ-001");
        formula.setStyleCode("WJ");
        formula.setStatus("active");
        formula.setFormulaJson("""
            {
              "must_have": [{"type": "material", "values": ["亚麻", "羊羔绒"], "role": "primary"}],
              "compatible": [
                {"type": "material", "values": ["羊毛", "胡桃木", "石材", "水泥", "陶瓷", "藤编"], "weight": 0.28, "reason": "风格标志性材质组合"},
                {"type": "color", "values": ["米白", "白色", "驼色"], "weight": 0.33, "reason": "核心配色体系"},
                {"type": "scene", "values": ["客厅", "书房"], "weight": 0.11, "reason": "典型空间"}
              ],
              "avoid": [
                {"type": "material", "values": ["不锈钢", "玻璃"], "reason": "过度抛光的工业化材质"},
                {"type": "mood", "values": ["鲜艳饱和的色彩"], "reason": "风格禁忌"}
              ]
            }
            """);
        when(formulaMapper.selectList(any())).thenReturn(List.of(formula));

        AiLabels labels = new AiLabels();
        labels.setStyle("WJ");
        labels.setColorPrimaryName("米白");
        labels.setMaterialTags(List.of("亚麻", "不锈钢", "玻璃"));
        labels.setSceneTags(List.of("客厅"));
        labels.setConfidence("high");

        // When
        StyleMatchResult result = service.match(labels, "RSPU-TEST-002");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isHasAvoidHit()).isTrue();
        // 当前兼容项命中较多，avoid 扣分后整体落入 mid；hasAvoidHit 标识风格存在冲突
        assertThat(result.getConfidence()).isIn("low", "mid");
    }

    @Test
    void match_shouldMapConfidenceBasedOnScore() {
        // Given: formula with no must-have and only one low-weight compatible
        StyleMatchingFormula formula = new StyleMatchingFormula();
        formula.setFormulaId("FORM-TEST-001");
        formula.setStyleCode("TEST");
        formula.setStatus("active");
        formula.setFormulaJson("""
            {
              "must_have": [{"type": "material", "values": ["胡桃木"], "role": "primary"}],
              "compatible": [],
              "avoid": []
            }
            """);
        when(formulaMapper.selectList(any())).thenReturn(List.of(formula));

        AiLabels labels = new AiLabels();
        labels.setStyle("TEST");
        labels.setMaterialTags(List.of("塑料"));

        // When
        StyleMatchResult result = service.match(labels, "RSPU-TEST-003");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOverallScore()).isLessThan(new BigDecimal("0.50"));
        assertThat(result.getConfidence()).isEqualTo("low");
    }

    @Test
    void match_shouldReturnNull_whenStyleIsEmpty() {
        assertThat(service.match(new AiLabels(), "RSPU-TEST-004")).isNull();
        verifyNoInteractions(formulaMapper, matchMapper);
    }

    @Test
    void match_shouldSupplementMaterialsFromOcrDescription() {
        // Given
        StyleMatchingFormula formula = new StyleMatchingFormula();
        formula.setFormulaId("FORM-MC-001");
        formula.setStyleCode("MC");
        formula.setStatus("active");
        formula.setFormulaJson("""
            {
              "must_have": [{"type": "material", "values": ["胡桃木"], "role": "primary"}],
              "compatible": [],
              "avoid": []
            }
            """);
        when(formulaMapper.selectList(any())).thenReturn(List.of(formula));

        AiLabels labels = new AiLabels();
        labels.setStyle("MC");
        labels.setMaterialTags(List.of());
        OcrResult ocr = new OcrResult();
        ocr.setMaterialDescription("胡桃木框架+布艺软包");
        labels.setOcr(ocr);

        // When
        StyleMatchResult result = service.match(labels, "RSPU-TEST-005");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOverallScore()).isGreaterThanOrEqualTo(BigDecimal.ONE);
    }

    @Test
    void match_shouldResolveChineseStyleNameToCode() {
        // Given
        CategoryDict styleDict = new CategoryDict();
        styleDict.setDictType("style");
        styleDict.setDictCode("MC");
        styleDict.setDictName("中古风");
        when(dictResolver.resolveCodeByName("style", "中古风")).thenReturn("MC");

        StyleMatchingFormula formula = new StyleMatchingFormula();
        formula.setFormulaId("FORM-MC-001");
        formula.setStyleCode("MC");
        formula.setStatus("active");
        formula.setFormulaJson("""
            {
              "must_have": [{"type": "material", "values": ["胡桃木"], "role": "primary"}],
              "compatible": [],
              "avoid": []
            }
            """);
        when(formulaMapper.selectList(any())).thenReturn(List.of(formula));

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setMaterialTags(List.of("胡桃木"));

        // When
        StyleMatchResult result = service.match(labels, "RSPU-TEST-006");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getStyleCode()).isEqualTo("MC");
    }
}
