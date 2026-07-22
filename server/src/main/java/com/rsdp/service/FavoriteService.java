package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.entity.FavoriteFolder;
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
import com.rsdp.util.IdGenerator;
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
    private final FavoriteFolderService favoriteFolderService;

    /**
     * 收藏产品（可指定目标文件夹，folderId 优先于 groupName）。
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

        // 文件夹优先：指定 folderId 时校验归属并同步轻量 group_name 文本
        FavoriteFolder folder = null;
        if (StringUtils.hasText(request.getFolderId())) {
            folder = favoriteFolderService.getFolderInUser(request.getFolderId(), userId);
        }

        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId(IdGenerator.favoriteId());
        favorite.setUserId(userId);
        favorite.setRspuId(request.getRspuId());
        favorite.setFolderId(folder != null ? folder.getFolderId() : null);
        favorite.setGroupName(folder != null ? folder.getFolderName()
            : StringUtils.hasText(request.getGroupName()) ? request.getGroupName().trim() : null);
        favorite.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(favorite);

        return toResponse(favorite, rspu, primaryImageUrl(request.getRspuId()));
    }

    /**
     * 移动收藏条目到文件夹（folderId 为空表示移出文件夹，变为未归档）。
     *
     * @param rspuId   产品 ID
     * @param folderId 目标文件夹 ID（可空）
     * @return 更新后的收藏记录
     */
    @Transactional
    public FavoriteResponse move(String rspuId, String folderId) {
        String userId = currentUserIdRequired();
        UserFavorite favorite = favoriteMapper.selectOne(new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .eq("rspu_id", rspuId));
        if (favorite == null) {
            throw new ResourceNotFoundException("未收藏该产品: " + rspuId);
        }

        FavoriteFolder folder = null;
        if (StringUtils.hasText(folderId)) {
            folder = favoriteFolderService.getFolderInUser(folderId, userId);
        }
        favorite.setFolderId(folder != null ? folder.getFolderId() : null);
        favorite.setGroupName(folder != null ? folder.getFolderName() : null);
        favoriteMapper.update(null, new UpdateWrapper<UserFavorite>()
            .eq("favorite_id", favorite.getFavoriteId())
            .set("folder_id", favorite.getFolderId())
            .set("group_name", favorite.getGroupName()));

        RspuMaster rspu = rspuMapper.selectById(rspuId);
        return toResponse(favorite, rspu, primaryImageUrl(rspuId));
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
     * @param folderId 按文件夹筛选（可选）
     * @param unfiled  为 true 时仅查未归档（folder_id 为空）的收藏
     * @return 收藏响应列表，按收藏时间倒序
     */
    public List<FavoriteResponse> list(String folderId, boolean unfiled) {
        String userId = currentUserIdRequired();
        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .orderByDesc("created_at");
        if (StringUtils.hasText(folderId)) {
            wrapper.eq("folder_id", folderId);
        } else if (unfiled) {
            wrapper.isNull("folder_id");
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
        response.setFolderId(favorite.getFolderId());
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
