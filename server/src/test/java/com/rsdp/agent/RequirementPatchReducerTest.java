package com.rsdp.agent;

import com.rsdp.agent.patch.PatchOperation;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.patch.RequirementPatch;
import com.rsdp.agent.patch.RequirementPatchReducer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequirementPatchReducer} 单元测试（Patch 应用层核心架构守卫）。
 */
class RequirementPatchReducerTest {

    private final RequirementPatchReducer reducer = new RequirementPatchReducer();

    private PatchOperation op(String field, String operation, String value, String evidence) {
        PatchOperation op = new PatchOperation();
        op.setField(field);
        op.setOperation(operation);
        op.setValue(value);
        op.setEvidence(evidence);
        return op;
    }

    private RequirementPatch patch(PatchOperation... ops) {
        RequirementPatch patch = new RequirementPatch();
        patch.setOperations(List.of(ops));
        return patch;
    }

    @Test
    void setBudgetMaxShouldConvertStringToBigDecimal() {
        RequirementConstraints base = new RequirementConstraints();

        RequirementPatchReducer.ApplyResult result = reducer.apply(base,
            patch(op("budgetMax", "set", "20000", "预算两万以内")));

        assertThat(result.getAppliedCount()).isEqualTo(1);
        assertThat(result.getRejectedOperations()).isEmpty();
        assertThat(result.getUpdated().getBudgetMax()).isEqualByComparingTo(new BigDecimal("20000"));
        // base 不被修改（不可变语义）
        assertThat(base.getBudgetMax()).isNull();
    }

    @Test
    void setMaxWidthMmShouldConvertStringToInteger() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("maxWidthMm", "set", "2400", "客厅宽度最多 2.4 米")));

        assertThat(result.getAppliedCount()).isEqualTo(1);
        assertThat(result.getUpdated().getMaxWidthMm()).isEqualTo(2400);
    }

    @Test
    void setAreaM2ShouldConvertToBigDecimal() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("areaM2", "set", "18.5", "客厅 18.5 平")));

        assertThat(result.getAppliedCount()).isEqualTo(1);
        assertThat(result.getUpdated().getAreaM2()).isEqualByComparingTo(new BigDecimal("18.5"));
    }

    @Test
    void fieldOutsideWhitelistShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("factoryPrice", "set", "100", "出厂价一百")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("白名单");
        assertThat(result.getRejectedOperations().get(0).operation().getField()).isEqualTo("factoryPrice");
    }

    @Test
    void setWithBlankEvidenceShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("style", "set", "现代", "   ")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("evidence");
        assertThat(result.getUpdated().getStyle()).isNull();
    }

    @Test
    void setWithMissingValueShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("style", "set", null, "要现代风格")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("value");
    }

    @Test
    void clearShouldRemoveExistingField() {
        RequirementConstraints base = new RequirementConstraints();
        base.setStyle("现代");
        base.setBudgetMax(new BigDecimal("20000"));

        RequirementPatchReducer.ApplyResult result = reducer.apply(base,
            patch(op("style", "clear", null, null)));

        assertThat(result.getAppliedCount()).isEqualTo(1);
        assertThat(result.getUpdated().getStyle()).isNull();
        // 其余字段不受影响，base 不被修改
        assertThat(result.getUpdated().getBudgetMax()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(base.getStyle()).isEqualTo("现代");
    }

    @Test
    void budgetMaxWithNonNumericValueShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("budgetMax", "set", "abc", "预算 abc")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("类型转换失败");
        assertThat(result.getUpdated().getBudgetMax()).isNull();
    }

    @Test
    void maxWidthMmWithNonNumericValueShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("maxWidthMm", "set", "2.4米", "宽度 2.4 米")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("类型转换失败");
    }

    @Test
    void unknownOperationShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op("style", "delete", "现代", "去掉风格")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getRejectedOperations().get(0).reason()).contains("未知 operation");
    }

    @Test
    void nullFieldShouldBeRejected() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(op(null, "set", "现代", "要现代风格")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).hasSize(1);
    }

    @Test
    void partialFailureShouldNotBlockOtherOperations() {
        RequirementPatchReducer.ApplyResult result = reducer.apply(new RequirementConstraints(),
            patch(
                op("style", "set", "现代", "要现代风格"),
                op("factoryPrice", "set", "100", "出厂价一百"),
                op("budgetMax", "set", "20000", "预算两万")
            ));

        assertThat(result.getAppliedCount()).isEqualTo(2);
        assertThat(result.getRejectedOperations()).hasSize(1);
        assertThat(result.getUpdated().getStyle()).isEqualTo("现代");
        assertThat(result.getUpdated().getBudgetMax()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void nullPatchShouldReturnBaseCopyWithZeroApplied() {
        RequirementConstraints base = new RequirementConstraints();
        base.setStyle("现代");

        RequirementPatchReducer.ApplyResult result = reducer.apply(base, null);

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getRejectedOperations()).isEmpty();
        assertThat(result.getUpdated().getStyle()).isEqualTo("现代");
        assertThat(result.getUpdated()).isNotSameAs(base);
    }

    @Test
    void emptyOperationsShouldReturnBaseCopyWithZeroApplied() {
        RequirementPatch patch = new RequirementPatch();
        patch.setOperations(List.of());

        RequirementPatchReducer.ApplyResult result = reducer.apply(null, patch);

        // appliedCount=0 时上层（RequirementPatchNode）不应产生新版本
        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void allRejectedShouldYieldZeroAppliedAndUnchangedConstraints() {
        RequirementConstraints base = new RequirementConstraints();
        base.setCategoryCode("沙发");

        RequirementPatchReducer.ApplyResult result = reducer.apply(base,
            patch(op("unknownField", "set", "x", "依据")));

        assertThat(result.getAppliedCount()).isZero();
        assertThat(result.getUpdated().getCategoryCode()).isEqualTo("沙发");
    }
}
