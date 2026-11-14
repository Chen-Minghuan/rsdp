package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.CatalogVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 工厂可见性配置（INTEG-001）：公共库开关、整批放行、黑名单。
 *
 * <p>操作权：平台员工可管理任意工厂；工厂管理员仅可管理本厂（逐接口服务端校验，
 * 不靠前端隐藏）。可见性判定统一收口在 {@link CatalogVisibilityService}。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/factories/{factoryCode}")
@RequiredArgsConstructor
public class FactoryCatalogController {

    private final CatalogVisibilityService catalogVisibilityService;
    private final DataScopeHelper dataScopeHelper;

    /** 读公共库开关。 */
    @GetMapping("/catalog-config")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_READ + "')")
    public Result<Map<String, Object>> getCatalogConfig(@PathVariable @NotBlank String factoryCode) {
        assertFactoryManageable(factoryCode);
        boolean enabled = catalogVisibilityService.getPublicCatalogEnabled(factoryCode);
        return Result.ok(Map.of("factoryCode", factoryCode, "publicCatalogEnabled", enabled));
    }

    /** 写公共库开关。 */
    @PatchMapping("/catalog-config")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_UPDATE + "')")
    public Result<Map<String, Object>> setCatalogConfig(@PathVariable @NotBlank String factoryCode,
                                                        @RequestBody Map<String, Boolean> request) {
        assertFactoryManageable(factoryCode);
        boolean enabled = Boolean.TRUE.equals(request.get("publicCatalogEnabled"));
        catalogVisibilityService.setPublicCatalogEnabled(factoryCode, enabled);
        return Result.ok(Map.of("factoryCode", factoryCode, "publicCatalogEnabled", enabled));
    }

    /** 列出本厂已放行的目标厂。 */
    @GetMapping("/catalog-grants")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_READ + "')")
    public Result<List<String>> listGrants(@PathVariable @NotBlank String factoryCode) {
        assertFactoryManageable(factoryCode);
        return Result.ok(catalogVisibilityService.listGrantedFactoryCodes(factoryCode));
    }

    /** 新增整批放行（本厂 → 目标厂，幂等）。 */
    @PostMapping("/catalog-grants")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_UPDATE + "')")
    public Result<List<String>> addGrant(@PathVariable @NotBlank String factoryCode,
                                         @RequestBody Map<String, String> request) {
        assertFactoryManageable(factoryCode);
        catalogVisibilityService.addGrant(factoryCode, requireTarget(request));
        return Result.ok(catalogVisibilityService.listGrantedFactoryCodes(factoryCode));
    }

    /** 移除整批放行。 */
    @DeleteMapping("/catalog-grants/{targetFactoryCode}")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_UPDATE + "')")
    public Result<List<String>> removeGrant(@PathVariable @NotBlank String factoryCode,
                                            @PathVariable @NotBlank String targetFactoryCode) {
        assertFactoryManageable(factoryCode);
        catalogVisibilityService.removeGrant(factoryCode, targetFactoryCode);
        return Result.ok(catalogVisibilityService.listGrantedFactoryCodes(factoryCode));
    }

    /** 列出本厂黑名单。 */
    @GetMapping("/catalog-blocks")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_READ + "')")
    public Result<List<String>> listBlocks(@PathVariable @NotBlank String factoryCode) {
        assertFactoryManageable(factoryCode);
        return Result.ok(catalogVisibilityService.listBlockedFactoryCodes(factoryCode));
    }

    /** 新增黑名单（本厂屏蔽目标厂全部产品，幂等）。 */
    @PostMapping("/catalog-blocks")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_UPDATE + "')")
    public Result<List<String>> addBlock(@PathVariable @NotBlank String factoryCode,
                                         @RequestBody Map<String, String> request) {
        assertFactoryManageable(factoryCode);
        catalogVisibilityService.addBlock(factoryCode, requireTarget(request));
        return Result.ok(catalogVisibilityService.listBlockedFactoryCodes(factoryCode));
    }

    /** 移除黑名单。 */
    @DeleteMapping("/catalog-blocks/{targetFactoryCode}")
    @PreAuthorize("hasAuthority('" + Permissions.FACTORY_UPDATE + "')")
    public Result<List<String>> removeBlock(@PathVariable @NotBlank String factoryCode,
                                            @PathVariable @NotBlank String targetFactoryCode) {
        assertFactoryManageable(factoryCode);
        catalogVisibilityService.removeBlock(factoryCode, targetFactoryCode);
        return Result.ok(catalogVisibilityService.listBlockedFactoryCodes(factoryCode));
    }

    /** 操作权：平台员工任意厂；工厂管理员仅本厂。 */
    private void assertFactoryManageable(String factoryCode) {
        if (SecurityOperatorContext.isPlatformStaff()) {
            return;
        }
        if (!dataScopeHelper.canAccessFactory(factoryCode)) {
            throw new BusinessException("只能管理本厂的可见性配置: " + factoryCode);
        }
    }

    private String requireTarget(Map<String, String> request) {
        String target = request == null ? null : request.get("targetFactoryCode");
        if (target == null || target.isBlank()) {
            throw new BusinessException("targetFactoryCode 不能为空");
        }
        return target.trim();
    }
}
