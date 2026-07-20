package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.service.FavoriteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 收藏产品。
     *
     * @param request 收藏请求
     * @return 收藏记录
     */
    @PostMapping
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
    public Result<Void> remove(@PathVariable @NotBlank(message = "产品 ID 不能为空") String rspuId) {
        favoriteService.remove(rspuId);
        return Result.ok();
    }

    /**
     * 查询我的收藏列表。
     *
     * @param group 分组筛选（可选）
     * @return 收藏列表
     */
    @GetMapping
    public Result<List<FavoriteResponse>> list(@RequestParam(required = false) String group) {
        return Result.ok(favoriteService.list(group));
    }

    /**
     * 批量检查收藏状态。
     *
     * @param rspuIds 产品 ID 列表
     * @return 已收藏的产品 ID 列表
     */
    @GetMapping("/check")
    public Result<List<String>> check(@RequestParam List<String> rspuIds) {
        return Result.ok(favoriteService.check(rspuIds));
    }
}
