package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.dto.response.SixDimSchemaResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.SixDimSchema;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SixDimSchemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六维标签维度定义服务（V25 配置化）。
 *
 * <p>维度定义落库（six_dim_schema 表），替代原前后端双写：
 * AI prompt 注入、前端展示/筛选/编辑统一从本服务（或对应查询接口）获取，
 * 新品类只需插入字典数据零代码上线。未知品类回退 GENERIC 定义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SixDimSchemaService {

    /** 未知品类兜底定义的品类码。 */
    public static final String GENERIC_CATEGORY = "GENERIC";

    private static final List<String> DIM_ORDER = List.of("A", "B", "C", "D", "E", "F");

    private final SixDimSchemaMapper sixDimSchemaMapper;
    private final DictService dictService;

    /**
     * 按品类码获取六维定义；未知品类回退 GENERIC 定义。
     *
     * @param categoryCode 品类码（空时返回 GENERIC 定义）
     * @return 六维维度定义
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_NAME_SIX_DIM_SCHEMA, key = "'schema:' + #categoryCode")
    public SixDimSchemaResponse getSchema(String categoryCode) {
        String code = StringUtils.hasText(categoryCode) ? categoryCode.trim().toUpperCase() : GENERIC_CATEGORY;
        List<SixDimSchema> rows = listByCategory(code);
        if (rows.isEmpty() && !GENERIC_CATEGORY.equals(code)) {
            rows = listByCategory(GENERIC_CATEGORY);
            code = GENERIC_CATEGORY;
        }
        return toResponse(code, rows);
    }

    /**
     * 获取全部品类的六维定义（字典管理中心维护入口数据源）。
     *
     * @return 全品类六维定义列表
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_NAME_SIX_DIM_SCHEMA, key = "'all'")
    public List<SixDimSchemaResponse> listAllSchemas() {
        List<SixDimSchema> rows = sixDimSchemaMapper.selectList(
            new LambdaQueryWrapper<SixDimSchema>()
                .orderByAsc(SixDimSchema::getCategoryCode)
                .orderByAsc(SixDimSchema::getSortOrder)
        );
        Map<String, List<SixDimSchema>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getCategoryCode(), k -> new java.util.ArrayList<>()).add(row));
        return grouped.entrySet().stream()
            .map(entry -> toResponse(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * 获取 prompt 中使用的六维说明文本。
     *
     * @param categoryCode 品类码
     * @return 六维维度定义说明文本
     */
    public String buildPromptDescription(String categoryCode) {
        SixDimSchemaResponse schema = getSchema(categoryCode);
        StringBuilder sb = new StringBuilder();
        sb.append("本产品的六维标签定义如下（请严格按 A-F 输出，键名不变）：\n");
        schema.dims().forEach((key, def) ->
            sb.append("  ").append(key).append(" = ").append(def.label())
              .append("：").append(def.description()).append("\n")
        );
        return sb.toString();
    }

    /**
     * 更新某品类某维度的标签与说明（字典管理中心维护入口）。
     *
     * @param categoryCode 品类码
     * @param dimKey       维度键
     * @param label        维度标签
     * @param description  维度说明
     * @return 更新后的该品类六维定义
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_NAME_SIX_DIM_SCHEMA, allEntries = true)
    public SixDimSchemaResponse updateDim(String categoryCode, String dimKey, String label, String description) {
        SixDimSchema row = sixDimSchemaMapper.selectOne(
            new LambdaQueryWrapper<SixDimSchema>()
                .eq(SixDimSchema::getCategoryCode, categoryCode)
                .eq(SixDimSchema::getDimKey, dimKey)
        );
        if (row == null) {
            throw new BusinessException("六维维度定义不存在: " + categoryCode + "/" + dimKey);
        }
        row.setLabel(label);
        row.setDescription(description == null ? "" : description);
        row.setUpdatedAt(LocalDateTime.now());
        sixDimSchemaMapper.updateById(row);
        log.info("六维维度定义已更新: {}/{} → {}", categoryCode, dimKey, label);
        return toResponse(categoryCode, listByCategory(categoryCode));
    }

    private List<SixDimSchema> listByCategory(String categoryCode) {
        return sixDimSchemaMapper.selectList(
            new LambdaQueryWrapper<SixDimSchema>()
                .eq(SixDimSchema::getCategoryCode, categoryCode)
                .orderByAsc(SixDimSchema::getSortOrder)
        );
    }

    /**
     * 实体行 → 响应：维度按 A-F 键序装配，品类名从 category 字典解析（未收录回退品类码）。
     */
    private SixDimSchemaResponse toResponse(String categoryCode, List<SixDimSchema> rows) {
        Map<String, SixDimSchemaResponse.DimDefinition> dims = new LinkedHashMap<>();
        DIM_ORDER.forEach(dim -> rows.stream()
            .filter(row -> dim.equalsIgnoreCase(row.getDimKey()))
            .findFirst()
            .ifPresent(row -> dims.put(dim,
                new SixDimSchemaResponse.DimDefinition(row.getLabel(), row.getDescription()))));
        return new SixDimSchemaResponse(categoryCode, resolveCategoryName(categoryCode), dims);
    }

    private String resolveCategoryName(String categoryCode) {
        if (GENERIC_CATEGORY.equals(categoryCode)) {
            return "通用";
        }
        try {
            return dictService.listByType("category").stream()
                .filter(d -> categoryCode.equalsIgnoreCase(d.getDictCode()))
                .map(CategoryDict::getDictName)
                .findFirst()
                .orElse(categoryCode);
        } catch (Exception e) {
            log.warn("解析品类名失败，回退品类码: {}", categoryCode, e);
            return categoryCode;
        }
    }
}
