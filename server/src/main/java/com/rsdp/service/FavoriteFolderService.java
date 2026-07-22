package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.FavoriteFolderRequest;
import com.rsdp.dto.response.FavoriteFolderResponse;
import com.rsdp.entity.FavoriteFolder;
import com.rsdp.entity.UserFavorite;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FavoriteFolderMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收藏夹文件夹服务：文件夹 CRUD，数据按当前用户隔离。
 */
@Service
@RequiredArgsConstructor
public class FavoriteFolderService {

    private final FavoriteFolderMapper folderMapper;
    private final UserFavoriteMapper favoriteMapper;
    private final AuditLogService auditLogService;

    /**
     * 查询当前用户的文件夹列表（含收藏数）。
     *
     * @return 文件夹列表
     */
    public List<FavoriteFolderResponse> listMyFolders() {
        String userId = currentUserIdRequired();
        List<FavoriteFolder> folders = folderMapper.selectList(new QueryWrapper<FavoriteFolder>()
            .eq("user_id", userId)
            .orderByAsc("sort_order")
            .orderByAsc("created_at"));
        if (folders.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> countMap = batchFavoriteCounts(
            userId, folders.stream().map(FavoriteFolder::getFolderId).toList());
        return folders.stream()
            .map(f -> toResponse(f, countMap.getOrDefault(f.getFolderId(), 0)))
            .toList();
    }

    /**
     * 创建文件夹。同用户下文件夹名称不可重复。
     *
     * @param request 创建请求
     * @return 创建后的文件夹
     */
    @Transactional
    public FavoriteFolderResponse create(FavoriteFolderRequest request) {
        String userId = currentUserIdRequired();
        String name = request.getFolderName().trim();
        assertFolderNameUnique(userId, name, null);

        FavoriteFolder folder = new FavoriteFolder();
        folder.setFolderId(IdGenerator.generate("FAVD"));
        folder.setUserId(userId);
        folder.setFolderName(name);
        folder.setSortOrder(0);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        folderMapper.insert(folder);
        auditLogService.logCreate("favorite_folder", folder.getFolderId(), folder, currentUsername());
        return toResponse(folder, 0);
    }

    /**
     * 重命名文件夹（同步收藏条目的轻量 group_name 文本）。
     *
     * @param folderId 文件夹 ID
     * @param request  更新请求
     * @return 更新后的文件夹
     */
    @Transactional
    public FavoriteFolderResponse rename(String folderId, FavoriteFolderRequest request) {
        String userId = currentUserIdRequired();
        FavoriteFolder folder = getFolderInUser(folderId, userId);
        String newName = request.getFolderName().trim();
        if (!newName.equals(folder.getFolderName())) {
            assertFolderNameUnique(userId, newName, folderId);
            FavoriteFolder oldSnapshot = snapshot(folder);
            folder.setFolderName(newName);
            folder.setUpdatedAt(LocalDateTime.now());
            folderMapper.updateById(folder);
            // 文件夹改名同步收藏条目轻量文本字段
            favoriteMapper.update(null, new UpdateWrapper<UserFavorite>()
                .eq("folder_id", folderId)
                .set("group_name", newName));
            auditLogService.logUpdate("favorite_folder", folderId, oldSnapshot, folder, currentUsername());
        }
        Long count = favoriteMapper.selectCount(new QueryWrapper<UserFavorite>()
            .eq("user_id", userId).eq("folder_id", folderId));
        return toResponse(folder, count != null ? count.intValue() : 0);
    }

    /**
     * 软删除文件夹：文件夹内收藏条目变为未归档（folder_id/group_name 置空）。
     *
     * @param folderId 文件夹 ID
     */
    @Transactional
    public void delete(String folderId) {
        String userId = currentUserIdRequired();
        FavoriteFolder folder = getFolderInUser(folderId, userId);
        FavoriteFolder oldSnapshot = snapshot(folder);
        folderMapper.deleteById(folderId);
        favoriteMapper.update(null, new UpdateWrapper<UserFavorite>()
            .eq("folder_id", folderId)
            .set("folder_id", null)
            .set("group_name", null));
        auditLogService.logDelete("favorite_folder", folderId, oldSnapshot, currentUsername());
    }

    /**
     * 校验文件夹存在且归属当前用户，返回文件夹实体。
     *
     * @param folderId 文件夹 ID
     * @param userId   用户 ID
     * @return 文件夹实体
     */
    public FavoriteFolder getFolderInUser(String folderId, String userId) {
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            throw new ResourceNotFoundException("收藏夹文件夹不存在: " + folderId);
        }
        return folder;
    }

    private void assertFolderNameUnique(String userId, String folderName, String excludeFolderId) {
        QueryWrapper<FavoriteFolder> wrapper = new QueryWrapper<FavoriteFolder>()
            .eq("user_id", userId)
            .eq("folder_name", folderName);
        if (StringUtils.hasText(excludeFolderId)) {
            wrapper.ne("folder_id", excludeFolderId);
        }
        if (folderMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("同名文件夹已存在: " + folderName);
        }
    }

    private Map<String, Integer> batchFavoriteCounts(String userId, List<String> folderIds) {
        List<Map<String, Object>> rows = favoriteMapper.selectMaps(new QueryWrapper<UserFavorite>()
            .select("folder_id", "COUNT(*) AS cnt")
            .eq("user_id", userId)
            .in("folder_id", folderIds)
            .groupBy("folder_id"));
        return rows.stream().collect(Collectors.toMap(
            row -> (String) row.get("folder_id"),
            row -> ((Number) row.get("cnt")).intValue(),
            (a, b) -> a));
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private FavoriteFolder snapshot(FavoriteFolder source) {
        FavoriteFolder copy = new FavoriteFolder();
        copy.setFolderId(source.getFolderId());
        copy.setUserId(source.getUserId());
        copy.setFolderName(source.getFolderName());
        copy.setSortOrder(source.getSortOrder());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private FavoriteFolderResponse toResponse(FavoriteFolder folder, int favoriteCount) {
        FavoriteFolderResponse response = new FavoriteFolderResponse();
        response.setFolderId(folder.getFolderId());
        response.setFolderName(folder.getFolderName());
        response.setSortOrder(folder.getSortOrder());
        response.setFavoriteCount(favoriteCount);
        response.setCreatedAt(folder.getCreatedAt());
        return response;
    }
}
