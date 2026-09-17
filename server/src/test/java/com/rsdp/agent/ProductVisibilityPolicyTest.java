package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.domain.ProductVisibilityPolicy;
import com.rsdp.common.ReviewStatus;
import com.rsdp.entity.RspuMaster;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link ProductVisibilityPolicy} 单元测试（审核可见性收口守卫）。
 */
@ExtendWith(MockitoExtension.class)
class ProductVisibilityPolicyTest {

    private final ProductVisibilityPolicy policy = new ProductVisibilityPolicy();

    @Test
    void platformStaffShouldOnlyFilterActiveStatus() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
            policy.applyScope(wrapper);

            String sqlSegment = wrapper.getSqlSegment();
            assertThat(sqlSegment).contains("status");
            assertThat(sqlSegment).doesNotContain("review_status");
            assertThat(wrapper.getParamNameValuePairs())
                .containsValue(ProductVisibilityPolicy.STATUS_ACTIVE);
        }
    }

    @Test
    void nonPlatformStaffShouldForceApprovedReviewStatus() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);

            QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
            policy.applyScope(wrapper);

            String sqlSegment = wrapper.getSqlSegment();
            assertThat(sqlSegment).contains("status");
            assertThat(sqlSegment).contains("review_status");
            assertThat(wrapper.getParamNameValuePairs())
                .containsValue(ProductVisibilityPolicy.STATUS_ACTIVE)
                .containsValue(ReviewStatus.APPROVED.getDbValue());
        }
    }
}
