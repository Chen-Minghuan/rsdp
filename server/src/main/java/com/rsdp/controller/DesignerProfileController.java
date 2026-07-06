package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.DesignerProfileSaveRequest;
import com.rsdp.dto.response.DesignerProfileResponse;
import com.rsdp.service.DesignerProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设计师画像接口。
 */
@RestController
@RequestMapping("/api/v1/designer-profiles")
@RequiredArgsConstructor
@Validated
public class DesignerProfileController {

    private final DesignerProfileService designerProfileService;

    /**
     * 查询当前登录用户的设计师画像。
     *
     * @return 设计师画像
     */
    @GetMapping("/me")
    public Result<DesignerProfileResponse> getMyProfile() {
        return Result.ok(designerProfileService.getMyProfile());
    }

    /**
     * 查询公开的的设计师画像列表。
     *
     * @return 设计师画像列表
     */
    @GetMapping
    public Result<List<DesignerProfileResponse>> listPublicProfiles() {
        return Result.ok(designerProfileService.listPublicProfiles());
    }

    /**
     * 根据用户 ID 查询设计师画像。
     *
     * @param userId 用户 ID
     * @return 设计师画像
     */
    @GetMapping("/{userId}")
    public Result<DesignerProfileResponse> getByUserId(
        @PathVariable @NotBlank(message = "用户 ID 不能为空") String userId) {
        return Result.ok(designerProfileService.getByUserId(userId));
    }

    /**
     * 保存当前登录用户的设计师画像。
     *
     * @param request 保存请求
     * @return 保存后的设计师画像
     */
    @PostMapping("/me")
    public Result<DesignerProfileResponse> saveMyProfile(
        @RequestBody @Valid DesignerProfileSaveRequest request) {
        return Result.ok(designerProfileService.saveMyProfile(request));
    }

    /**
     * 更新当前登录用户的设计师画像。
     *
     * @param request 保存请求
     * @return 保存后的设计师画像
     */
    @PutMapping("/me")
    public Result<DesignerProfileResponse> updateMyProfile(
        @RequestBody @Valid DesignerProfileSaveRequest request) {
        return Result.ok(designerProfileService.saveMyProfile(request));
    }
}
