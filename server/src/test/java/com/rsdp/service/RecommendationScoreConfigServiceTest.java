package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RecommendationScoreConfigCreateRequest;
import com.rsdp.dto.request.RecommendationScoreConfigUpdateRequest;
import com.rsdp.dto.response.RecommendationScoreConfigResponse;
import com.rsdp.entity.RecommendationScoreConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.RecommendationScoreConfigMapper;
import com.rsdp.security.SecurityUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecommendationScoreConfigService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RecommendationScoreConfigServiceTest {

    @Mock
    private RecommendationScoreConfigMapper configMapper;

    private RecommendationScoreConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new RecommendationScoreConfigService(configMapper, new ObjectMapper());
        var user = new SecurityUser("USER-001", "admin", "", org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_shouldSaveAndReturnResponse() {
        RecommendationScoreConfigCreateRequest request = new RecommendationScoreConfigCreateRequest();
        request.setConfigKey("default");
        request.setName("默认配置");
        request.setWeights(Map.of("styleMatch", BigDecimal.ONE));
        request.setIsDefault(true);
        request.setIsActive(true);

        when(configMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.insert(any(RecommendationScoreConfig.class))).thenAnswer(inv -> {
            RecommendationScoreConfig cfg = inv.getArgument(0);
            cfg.setConfigId("CFG-001");
            return 1;
        });
        lenient().when(configMapper.clearOtherDefaults(any())).thenReturn(1);

        RecommendationScoreConfigResponse response = configService.create(request);

        assertThat(response.getConfigId()).isEqualTo("CFG-001");
        assertThat(response.getConfigKey()).isEqualTo("default");
        assertThat(response.getWeights()).containsEntry("styleMatch", BigDecimal.ONE);
        verify(configMapper).insert(any(RecommendationScoreConfig.class));
        verify(configMapper).clearOtherDefaults(eq("CFG-001"));
    }

    @Test
    void create_shouldRejectWeightsNotSumToOne() {
        RecommendationScoreConfigCreateRequest request = new RecommendationScoreConfigCreateRequest();
        request.setConfigKey("bad");
        request.setName("错误配置");
        Map<String, BigDecimal> weights = new HashMap<>();
        weights.put("a", new BigDecimal("0.5"));
        weights.put("b", new BigDecimal("0.4"));
        request.setWeights(weights);

        assertThatThrownBy(() -> configService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("权重项之和必须等于 1");
    }

    @Test
    void update_shouldSetDefaultAndClearOthers() {
        RecommendationScoreConfig existing = new RecommendationScoreConfig();
        existing.setConfigId("CFG-001");
        existing.setConfigKey("k");
        existing.setName("old");
        existing.setWeights("{}");
        existing.setIsDefault(false);

        when(configMapper.selectById("CFG-001")).thenReturn(existing);

        RecommendationScoreConfigUpdateRequest request = new RecommendationScoreConfigUpdateRequest();
        request.setIsDefault(true);

        RecommendationScoreConfigResponse response = configService.update("CFG-001", request);

        assertThat(response.getIsDefault()).isTrue();
        verify(configMapper).clearOtherDefaults(eq("CFG-001"));
    }

    @Test
    void getDefault_shouldReturnActiveDefault() {
        RecommendationScoreConfig config = new RecommendationScoreConfig();
        config.setConfigId("CFG-001");
        config.setConfigKey("default");
        config.setName("默认");
        config.setWeights("{\"styleMatch\":1}");
        config.setIsDefault(true);
        config.setIsActive(true);

        when(configMapper.selectOne(any())).thenReturn(config);

        RecommendationScoreConfigResponse response = configService.getDefault();

        assertThat(response.getConfigId()).isEqualTo("CFG-001");
        assertThat(response.getWeights()).containsEntry("styleMatch", BigDecimal.ONE);
    }

    @Test
    void getDefault_shouldThrowWhenNotFound() {
        when(configMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> configService.getDefault())
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("未找到默认推荐打分配置");
    }
}
