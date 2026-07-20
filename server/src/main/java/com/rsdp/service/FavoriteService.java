package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.UserFavorite;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 收藏夹服务：用户级产品收藏，数据按当前用户隔离。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteMapper favoriteMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;

    /**
     * 收藏产品。
     *
     * @param request 收藏请求
     * @return 收藏记录响应
     */
    @Transactional
    public FavoriteResponse add(FavoriteRequest request) {
        String userId = currentUserIdRequired();

        RspuMaster rspu = rspuMapper.selectById(request.getRspuId());
        if (rspu == null) {
            throw new ResourceNotFoundException("产品不存在: " + request.getRspuId());
        }

        Long count = favoriteMapper.selectCount(new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .eq("rspu_id", request.getRspuId()));
        if (count != null && count > 0) {
            throw new BusinessException("已收藏该产品");
        }

        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId("FAV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        favorite.setUserId(userId);
        favorite.setRspuId(request.getRspuId());
        favorite.setGroupName(StringUtils.hasText(request.getGroupName()) ? request.getGroupName().trim() : null);
        favorite.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(favorite);

        return toResponse(favorite, rspu, primaryImageUrl(request.getRspuId()));
    }

    /**
     * 取消收藏。
     *
     * @param rspuId 产品 ID
     */
    @Transactional
    public void remove(String rspuId) {
        String userId = currentUserIdRequired();
        int deleted = favoriteMapper.delete(new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .eq("rspu_id", rspuId));
        if (deleted == 0) {
            throw new ResourceNotFoundException("未收藏该产品: " + rspuId);
        }
    }

    /**
     * 查询当前用户的收藏列表。
     *
     * @param group 分组筛选（可选）
     * @return 收藏响应列表，按收藏时间倒序
     */
    public List<FavoriteResponse> list(String group) {
        String userId = currentUserIdRequired();
        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .orderByDesc("created_at");
        if (StringUtils.hasText(group)) {
            wrapper.eq("group_name", group);
        }
        List<UserFavorite> favorites = favoriteMapper.selectList(wrapper);
        if (favorites.isEmpty()) {
            return List.of();
        }

        List<String> rspuIds = favorites.stream().map(UserFavorite::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(rspuIds);

        return favorites.stream()
            .map(fav -> toResponse(fav, rspuMap.get(fav.getRspuId()), imageUrlMap.get(fav.getRspuId())))
            .toList();
    }

    /**
     * 批量检查收藏状态。
     *
     * @param rspuIds 产品 ID 列表
     * @return 已收藏的产品 ID 列表
     */
    public List<String> check(List<String> rspuIds) {
        String userId = currentUserIdRequired();
        if (rspuIds == null || rspuIds.isEmpty()) {
            return List.of();
        }
        return favoriteMapper.selectList(new QueryWrapper<UserFavorite>()
                .eq("user_id", userId)
                .in("rspu_id", rspuIds))
            .stream()
            .map(UserFavorite::getRspuId)
            .toList();
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    private FavoriteResponse toResponse(UserFavorite favorite, RspuMaster rspu, String primaryImageUrl) {
        FavoriteResponse response = new FavoriteResponse();
        response.setFavoriteId(favorite.getFavoriteId());
        response.setRspuId(favorite.getRspuId());
        response.setGroupName(favorite.getGroupName());
        response.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
        response.setPrimaryImageUrl(primaryImageUrl);
        response.setCreatedAt(favorite.getCreatedAt());
        return response;
    }

    private String primaryImageUrl(String rspuId) {
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .eq("rspu_id", rspuId)
            .eq("is_primary", true)
            .last("LIMIT 1"));
        return images.isEmpty() ? null : "/api/v1/images/" + images.get(0).getImageId();
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>()
                .in("rspu_id", rspuIds)
                .eq("is_primary", true)
        );
        return images.stream()
            .collect(Collectors.toMap(
                ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(),
                (a, b) -> a
            ));
    }
}
