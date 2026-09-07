package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.SchemeShareResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 方案分享服务（免登录只读视图，V42）。
 *
 * <p>两种公开入口：方案独立分享（校验 scheme.share_enabled + share_expire_at）与
 * 项目分享页内的方案（校验项目分享有效且方案属于该项目）。
 * 返回内容严格白名单组装：仅产品名/主图/数量/空间名/排序/标准售价，
 * 不含工厂/成本/RSKU 等敏感信息（标准售价为对客价格，可公开）。</p>
 */
@Service
@RequiredArgsConstructor
public class SchemeShareService {

    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final CategoryDictMapper categoryDictMapper;
    private final ProjectShareService projectShareService;
    private final SchemeSalePriceService schemeSalePriceService;

    /**
     * 获取方案独立分享公开视图（校验分享开关 + 过期时间，过期时间为空=永久有效）。
     *
     * @param schemeId 方案 ID
     * @return 分享视图（只读）
     */
    public SchemeShareResponse getSharedScheme(String schemeId) {
        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || !Boolean.TRUE.equals(scheme.getShareEnabled())) {
            throw new ResourceNotFoundException("未开启分享或该页面不存在");
        }
        if (scheme.getShareExpireAt() != null && scheme.getShareExpireAt().isBefore(LocalDateTime.now())) {
            throw new ResourceNotFoundException("分享已过期或该页面不存在");
        }
        return buildShareView(scheme);
    }

    /**
     * 获取项目分享页内的方案公开视图（校验项目分享有效且方案属于该项目）。
     *
     * @param projectId 项目 ID
     * @param schemeId  方案 ID
     * @return 分享视图（只读）
     */
    public SchemeShareResponse getSharedProjectScheme(String projectId, String schemeId) {
        // 复用项目公开校验逻辑（开关 + 过期双重校验）
        projectShareService.getValidSharedProject(projectId);

        Scheme scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || !projectId.equals(scheme.getProjectId())) {
            throw new ResourceNotFoundException("方案不存在或不属于该项目");
        }
        return buildShareView(scheme);
    }

    /**
     * 组装方案分享视图（严格白名单：产品名/主图/数量/空间名/排序/标准售价）。
     *
     * @param scheme 方案实体
     * @return 分享视图
     */
    private SchemeShareResponse buildShareView(Scheme scheme) {
        List<SchemeItem> items = schemeItemMapper.selectList(new QueryWrapper<SchemeItem>()
            .eq("scheme_id", scheme.getSchemeId())
            .orderByAsc("sort_order"));

        List<String> rspuIds = items.stream().map(SchemeItem::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = batchRspuMap(rspuIds);
        Map<String, RskuSupply> rskuMap = batchRskuMap(
            items.stream().map(SchemeItem::getRskuId).distinct().toList());
        Map<String, String> imageMap = batchPrimaryImageIds(rspuIds);
        // 空间标签：scheme_item.space_tag 覆盖优先，空则回退产品 rspu_scene 首场景码
        Map<String, String> derivedCodes = batchDerivedSpaceCodes(rspuIds);
        List<String> effectiveCodes = items.stream()
            .map(item -> StringUtils.hasText(item.getSpaceTag())
                ? item.getSpaceTag()
                : derivedCodes.get(item.getRspuId()))
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        Map<String, String> sceneNames = batchSceneNames(effectiveCodes);

        SchemeShareResponse response = new SchemeShareResponse();
        response.setSchemeId(scheme.getSchemeId());
        response.setSchemeName(scheme.getSchemeName());
        response.setShareExpireAt(scheme.getShareExpireAt());
        response.setItems(items.stream().map(item -> {
            SchemeShareResponse.ShareItem shareItem = new SchemeShareResponse.ShareItem();
            RspuMaster rspu = rspuMap.get(item.getRspuId());
            shareItem.setRspuId(item.getRspuId());
            shareItem.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
            shareItem.setImageId(imageMap.get(item.getRspuId()));
            shareItem.setQuantity(item.getQuantity());
            // 标准售价（对客价格，可公开；未定价为 null）
            shareItem.setSalePrice(schemeSalePriceService.salePriceOf(rspu, rskuMap.get(item.getRskuId())));
            String code = StringUtils.hasText(item.getSpaceTag())
                ? item.getSpaceTag()
                : derivedCodes.get(item.getRspuId());
            // 码已删时回退码原文
            shareItem.setSpaceTagName(StringUtils.hasText(code) ? sceneNames.getOrDefault(code, code) : null);
            shareItem.setSortOrder(item.getSortOrder());
            return shareItem;
        }).toList());
        return response;
    }

    private Map<String, RspuMaster> batchRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds))
            .stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
    }

    private Map<String, RskuSupply> batchRskuMap(List<String> rskuIds) {
        if (rskuIds.isEmpty()) {
            return Map.of();
        }
        return rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>().in("rsku_id", rskuIds))
            .stream().collect(Collectors.toMap(RskuSupply::getRskuId, r -> r, (a, b) -> a));
    }

    private Map<String, String> batchPrimaryImageIds(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .in("rspu_id", rspuIds)
            .eq("is_primary", true));
        return images.stream().collect(Collectors.toMap(
            ImageAssets::getRspuId, ImageAssets::getImageId, (a, b) -> a));
    }

    /**
     * 批量获取 RSPU 的推导空间码：取每个 RSPU 的首个场景字典码。
     *
     * @param rspuIds RSPU ID 列表
     * @return rspuId → 首个场景字典码
     */
    private Map<String, String> batchDerivedSpaceCodes(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RspuScene> scenes = rspuSceneMapper.selectList(new QueryWrapper<RspuScene>()
            .in("rspu_id", rspuIds)
            .orderByAsc("scene_code"));
        Map<String, String> result = new HashMap<>();
        for (RspuScene scene : scenes) {
            result.putIfAbsent(scene.getRspuId(), scene.getSceneCode());
        }
        return result;
    }

    private Map<String, String> batchSceneNames(List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return categoryDictMapper.selectList(new QueryWrapper<CategoryDict>()
                .eq("dict_type", "scene")
                .in("dict_code", codes))
            .stream().collect(Collectors.toMap(
                CategoryDict::getDictCode, CategoryDict::getDictName, (a, b) -> a));
    }
}
