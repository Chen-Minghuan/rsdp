package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.OrderFactoryStatResponse;
import com.rsdp.dto.response.OrderProductStatResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderStatisticsService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderStatisticsServiceTest {

    @Mock
    private DesignOrderMapper designOrderMapper;

    @Mock
    private DesignOrderItemMapper designOrderItemMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @InjectMocks
    private OrderStatisticsService orderStatisticsService;

    @Test
    void statisticsShouldRejectInvalidDim() {
        assertThatThrownBy(() -> orderStatisticsService.statistics("invalid", null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的统计维度");
    }

    @Test
    void statByProductShouldAggregateAcrossOrdersAndSortDesc() {
        when(designOrderMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(order("ORD-1"), order("ORD-2")));
        DesignOrderItem a1 = item("ORD-1", "RSPU-A", "F001", "100", 2);
        a1.setProductName("现代沙发");
        a1.setImageId("IMG-1");
        when(designOrderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            a1,
            item("ORD-2", "RSPU-A", "F001", "120", 1),
            item("ORD-2", "RSPU-B", "F002", "50", 3)
        ));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            List<OrderProductStatResponse> stats = orderStatisticsService.statByProduct(null, null);

            assertThat(stats).hasSize(2);
            assertThat(stats.get(0).getRspuId()).isEqualTo("RSPU-A");
            assertThat(stats.get(0).getProductName()).isEqualTo("现代沙发");
            assertThat(stats.get(0).getImageId()).isEqualTo("IMG-1");
            assertThat(stats.get(0).getTotalQuantity()).isEqualTo(3);
            assertThat(stats.get(0).getTotalAmount()).isEqualByComparingTo("320.00");
            assertThat(stats.get(1).getRspuId()).isEqualTo("RSPU-B");
            assertThat(stats.get(1).getTotalQuantity()).isEqualTo(3);
            assertThat(stats.get(1).getTotalAmount()).isEqualByComparingTo("150.00");
        }
    }

    @Test
    void statByProductShouldScopeToCreatorAndExcludeCancelledForNonAdmin() {
        when(designOrderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            List<OrderProductStatResponse> stats = orderStatisticsService.statByProduct(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));

            assertThat(stats).isEmpty();
        }

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(designOrderMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("created_by");
        assertThat(sqlSegment).contains("status");
        assertThat(sqlSegment).contains("created_at");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("CANCELLED");
        verify(designOrderItemMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void statByFactoryShouldAggregateDistinctOrdersAndFillNames() {
        when(designOrderMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(order("ORD-1"), order("ORD-2")));
        when(designOrderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            item("ORD-1", "RSPU-A", "F001", "100", 2),
            item("ORD-1", "RSPU-B", "F001", "50", 1),
            item("ORD-2", "RSPU-C", "F001", "200", 1),
            item("ORD-2", "RSPU-D", "F002", "80", 2),
            item("ORD-2", "RSPU-E", null, "999", 9)
        ));
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("佛山家具厂");
        when(factoryMasterMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(factory));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            List<OrderFactoryStatResponse> stats = orderStatisticsService.statByFactory(null, null);

            assertThat(stats).hasSize(2);
            assertThat(stats.get(0).getFactoryCode()).isEqualTo("F001");
            assertThat(stats.get(0).getFactoryName()).isEqualTo("佛山家具厂");
            assertThat(stats.get(0).getOrderCount()).isEqualTo(2);
            assertThat(stats.get(0).getTotalQuantity()).isEqualTo(4);
            assertThat(stats.get(0).getTotalAmount()).isEqualByComparingTo("450.00");
            assertThat(stats.get(1).getFactoryCode()).isEqualTo("F002");
            assertThat(stats.get(1).getFactoryName()).isEqualTo("F002");
            assertThat(stats.get(1).getOrderCount()).isEqualTo(1);
            assertThat(stats.get(1).getTotalQuantity()).isEqualTo(2);
            assertThat(stats.get(1).getTotalAmount()).isEqualByComparingTo("160.00");
        }
    }

    @Test
    void statByFactoryShouldReturnEmptyWhenNoOrders() {
        when(designOrderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            List<OrderFactoryStatResponse> stats = orderStatisticsService.statByFactory(null, null);

            assertThat(stats).isEmpty();
        }
        verify(designOrderItemMapper, never()).selectList(any(QueryWrapper.class));
        verify(factoryMasterMapper, never()).selectList(any(QueryWrapper.class));
    }

    private DesignOrder order(String orderId) {
        DesignOrder order = new DesignOrder();
        order.setOrderId(orderId);
        return order;
    }

    private DesignOrderItem item(String orderId, String rspuId, String factoryCode, String finalPrice, int quantity) {
        DesignOrderItem item = new DesignOrderItem();
        item.setOrderId(orderId);
        item.setRspuId(rspuId);
        item.setFactoryCode(factoryCode);
        item.setFinalPrice(new BigDecimal(finalPrice));
        item.setQuantity(quantity);
        return item;
    }
}
