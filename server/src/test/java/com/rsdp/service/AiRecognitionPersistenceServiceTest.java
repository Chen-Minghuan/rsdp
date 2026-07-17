package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRecognitionPersistenceService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AiRecognitionPersistenceServiceTest {

    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private ImageAssetsMapper imageAssetsMapper;
    @Mock
    private AiRecognitionMapper aiRecognitionMapper;
    @Mock
    private RspuStyleMapper rspuStyleMapper;
    @Mock
    private RspuSceneMapper rspuSceneMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DictResolverService dictResolverService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiRecognitionPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        injectField("objectMapper", objectMapper);
        var user = User.withUsername("admin").password("").roles("ADMIN").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void injectField(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = AiRecognitionPersistenceService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(persistenceService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void saveSuccess_shouldPersistSecondaryStylesAsNonPrimary() {
        // AI 输出主风格「中古风」+ 备选「奶油风/北欧风」（含与主重复项，验证去重过滤）
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);

        when(dictResolverService.resolveCodeByName("style", "中古风")).thenReturn("MC");
        when(dictResolverService.resolveCodesByNames("style", List.of("奶油风", "中古风", "北欧风")))
            .thenReturn(List.of("CR", "MC", "NC"));
        when(dictResolverService.resolveCodesByNames("scene", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("material", null)).thenReturn(List.of());

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setSecondaryStyles(List.of("奶油风", "中古风", "北欧风"));

        persistenceService.saveSuccess("TASK-1", "RSPU-TEST01", "IMG-1", "REC-1",
            "qwen3-vl-plus", labels, 100, null);

        // rspu_style 三条：MC 主；CR、NC 辅（与主重复的 MC 被过滤）；无记录时直接插入
        ArgumentCaptor<RspuStyle> styleCaptor = ArgumentCaptor.forClass(RspuStyle.class);
        verify(rspuStyleMapper, never()).delete(any());
        verify(rspuStyleMapper, times(3)).insert(styleCaptor.capture());
        List<RspuStyle> saved = styleCaptor.getAllValues();
        assertThat(saved.get(0).getStyleCode()).isEqualTo("MC");
        assertThat(saved.get(0).getIsPrimary()).isTrue();
        assertThat(saved.get(1).getStyleCode()).isEqualTo("CR");
        assertThat(saved.get(1).getIsPrimary()).isFalse();
        assertThat(saved.get(2).getStyleCode()).isEqualTo("NC");
        assertThat(saved.get(2).getIsPrimary()).isFalse();
    }

    @Test
    void saveSuccess_shouldWritePrimaryOnlyWhenNoSecondaryStyles() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);

        when(dictResolverService.resolveCodeByName("style", "中古风")).thenReturn("MC");
        when(dictResolverService.resolveCodesByNames("style", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("scene", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("material", null)).thenReturn(List.of());

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");

        persistenceService.saveSuccess("TASK-1", "RSPU-TEST01", "IMG-1", "REC-1",
            "qwen3-vl-plus", labels, 100, null);

        // 无备选风格：保持旧行为，只写一条主风格
        ArgumentCaptor<RspuStyle> styleCaptor = ArgumentCaptor.forClass(RspuStyle.class);
        verify(rspuStyleMapper, times(1)).insert(styleCaptor.capture());
        assertThat(styleCaptor.getValue().getStyleCode()).isEqualTo("MC");
        assertThat(styleCaptor.getValue().getIsPrimary()).isTrue();
    }

    @Test
    void saveSuccess_shouldNotOverwriteHumanProvidedFields() {
        // Excel/人工已明确提供风格、材质、场景、主色：AI 识别结果不得覆盖
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("MC");
        rspu.setMaterialTags("[\"WO\"]");
        rspu.setSceneTags("[\"LIVING\"]");
        rspu.setColorPrimaryName("焦糖棕");
        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(rspuStyleMapper.selectCount(any())).thenReturn(1L);
        when(rspuSceneMapper.selectCount(any())).thenReturn(1L);

        when(dictResolverService.resolveCodeByName("style", "北欧风")).thenReturn("NC");
        when(dictResolverService.resolveCodesByNames("style", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("scene", List.of("卧室"))).thenReturn(List.of("BEDROOM"));
        when(dictResolverService.resolveCodesByNames("material", List.of("金属"))).thenReturn(List.of("METAL"));

        AiLabels labels = new AiLabels();
        labels.setStyle("北欧风");
        labels.setColorPrimaryName("米白");
        labels.setSceneTags(List.of("卧室"));
        labels.setMaterialTags(List.of("金属"));
        labels.setSixDimTags(java.util.Map.of("A", "蛋形"));
        labels.setConfidence("high");

        persistenceService.saveSuccess("TASK-1", "RSPU-TEST01", "IMG-1", "REC-1",
            "qwen3-vl-plus", labels, 100, null);

        // 人工提供的字段全部保持原值
        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");
        assertThat(rspu.getMaterialTags()).isEqualTo("[\"WO\"]");
        assertThat(rspu.getSceneTags()).isEqualTo("[\"LIVING\"]");
        assertThat(rspu.getColorPrimaryName()).isEqualTo("焦糖棕");
        verify(rspuStyleMapper, never()).insert(any(RspuStyle.class));
        verify(rspuSceneMapper, never()).insert(any(com.rsdp.entity.RspuScene.class));

        // 空缺字段（六维标签）由 AI 补充；AI 产物字段（置信度）始终更新
        assertThat(rspu.getSixDimTags()).isEqualTo("{\"A\":\"蛋形\"}");
        assertThat(rspu.getAestheticsConfidence()).isEqualTo("high");
    }

    @Test
    void saveSuccess_shouldFillUnidentifiedPositioningLabel() {
        // 「待识别」占位视为空缺，AI 识别结果应填充并写入风格关联
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("待识别");
        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(rspuStyleMapper.selectCount(any())).thenReturn(0L);
        when(rspuSceneMapper.selectCount(any())).thenReturn(0L);

        when(dictResolverService.resolveCodeByName("style", "中古风")).thenReturn("MC");
        when(dictResolverService.resolveCodesByNames("style", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("scene", null)).thenReturn(List.of());
        when(dictResolverService.resolveCodesByNames("material", null)).thenReturn(List.of());

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");

        persistenceService.saveSuccess("TASK-1", "RSPU-TEST01", "IMG-1", "REC-1",
            "qwen3-vl-plus", labels, 100, null);

        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");
        verify(rspuStyleMapper, times(1)).insert(any(RspuStyle.class));
    }
}
