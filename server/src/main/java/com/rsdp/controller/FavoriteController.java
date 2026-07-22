package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.FavoriteFolderRequest;
import com.rsdp.dto.request.FavoriteMoveRequest;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteFolderResponse;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.service.FavoriteFolderService;
import com.rsdp.service.FavoriteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.rsdp.security.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏夹接口。所有接口要求登录，数据按当前用户隔离。
 */
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
@Validated
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final FavoriteFolderService favoriteFolderService;

    /**
     * 收藏产品（可指定目标文件夹）。
     *
     * @param request 收藏请求
     * @return 收藏记录
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<FavoriteResponse> add(@RequestBody @Valid FavoriteRequest request) {
        return Result.ok(favoriteService.add(request));
    }

    /**
     * 取消收藏。
     *
     * @param rspuId 产品 ID
     * @return 空结果
     */
    @DeleteMapping("/{rspuId}")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<Void> remove(@PathVariable @NotBlank(message = "产品 ID 不能为空") String rspuId) {
        favoriteService.remove(rspuId);
        return Result.ok();
    }

    /**
     * 移动收藏条目到文件夹（folderId 为空表示未归档）。
     *
     * @param rspuId  产品 ID
     * @param request 移动请求
     * @return 更新后的收藏记录
     */
    @PutMapping("/{rspuId}/folder")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<FavoriteResponse> move(
        @PathVariable @NotBlank(message = "产品 ID 不能为空") String rspuId,
        @RequestBody @Valid FavoriteMoveRequest request) {
        return Result.ok(favoriteService.move(rspuId, request.getFolderId()));
    }

    /**
     * 查询我的收藏列表。
     *
     * @param folderId 按文件夹筛选（可选）
     * @param unfiled  为 true 时仅查未归档收藏
     * @return 收藏列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_READ + "')")
    public Result<List<FavoriteResponse>> list(
        @RequestParam(required = false) String folderId,
        @RequestParam(defaultValue = "false") boolean unfiled) {
        return Result.ok(favoriteService.list(folderId, unfiled));
    }

    /**
     * 批量检查收藏状态。
     *
     * @param rspuIds 产品 ID 列表
     * @return 已收藏的产品 ID 列表
     */
    @GetMapping("/check")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_READ + "')")
    public Result<List<String>> check(@RequestParam List<String> rspuIds) {
        return Result.ok(favoriteService.check(rspuIds));
    }

    /**
     * 查询我的收藏夹文件夹列表（含收藏数）。
     *
     * @return 文件夹列表
     */
    @GetMapping("/folders")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_READ + "')")
    public Result<List<FavoriteFolderResponse>> listFolders() {
        return Result.ok(favoriteFolderService.listMyFolders());
    }

    /**
     * 创建收藏夹文件夹。
     *
     * @param request 创建请求
     * @return 创建后的文件夹
     */
    @PostMapping("/folders")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<FavoriteFolderResponse> createFolder(@RequestBody @Valid FavoriteFolderRequest request) {
        return Result.ok(favoriteFolderService.create(request));
    }

    /**
     * 重命名收藏夹文件夹。
     *
     * @param folderId 文件夹 ID
     * @param request  更新请求
     * @return 更新后的文件夹
     */
    @PutMapping("/folders/{folderId}")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<FavoriteFolderResponse> renameFolder(
        @PathVariable @NotBlank(message = "文件夹 ID 不能为空") String folderId,
        @RequestBody @Valid FavoriteFolderRequest request) {
        return Result.ok(favoriteFolderService.rename(folderId, request));
    }

    /**
     * 删除收藏夹文件夹（夹内收藏变为未归档）。
     *
     * @param folderId 文件夹 ID
     * @return 空结果
     */
    @DeleteMapping("/folders/{folderId}")
    @PreAuthorize("hasAuthority('" + Permissions.FAVORITE_WRITE + "')")
    public Result<Void> deleteFolder(
        @PathVariable @NotBlank(message = "文件夹 ID 不能为空") String folderId) {
        favoriteFolderService.delete(folderId);
        return Result.ok();
    }
}
