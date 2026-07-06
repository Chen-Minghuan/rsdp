package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RecommendationScoreConfigCreateRequest;
import com.rsdp.dto.request.RecommendationScoreConfigUpdateRequest;
import com.rsdp.dto.response.RecommendationScoreConfigResponse;
import com.rsdp.entity.RecommendationScoreConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.RecommendationScoreConfigMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 推荐打分配置服务。
 */
@Service
@RequiredArgsConstructor
public class RecommendationScoreConfigService {

    private final RecommendationScoreConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询所有配置。
     *
     * @return 配置响应列表
     */
    public List<RecommendationScoreConfigResponse> list() {
        return configMapper.selectList(
            new QueryWrapper<RecommendationScoreConfig>()
                .orderByDesc("is_default", "created_at")
        ).stream().map(this::toResponse).toList();
    }

    /**
     * 查询默认配置。
     *
     * @return 默认配置响应
     */
    public RecommendationScoreConfigResponse getDefault() {
        RecommendationScoreConfig config = configMapper.selectOne(
            new QueryWrapper<RecommendationScoreConfig>()
                .eq("is_default", true)
                .eq("is_active", true)
                .last("LIMIT 1")
        );
        if (config == null) {
            throw new ResourceNotFoundException("未找到默认推荐打分配置");
        }
        return toResponse(config);
    }

    /**
     * 根据 ID 查询配置。
     *
     * @param configId 配置 ID
     * @return 配置响应
     */
    public RecommendationScoreConfigResponse getById(String configId) {
        RecommendationScoreConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new ResourceNotFoundException("推荐打分配置不存在: " + configId);
        }
        return toResponse(config);
    }

    /**
     * 创建配置。
     *
     * @param request 创建请求
     * @return 创建后的配置响应
     */
    @Transactional
    public RecommendationScoreConfigResponse create(RecommendationScoreConfigCreateRequest request) {
        assertConfigKeyUnique(request.getConfigKey(), null);
        validateWeights(request.getWeights());

        RecommendationScoreConfig config = new RecommendationScoreConfig();
        config.setConfigId(UUID.randomUUID().toString());
        config.setConfigKey(request.getConfigKey().trim());
        config.setName(request.getName().trim());
        config.setDescription(request.getDescription());
        config.setWeights(toJson(request.getWeights()));
        config.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
        config.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        String createdBy = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(createdBy)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        config.setCreatedBy(createdBy);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        configMapper.insert(config);

        if (Boolean.TRUE.equals(config.getIsDefault())) {
            configMapper.clearOtherDefaults(config.getConfigId());
        }
        return toResponse(config);
    }

    /**
     * 更新配置。
     *
     * @param configId 配置 ID
     * @param request  更新请求
     * @return 更新后的配置响应
     */
    @Transactional
    public RecommendationScoreConfigResponse update(String configId, RecommendationScoreConfigUpdateRequest request) {
        RecommendationScoreConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new ResourceNotFoundException("推荐打分配置不存在: " + configId);
        }

        if (StringUtils.hasText(request.getName())) {
            config.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            config.setDescription(request.getDescription());
        }
        if (request.getWeights() != null) {
            validateWeights(request.getWeights());
            config.setWeights(toJson(request.getWeights()));
        }
        if (request.getIsDefault() != null) {
            config.setIsDefault(request.getIsDefault());
        }
        if (request.getIsActive() != null) {
            config.setIsActive(request.getIsActive());
        }
        config.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(config);

        if (Boolean.TRUE.equals(config.getIsDefault())) {
            configMapper.clearOtherDefaults(config.getConfigId());
        }
        return toResponse(config);
    }

    /**
     * 删除配置。
     *
     * @param configId 配置 ID
     */
    @Transactional
    public void delete(String configId) {
        RecommendationScoreConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new ResourceNotFoundException("推荐打分配置不存在: " + configId);
        }
        configMapper.deleteById(configId);
    }

    private void assertConfigKeyUnique(String configKey, String excludeId) {
        QueryWrapper<RecommendationScoreConfig> wrapper = new QueryWrapper<RecommendationScoreConfig>()
            .eq("config_key", configKey);
        if (excludeId != null) {
            wrapper.ne("config_id", excludeId);
        }
        Long count = configMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException("配置键已存在: " + configKey);
        }
    }

    private void validateWeights(Map<String, BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new BusinessException("权重项不能为空");
        }
        BigDecimal sum = weights.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new BusinessException("权重项之和必须等于 1，当前为 " + sum);
        }
    }

    private RecommendationScoreConfigResponse toResponse(RecommendationScoreConfig config) {
        RecommendationScoreConfigResponse response = new RecommendationScoreConfigResponse();
        response.setConfigId(config.getConfigId());
        response.setConfigKey(config.getConfigKey());
        response.setName(config.getName());
        response.setDescription(config.getDescription());
        response.setWeights(fromJson(config.getWeights()));
        response.setIsDefault(config.getIsDefault());
        response.setIsActive(config.getIsActive());
        response.setCreatedBy(config.getCreatedBy());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, BigDecimal> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 反序列化失败: " + e.getMessage());
        }
    }
}
