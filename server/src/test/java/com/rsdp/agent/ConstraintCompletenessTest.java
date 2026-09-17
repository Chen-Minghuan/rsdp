package com.rsdp.agent;

import com.rsdp.agent.graph.ConstraintCompleteness;
import com.rsdp.agent.patch.RequirementConstraints;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConstraintCompleteness} 单元测试（关键约束三组取二判定）。
 */
class ConstraintCompletenessTest {

    private final ConstraintCompleteness completeness = new ConstraintCompleteness();

    @Test
    void nullConstraintsShouldMissAllThreeGroups() {
        assertThat(completeness.isComplete(null)).isFalse();
        assertThat(completeness.missingAspects(null)).hasSize(3);
    }

    @Test
    void emptyConstraintsShouldNotBeComplete() {
        assertThat(completeness.isComplete(new RequirementConstraints())).isFalse();
        assertThat(completeness.missingAspects(new RequirementConstraints())).hasSize(3);
    }

    @Test
    void onlyCategoryShouldNotBeComplete() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");

        assertThat(completeness.isComplete(constraints)).isFalse();
        assertThat(completeness.missingAspects(constraints)).hasSize(2);
    }

    @Test
    void categoryAndBudgetShouldBeComplete() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");
        constraints.setBudgetMax(new BigDecimal("20000"));

        assertThat(completeness.isComplete(constraints)).isTrue();
        assertThat(completeness.missingAspects(constraints)).hasSize(1);
    }

    @Test
    void budgetAndMaxWidthShouldBeComplete() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setBudgetMax(new BigDecimal("20000"));
        constraints.setMaxWidthMm(2400);

        assertThat(completeness.isComplete(constraints)).isTrue();
    }

    @Test
    void categoryAndSofaFormShouldBeComplete() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");
        constraints.setSofaForm("L型");

        assertThat(completeness.isComplete(constraints)).isTrue();
    }

    @Test
    void budgetAndSofaFormShouldBeComplete() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setBudgetMax(new BigDecimal("20000"));
        constraints.setSofaForm("一字型");

        assertThat(completeness.isComplete(constraints)).isTrue();
    }

    @Test
    void allGroupsPresentShouldHaveNoMissing() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");
        constraints.setBudgetMax(new BigDecimal("20000"));
        constraints.setMaxWidthMm(2400);

        assertThat(completeness.isComplete(constraints)).isTrue();
        assertThat(completeness.missingAspects(constraints)).isEmpty();
    }

    @Test
    void minWidthAloneShouldNotSatisfyFormOrSizeGroup() {
        // 第三组要求 sofaForm 或 maxWidthMm，minWidthMm 不计入
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setMinWidthMm(2000);

        assertThat(completeness.isComplete(constraints)).isFalse();
        assertThat(completeness.missingAspects(constraints)).contains("沙发形态或尺寸限制");
    }
}
