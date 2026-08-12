package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PublicCategoryResponse;
import com.rsdp.dto.response.PublicProductItemResponse;
import com.rsdp.dto.response.PublicSceneResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户端官网公开目录服务（免登录）：商品列表 / 空间入口 / 类目树。
 *
 * <p>红线：只查 RSPU 展示字段与零售参考价（retail_price 不加密），
 * 绝不联查 rsku_supply 工厂报价（AES 加密列不出库到用户端）。</p>
 */
@Service
@RequiredArgsConstructor
public class PublicCatalogService {

    /** 公开列表单页上限。 */
    private static final int MAX_PAGE_SIZE = 50;

    private static final String IMAGE_TYPE_SCENE = "scene";
    private static final String STATUS_ACTIVE = "active";
    private static final String DICT_TYPE_SCENE = "scene";
    private static final String DICT_TYPE_CATEGORY = "category";

    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final DictService dictService;
    private final ObjectMapper objectMapper;

    /**
     * 公开商品分页列表（仅在售 active 产品）。
     *
     * @param page      页码（从 1 开始）
     * @param size      每页条数（上限 50）
     * @param category  品类码筛选（category_code 精确）
     * @param seatCount 座位数筛选（匹配变体 dimensions 的 seat_count/seatCount 键）
     * @param color     颜色筛选（主色中文名模糊）
     * @param material  材质筛选（material_tags JSONB 包含）
     * @param priceMin  零售参考价下限
     * @param priceMax  零售参考价上限
     * @param sort      排序：newest（默认）/ price_asc / price_desc
     * @return 分页商品列表
     */
    public PageResult<PublicProductItemResponse> listProducts(int page, int size, String category,
            String seatCount, String color, String material,
            BigDecimal priceMin, BigDecimal priceMax, String sort) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_ACTIVE);
        if (StringUtils.hasText(category)) {
            wrapper.eq("category_code", category.trim());
        }
        if (StringUtils.hasText(seatCount)) {
            // 座位数存于变体 dimensions JSONB（键名兼容 seat_count / seatCount 两种写法）
            wrapper.apply("EXISTS (SELECT 1 FROM rspu_variant v WHERE v.rspu_id = rspu_master.rspu_id"
                + " AND v.deleted_at IS NULL"
                + " AND COALESCE(v.dimensions->>'seat_count', v.dimensions->>'seatCount') = {0})",
                seatCount.trim());
        }
        if (StringUtils.hasText(color)) {
            wrapper.like("color_primary_name", color.trim());
        }
        if (StringUtils.hasText(material)) {
            wrapper.apply("material_tags @> {0}::jsonb", toJsonArray(material.trim()));
        }
        if (priceMin != null) {
            wrapper.ge("retail_price", priceMin);
        }
        if (priceMax != null) {
            wrapper.le("retail_price", priceMax);
        }
        switch (sort == null ? "" : sort) {
            case "price_asc" -> wrapper.orderByAsc("retail_price");
            case "price_desc" -> wrapper.orderByDesc("retail_price");
            default -> wrapper.orderByDesc("created_at");
        }

        Page<RspuMaster> pageResult = rspuMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<RspuMaster> records = pageResult.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(pageResult.getTotal(), safePage, safeSize, List.of());
        }

        List<String> rspuIds = records.stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> primaryImageMap = batchImageUrls(rspuIds, false);
        Map<String, String> sceneImageMap = batchImageUrls(rspuIds, true);
        Map<String, Long> variantCountMap = batchVariantCounts(rspuIds);

        List<PublicProductItemResponse> rows = records.stream().map(rspu -> {
            PublicProductItemResponse item = new PublicProductItemResponse();
            item.setRspuId(rspu.getRspuId());
            item.setRspuCode(rspu.getRspuCode());
            item.setProductName(rspu.getProductName());
            item.setCategoryCode(rspu.getCategoryCode());
            item.setCategoryPath(rspu.getCategoryPath());
            item.setPositioningLabel(rspu.getPositioningLabel());
            item.setColorPrimaryName(rspu.getColorPrimaryName());
            item.setMaterialTags(parseStringList(rspu.getMaterialTags()));
            item.setRetailPrice(rspu.getRetailPrice());
            item.setPrimaryImageUrl(primaryImageMap.get(rspu.getRspuId()));
            item.setSceneImageUrl(sceneImageMap.get(rspu.getRspuId()));
            item.setVariantCount(variantCountMap.getOrDefault(rspu.getRspuId(), 0L).intValue());
            item.setCreatedAt(rspu.getCreatedAt());
            return item;
        }).toList();
        return PageResult.of(pageResult.getTotal(), safePage, safeSize, rows);
    }

    /**
     * 空间入口列表：场景字典（启用）+ 每个空间一张代表图。
     *
     * <p>封面图手配优先（category_dict.image_id，V35），
     * 无手配时回退"该场景下最新在售产品的主图"。</p>
     *
     * @return 空间入口列表
     */
    public List<PublicSceneResponse> listScenes() {
        List<CategoryDict> scenes = dictService.listByType(DICT_TYPE_SCENE);
        return scenes.stream().map(scene -> {
            PublicSceneResponse item = new PublicSceneResponse();
            item.setSceneCode(scene.getDictCode());
            item.setSceneName(scene.getDictName());
            item.setSceneNameEn(scene.getDictNameEn());
            item.setImageUrl(StringUtils.hasText(scene.getImageId())
                ? imageUrl(scene.getImageId())
                : findSceneRepresentImage(scene.getDictCode()));
            return item;
        }).toList();
    }

    /**
     * 类目两级树：category_dict 按 parent_code 组装（当前种子为单层，roots 即全部类目）。
     *
     * @return 类目树根节点列表
     */
    public List<PublicCategoryResponse> listCategories() {
        List<CategoryDict> all = dictService.listByType(DICT_TYPE_CATEGORY);
        Map<String, List<CategoryDict>> childrenByParent = all.stream()
            .filter(d -> StringUtils.hasText(d.getParentCode()))
            .collect(Collectors.groupingBy(CategoryDict::getParentCode));
        return all.stream()
            .filter(d -> !StringUtils.hasText(d.getParentCode()))
            .map(d -> toCategoryNode(d, childrenByParent))
            .toList();
    }

    private PublicCategoryResponse toCategoryNode(CategoryDict dict,
            Map<String, List<CategoryDict>> childrenByParent) {
        PublicCategoryResponse node = new PublicCategoryResponse();
        node.setDictCode(dict.getDictCode());
        node.setDictName(dict.getDictName());
        node.setDictNameEn(dict.getDictNameEn());
        node.setSortOrder(dict.getSortOrder());
        List<PublicCategoryResponse> children = childrenByParent
            .getOrDefault(dict.getDictCode(), List.of())
            .stream()
            .map(child -> toCategoryNode(child, childrenByParent))
            .toList();
        node.setChildren(children);
        return node;
    }

    /**
     * 批量查询产品图片地址。
     *
     * @param rspuIds 产品 ID 列表
     * @param scene   true=取场景图（image_type=scene），false=取主图（is_primary）
     * @return rspuId → 图片访问地址
     */
    private Map<String, String> batchImageUrls(List<String> rspuIds, boolean scene) {
        QueryWrapper<ImageAssets> wrapper = new QueryWrapper<ImageAssets>()
            .in("rspu_id", rspuIds)
            .orderByDesc("created_at");
        if (scene) {
            wrapper.eq("image_type", IMAGE_TYPE_SCENE);
        } else {
            wrapper.eq("is_primary", true);
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(wrapper);
        Map<String, String> result = new HashMap<>();
        for (ImageAssets image : images) {
            // 按创建时间倒序遍历，putIfAbsent 保留每个产品最新一张
            result.putIfAbsent(image.getRspuId(), imageUrl(image.getImageId()));
        }
        return result;
    }

    /**
     * 批量统计变体数量。
     *
     * @param rspuIds 产品 ID 列表
     * @return rspuId → 变体数
     */
    private Map<String, Long> batchVariantCounts(List<String> rspuIds) {
        List<Map<String, Object>> rows = rspuVariantMapper.selectMaps(new QueryWrapper<RspuVariant>()
            .select("rspu_id", "COUNT(*) AS cnt")
            .in("rspu_id", rspuIds)
            .groupBy("rspu_id"));
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("rspu_id"), ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    /**
     * 查询空间代表图：该场景下最新在售产品的主图。
     *
     * @param sceneCode 场景字典码
     * @return 图片访问地址，无在售产品图时为 null
     */
    private String findSceneRepresentImage(String sceneCode) {
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .eq("is_primary", true)
            .apply("rspu_id IN (SELECT rspu_id FROM rspu_scene WHERE scene_code = {0})", sceneCode)
            .apply("rspu_id IN (SELECT rspu_id FROM rspu_master WHERE status = {0} AND deleted_at IS NULL)",
                STATUS_ACTIVE)
            .orderByDesc("created_at")
            .last("LIMIT 1"));
        return images.isEmpty() ? null : imageUrl(images.get(0).getImageId());
    }

    private String imageUrl(String imageId) {
        return StringUtils.hasText(imageId) ? "/api/v1/images/" + imageId : null;
    }

    private String toJsonArray(String value) {
        try {
            return objectMapper.writeValueAsString(List.of(value));
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
