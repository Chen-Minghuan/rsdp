package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.ProjectShareResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目画布分享服务（免登录只读视图）。
 *
 * <p>校验分享开关 + 过期时间；返回内容不含工厂/价格/RSKU 等敏感信息。</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectShareService {

    private final ProjectMapper projectMapper;
    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final CategoryDictMapper categoryDictMapper;

    /**
     * 获取项目分享公开视图。
     *
     * @param projectId 项目 ID
     * @return 分享视图（只读）
     */
    public ProjectShareResponse getSharedProject(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !Boolean.TRUE.equals(project.getShareEnabled())) {
            throw new ResourceNotFoundException("分享不存在或已关闭");
        }
        if (project.getShareExpireAt() != null && project.getShareExpireAt().isBefore(LocalDateTime.now())) {
            throw new ResourceNotFoundException("分享已过期");
        }

        List<Scheme> schemes = schemeMapper.selectList(new QueryWrapper<Scheme>()
            .eq("project_id", projectId)
            .eq("status", "active")
            .orderByAsc("created_at"));

        ProjectShareResponse response = new ProjectShareResponse();
        response.setProjectId(project.getProjectId());
        response.setProjectName(project.getProjectName());
        response.setCompanyName(project.getCompanyName());
        response.setRemark(project.getRemark());
        response.setShareExpireAt(project.getShareExpireAt());
        response.setSchemes(buildShareSchemes(schemes));
        return response;
    }

    private List<ProjectShareResponse.ShareScheme> buildShareSchemes(List<Scheme> schemes) {
        if (schemes.isEmpty()) {
            return List.of();
        }
        List<String> schemeIds = schemes.stream().map(Scheme::getSchemeId).toList();
        List<SchemeItem> allItems = schemeItemMapper.selectList(new QueryWrapper<SchemeItem>()
            .in("scheme_id", schemeIds)
            .orderByAsc("sort_order"));
        Map<String, List<SchemeItem>> itemsByScheme = allItems.stream()
            .collect(Collectors.groupingBy(SchemeItem::getSchemeId, Collectors.toList()));

        // 批量补产品名 / 主图 / 空间标签
        List<String> rspuIds = allItems.stream().map(SchemeItem::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = batchRspuMap(rspuIds);
        Map<String, String> imageMap = batchPrimaryImageUrls(rspuIds);
        Map<String, String> spaceTagMap = batchSpaceTags(rspuIds);

        List<ProjectShareResponse.ShareScheme> result = new ArrayList<>();
        for (Scheme scheme : schemes) {
            List<SchemeItem> items = itemsByScheme.getOrDefault(scheme.getSchemeId(), List.of());
            ProjectShareResponse.ShareScheme shareScheme = new ProjectShareResponse.ShareScheme();
            shareScheme.setSchemeId(scheme.getSchemeId());
            shareScheme.setSchemeName(scheme.getSchemeName());
            shareScheme.setItemCount(items.size());
            shareScheme.setItems(items.stream().map(item -> {
                ProjectShareResponse.ShareItem shareItem = new ProjectShareResponse.ShareItem();
                RspuMaster rspu = rspuMap.get(item.getRspuId());
                shareItem.setRspuId(item.getRspuId());
                shareItem.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
                shareItem.setImageId(imageMap.get(item.getRspuId()));
                shareItem.setQuantity(item.getQuantity());
                shareItem.setSpaceTag(spaceTagMap.get(item.getRspuId()));
                return shareItem;
            }).toList());
            result.add(shareScheme);
        }
        return result;
    }

    private Map<String, RspuMaster> batchRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds))
            .stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .in("rspu_id", rspuIds)
            .eq("is_primary", true));
        return images.stream().collect(Collectors.toMap(
            ImageAssets::getRspuId, ImageAssets::getImageId, (a, b) -> a));
    }

    private Map<String, String> batchSpaceTags(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RspuScene> scenes = rspuSceneMapper.selectList(new QueryWrapper<RspuScene>()
            .in("rspu_id", rspuIds)
            .orderByAsc("scene_code"));
        if (scenes.isEmpty()) {
            return Map.of();
        }
        List<String> codes = scenes.stream().map(RspuScene::getSceneCode).distinct().toList();
        Map<String, String> nameMap = categoryDictMapper.selectList(new QueryWrapper<CategoryDict>()
                .eq("dict_type", "scene")
                .in("dict_code", codes))
            .stream().collect(Collectors.toMap(CategoryDict::getDictCode, CategoryDict::getDictName, (a, b) -> a));
        Map<String, String> result = new HashMap<>();
        for (RspuScene scene : scenes) {
            result.putIfAbsent(scene.getRspuId(),
                nameMap.getOrDefault(scene.getSceneCode(), scene.getSceneCode()));
        }
        return result;
    }
}
