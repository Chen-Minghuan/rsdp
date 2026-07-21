package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.DesignerProfileSaveRequest;
import com.rsdp.dto.response.DesignerProfileResponse;
import com.rsdp.entity.DesignerProfile;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignerProfileMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 设计师画像服务。
 */
@Service
@RequiredArgsConstructor
public class DesignerProfileService {

    private final DesignerProfileMapper designerProfileMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前登录用户的设计师画像。
     *
     * @return 设计师画像响应
     */
    public DesignerProfileResponse getMyProfile() {
        SysUser user = currentUser();
        DesignerProfile profile = designerProfileMapper.selectByUserId(user.getUserId());
        if (profile == null) {
            throw new ResourceNotFoundException("设计师画像不存在");
        }
        return toResponse(profile, user.getUsername());
    }

    /**
     * 根据用户 ID 查询设计师画像。
     *
     * @param userId 用户 ID
     * @return 设计师画像响应
     */
    public DesignerProfileResponse getByUserId(String userId) {
        DesignerProfile profile = designerProfileMapper.selectByUserId(userId);
        if (profile == null) {
            throw new ResourceNotFoundException("设计师画像不存在");
        }
        SysUser user = sysUserMapper.selectById(userId);
        return toResponse(profile, user != null ? user.getUsername() : null);
    }

    private static final int MAX_LIST_SIZE = 1000;

    /**
     * 查询公开的的设计师画像列表。
     *
     * @return 设计师画像响应列表
     */
    public List<DesignerProfileResponse> listPublicProfiles() {
        List<DesignerProfile> profiles = designerProfileMapper.selectList(
            new QueryWrapper<DesignerProfile>()
                .eq("is_public", true)
                .eq("status", "active")
                .orderByDesc("updated_at")
                .last("LIMIT " + MAX_LIST_SIZE)
        );
        List<String> userIds = profiles.stream()
            .map(DesignerProfile::getUserId)
            .distinct()
            .toList();
        Map<String, SysUser> userMap = userIds.isEmpty()
            ? Map.of()
            : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getUserId, u -> u, (a, b) -> a));
        return profiles.stream()
            .map(p -> toResponse(p, Optional.ofNullable(userMap.get(p.getUserId())).map(SysUser::getUsername).orElse(null)))
            .toList();
    }

    /**
     * 保存当前登录用户的设计师画像（不存在则创建，存在则更新）。
     *
     * @param request 保存请求
     * @return 保存后的设计师画像响应
     */
    @Transactional
    public DesignerProfileResponse saveMyProfile(DesignerProfileSaveRequest request) {
        SysUser user = currentUser();
        DesignerProfile profile = designerProfileMapper.selectByUserId(user.getUserId());
        if (profile == null) {
            profile = new DesignerProfile();
            profile.setProfileId(UUID.randomUUID().toString());
            profile.setUserId(user.getUserId());
            profile.setStatus("active");
            profile.setCreatedAt(LocalDateTime.now());
        }
        applyRequest(profile, request);
        profile.setUpdatedAt(LocalDateTime.now());
        if (profile.getProfileId() != null && designerProfileMapper.selectById(profile.getProfileId()) == null) {
            designerProfileMapper.insert(profile);
        } else {
            designerProfileMapper.updateById(profile);
        }
        return toResponse(profile, user.getUsername());
    }

    private void applyRequest(DesignerProfile profile, DesignerProfileSaveRequest request) {
        if (StringUtils.hasText(request.getRealName())) {
            profile.setRealName(request.getRealName());
        }
        if (request.getAvatarUrl() != null) {
            profile.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getSpecialties() != null) {
            profile.setSpecialties(toJson(request.getSpecialties()));
        }
        if (request.getPreferredStyles() != null) {
            profile.setPreferredStyles(toJson(request.getPreferredStyles()));
        }
        if (request.getPreferredCategories() != null) {
            profile.setPreferredCategories(toJson(request.getPreferredCategories()));
        }
        if (StringUtils.hasText(request.getPriceSensitivity())) {
            profile.setPriceSensitivity(request.getPriceSensitivity());
        }
        if (request.getLocation() != null) {
            profile.setLocation(request.getLocation());
        }
        if (request.getCompanyName() != null) {
            profile.setCompanyName(request.getCompanyName());
        }
        if (request.getContactPhone() != null) {
            profile.setContactPhone(request.getContactPhone());
        }
        if (request.getBio() != null) {
            profile.setBio(request.getBio());
        }
        if (request.getDefaultBudgetMin() != null) {
            profile.setDefaultBudgetMin(request.getDefaultBudgetMin());
        }
        if (request.getDefaultBudgetMax() != null) {
            profile.setDefaultBudgetMax(request.getDefaultBudgetMax());
        }
        if (request.getIsPublic() != null) {
            profile.setIsPublic(request.getIsPublic());
        }
    }

    private SysUser currentUser() {
        String username = SecurityOperatorContext.currentUsername();
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("当前用户不存在");
        }
        return user;
    }

    private DesignerProfileResponse toResponse(DesignerProfile profile, String username) {
        DesignerProfileResponse response = new DesignerProfileResponse();
        response.setProfileId(profile.getProfileId());
        response.setUserId(profile.getUserId());
        response.setUsername(username);
        response.setRealName(profile.getRealName());
        response.setAvatarUrl(profile.getAvatarUrl());
        response.setSpecialties(fromJson(profile.getSpecialties()));
        response.setPreferredStyles(fromJson(profile.getPreferredStyles()));
        response.setPreferredCategories(fromJson(profile.getPreferredCategories()));
        response.setPriceSensitivity(profile.getPriceSensitivity());
        response.setLocation(profile.getLocation());
        response.setCompanyName(profile.getCompanyName());
        response.setContactPhone(profile.getContactPhone());
        response.setBio(profile.getBio());
        response.setDefaultBudgetMin(profile.getDefaultBudgetMin());
        response.setDefaultBudgetMax(profile.getDefaultBudgetMax());
        response.setIsPublic(profile.getIsPublic());
        response.setStatus(profile.getStatus());
        response.setCreatedAt(profile.getCreatedAt());
        response.setUpdatedAt(profile.getUpdatedAt());
        return response;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 反序列化失败: " + e.getMessage());
        }
    }
}
