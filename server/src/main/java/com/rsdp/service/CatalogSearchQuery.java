package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuMaster;

/** 将名称命中和向量候选合并到目录原有的 SQL 分页。 */
final class CatalogSearchQuery extends QueryWrapper<RspuMaster> {

    void applyMatch(CatalogSearchService.Match match, boolean includeInternalFields) {
        String keyword = match.keyword();
        if (keyword == null) {
            if (match.rankedIds().isEmpty()) {
                apply("1 = 0");
            } else {
                in("rspu_id", match.rankedIds());
            }
            return;
        }
        String pattern = containsPattern(keyword);
        and(matches -> {
            matches.apply("product_name ILIKE {0} ESCAPE '!' OR (" + productNameMention("{1}") + ")",
                pattern, keyword);
            if (includeInternalFields) {
                matches.or().apply("category_path ILIKE {0} ESCAPE '!'", pattern)
                    .or().apply("rspu_id ILIKE {0} ESCAPE '!'", pattern);
            }
            if (!match.rankedIds().isEmpty()) {
                matches.or().in("rspu_id", match.rankedIds());
            }
        });
    }

    void orderByMatch(CatalogSearchService.Match match, boolean includeInternalFields,
                      String secondaryOrder, boolean priceSort) {
        StringBuilder order = new StringBuilder("ORDER BY ");
        if (match.keyword() != null) {
            String exact = formatParam(null, match.keyword());
            String pattern = formatParam(null, containsPattern(match.keyword()));
            order.append("CASE WHEN lower(product_name) = lower(").append(exact).append(") THEN 0")
                .append(" WHEN product_name ILIKE ").append(pattern).append(" ESCAPE '!' OR (")
                .append(productNameMention(exact)).append(") THEN 1");
            if (includeInternalFields) {
                order.append(" WHEN category_path ILIKE ").append(pattern).append(" ESCAPE '!' OR rspu_id ILIKE ")
                    .append(pattern).append(" ESCAPE '!' THEN 2");
            }
            order.append(" ELSE 3 END, ");
        }
        if (priceSort) {
            order.append(secondaryOrder).append(", ");
        }
        if (!match.rankedIds().isEmpty()) {
            String ids = String.join(", ", match.rankedIds().stream().map(id -> formatParam(null, id)).toList());
            order.append("array_position(ARRAY[").append(ids).append("]::text[], rspu_id::text) ASC NULLS LAST, ");
        }
        if (!priceSort) {
            order.append(secondaryOrder).append(", ");
        }
        order.append("rspu_id ASC");
        last(order.toString());
    }

    private static String containsPattern(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String productNameMention(String termParameter) {
        return "length(btrim(product_name)) >= 3 AND strpos(lower(" + termParameter
            + "), lower(btrim(product_name))) > 0";
    }
}
