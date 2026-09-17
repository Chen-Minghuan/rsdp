package com.rsdp.agent.graph;

import com.rsdp.agent.patch.RequirementConstraints;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求约束齐备度判定（规则集中在代码常量，后续可外置配置）。
 *
 * <p>关键约束三组：categoryCode / budgetMax / (sofaForm 或 maxWidthMm)，
 * 至少命中两组视为齐备，可进入检索推荐。</p>
 */
@Component
public class ConstraintCompleteness {

    /**
     * 约束是否齐备（三组关键约束至少其二）。
     *
     * @param constraints 需求约束
     * @return 是否齐备
     */
    public boolean isComplete(RequirementConstraints constraints) {
        return missingAspects(constraints).size() <= 1;
    }

    /**
     * 缺失的关键约束描述（供追问话术与路由判断）。
     *
     * @param constraints 需求约束
     * @return 缺失组描述列表（空 = 全部齐备）
     */
    public List<String> missingAspects(RequirementConstraints constraints) {
        List<String> missing = new ArrayList<>();
        if (constraints == null) {
            missing.add("品类（如沙发/茶几/床）");
            missing.add("预算上限");
            missing.add("沙发形态或尺寸限制");
            return missing;
        }
        if (!StringUtils.hasText(constraints.getCategoryCode())) {
            missing.add("品类（如沙发/茶几/床）");
        }
        if (constraints.getBudgetMax() == null) {
            missing.add("预算上限");
        }
        if (!StringUtils.hasText(constraints.getSofaForm()) && constraints.getMaxWidthMm() == null) {
            missing.add("沙发形态或尺寸限制");
        }
        return missing;
    }
}
