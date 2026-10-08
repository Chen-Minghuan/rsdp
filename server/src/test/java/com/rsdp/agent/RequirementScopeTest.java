package com.rsdp.agent;

import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.patch.RequirementScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequirementScope} 单元测试（作用域划分与有效约束合并语义守卫）。
 */
class RequirementScopeTest {

    @Test
    void globalFieldsShouldBeRecognized() {
        assertThat(RequirementScope.isGlobalField("style")).isTrue();
        assertThat(RequirementScope.isGlobalField("budgetMax")).isTrue();
        assertThat(RequirementScope.isGlobalField("note")).isTrue();
        assertThat(RequirementScope.isGlobalField("categoryCode")).isFalse();
        assertThat(RequirementScope.isGlobalField(null)).isFalse();
    }

    @Test
    void roomFieldsShouldBeRecognized() {
        assertThat(RequirementScope.isRoomField("categoryCode")).isTrue();
        assertThat(RequirementScope.isRoomField("material")).isTrue();
        assertThat(RequirementScope.isRoomField("maxWidthMm")).isTrue();
        assertThat(RequirementScope.isRoomField("areaM2")).isTrue();
        assertThat(RequirementScope.isRoomField("style")).isFalse();
        assertThat(RequirementScope.isRoomField(null)).isFalse();
    }

    @Test
    void mergeShouldOverlayNonNullRoomFields() {
        RequirementConstraints global = new RequirementConstraints();
        global.setStyle("现代意式");
        global.setBudgetMax(new BigDecimal("100000"));
        global.setCategoryCode("沙发");
        global.setColor("米白");

        RequirementConstraints room = new RequirementConstraints();
        room.setCategoryCode("床");
        // color 为 null：继承全局

        RequirementConstraints merged = RequirementScope.mergeEffective(global, room);

        assertThat(merged.getStyle()).isEqualTo("现代意式");
        assertThat(merged.getBudgetMax()).isEqualByComparingTo("100000");
        // 房间字段覆盖
        assertThat(merged.getCategoryCode()).isEqualTo("床");
        // 房间字段为 null 时继承全局
        assertThat(merged.getColor()).isEqualTo("米白");
    }

    @Test
    void mergeWithNullRoomShouldReturnGlobalCopy() {
        RequirementConstraints global = new RequirementConstraints();
        global.setStyle("MC");
        RequirementConstraints merged = RequirementScope.mergeEffective(global, null);
        assertThat(merged.getStyle()).isEqualTo("MC");
        // 返回新对象，不改入参
        assertThat(merged).isNotSameAs(global);
    }

    @Test
    void mergeWithNullGlobalShouldUseRoomOnly() {
        RequirementConstraints room = new RequirementConstraints();
        room.setCategoryCode("床");
        room.setMaxWidthMm(2000);
        RequirementConstraints merged = RequirementScope.mergeEffective(null, room);
        assertThat(merged.getCategoryCode()).isEqualTo("床");
        assertThat(merged.getMaxWidthMm()).isEqualTo(2000);
        assertThat(merged.getStyle()).isNull();
    }
}
