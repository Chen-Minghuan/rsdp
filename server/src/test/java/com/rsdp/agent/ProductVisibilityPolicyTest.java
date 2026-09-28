package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.domain.ProductVisibilityPolicy;
import com.rsdp.entity.RspuMaster;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductVisibilityPolicy} 单元测试（可见性收口守卫）。
 *
 * <p>复核机制已于 2026-09-28 移除：策略只下推 status='active'，与操作人身份无关。</p>
 */
class ProductVisibilityPolicyTest {

    private final ProductVisibilityPolicy policy = new ProductVisibilityPolicy();

    @Test
    void applyScopeShouldOnlyFilterActiveStatus() {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        policy.applyScope(wrapper);

        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).contains("status");
        assertThat(sqlSegment).doesNotContain("review_status");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(ProductVisibilityPolicy.STATUS_ACTIVE);
    }
}
