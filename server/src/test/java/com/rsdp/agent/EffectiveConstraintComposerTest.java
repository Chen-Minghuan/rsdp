package com.rsdp.agent;

import com.rsdp.agent.domain.EffectiveConstraintComposer;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.patch.RequirementConstraints;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EffectiveConstraintComposer} 单元测试（空间硬约束只收紧不放宽的优先级守卫）。
 */
class EffectiveConstraintComposerTest {

    private final EffectiveConstraintComposer composer = new EffectiveConstraintComposer();

    private FloorPlanRoomFact room(Integer widthMm, Integer depthMm) {
        return new FloorPlanRoomFact("ROOM-1", "客厅", "LIVING_ROOM", widthMm, depthMm,
            widthMm != null && depthMm != null
                ? new BigDecimal(widthMm).multiply(new BigDecimal(depthMm)).divide(new BigDecimal("1000000"))
                : null);
    }

    @Test
    void noRoomShouldPassThroughUserConstraints() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");
        constraints.setMaxWidthMm(2800);

        ProductSearchCriteria criteria = composer.compose(constraints, null);

        assertThat(criteria.getCategoryCode()).isEqualTo("沙发");
        assertThat(criteria.getMaxWidthMm()).isEqualTo(2800);
        assertThat(criteria.getMaxDepthMm()).isNull();
        assertThat(criteria.getRoomType()).isNull();
    }

    @Test
    void roomShouldApplySpaceCapsWhenUserHasNoSizeLimit() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");

        ProductSearchCriteria criteria = composer.compose(constraints, room(4300, 3800));

        // 空间硬约束：开间/进深 - 600 通道预留
        assertThat(criteria.getMaxWidthMm()).isEqualTo(3700);
        assertThat(criteria.getMaxDepthMm()).isEqualTo(3200);
        assertThat(criteria.getRoomType()).isEqualTo("LIVING_ROOM");
        assertThat(criteria.getRoomAreaM2()).isNotNull();
    }

    @Test
    void stricterUserLimitShouldWinOverSpaceCap() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setMaxWidthMm(2800);

        ProductSearchCriteria criteria = composer.compose(constraints, room(4300, 3800));

        // 用户更严格（2800 < 3700）：取用户值
        assertThat(criteria.getMaxWidthMm()).isEqualTo(2800);
    }

    @Test
    void looserUserLimitShouldBeTightenedBySpaceCap() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setMaxWidthMm(4000);

        ProductSearchCriteria criteria = composer.compose(constraints, room(4300, 3800));

        // 空间更严格（3700 < 4000）：空间硬约束优先
        assertThat(criteria.getMaxWidthMm()).isEqualTo(3700);
    }

    @Test
    void unknownRoomDimensionsShouldNotApplySpaceCaps() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setMaxWidthMm(2800);

        ProductSearchCriteria criteria = composer.compose(constraints, room(null, null));

        assertThat(criteria.getMaxWidthMm()).isEqualTo(2800);
        assertThat(criteria.getMaxDepthMm()).isNull();
    }

    @Test
    void tinyRoomShouldNotApplyNegativeCap() {
        ProductSearchCriteria criteria = composer.compose(new RequirementConstraints(), room(500, 400));
        // 开间不足 600 时 cap <= 0：不施加约束（避免误杀全部结果）
        assertThat(criteria.getMaxWidthMm()).isNull();
        assertThat(criteria.getMaxDepthMm()).isNull();
    }

    @Test
    void roomProvidesSizeConstraintShouldFollowWidthKnowledge() {
        assertThat(composer.roomProvidesSizeConstraint(room(4300, 3800))).isTrue();
        assertThat(composer.roomProvidesSizeConstraint(room(null, 3800))).isFalse();
        assertThat(composer.roomProvidesSizeConstraint(room(500, 3800))).isFalse();
        assertThat(composer.roomProvidesSizeConstraint(null)).isFalse();
    }

    @Test
    void nullConstraintsShouldStillApplyRoomContext() {
        ProductSearchCriteria criteria = composer.compose(null, room(4300, 3800));
        assertThat(criteria.getMaxWidthMm()).isEqualTo(3700);
        assertThat(criteria.getRoomType()).isEqualTo("LIVING_ROOM");
    }
}
