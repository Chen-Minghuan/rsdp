package com.rsdp.service;

import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderItemResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link OrderExportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderExportServiceTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderExportService orderExportService;

    @Test
    void exportShouldProduceExcelWithOrderNoFileName() {
        OrderDetailResponse detail = new OrderDetailResponse();
        detail.setOrderNo("DO-20260715-001");
        detail.setStatus("PENDING");
        detail.setReceiverName("张三");
        detail.setItemCount(2);
        detail.setOriginalTotalPrice(new BigDecimal("2000.00"));
        detail.setPriceRate(new BigDecimal("0.9"));
        detail.setFinalTotalPrice(new BigDecimal("1800.00"));

        OrderItemResponse item1 = new OrderItemResponse();
        item1.setId(1L);
        item1.setProductName("布艺沙发");
        item1.setModel("FS-100");
        item1.setRspuId("RSPU-001");
        item1.setFactoryCode("F001");
        item1.setQuantity(2);
        item1.setOriginalPrice(new BigDecimal("1000.00"));
        item1.setFinalPrice(new BigDecimal("900.00"));
        item1.setAdjustPrice(new BigDecimal("850.00"));
        item1.setEffectivePrice(new BigDecimal("850.00"));
        item1.setSubtotal(new BigDecimal("1700.00"));

        OrderItemResponse item2 = new OrderItemResponse();
        item2.setId(2L);
        item2.setProductName("极简茶几");
        item2.setQuantity(1);
        item2.setFinalPrice(new BigDecimal("100.00"));
        item2.setEffectivePrice(new BigDecimal("100.00"));
        item2.setSubtotal(new BigDecimal("100.00"));

        detail.setItems(List.of(item1, item2));
        when(orderService.detail("ORD-1")).thenReturn(detail);

        OrderExportService.OrderExportFile file = orderExportService.export("ORD-1");

        assertThat(file.content()).isNotEmpty();
        assertThat(file.fileName()).isEqualTo("DO-20260715-001-订单明细.xlsx");
    }

    @Test
    void exportShouldHandleEmptyItems() {
        OrderDetailResponse detail = new OrderDetailResponse();
        detail.setOrderNo("DO-20260715-002");
        detail.setStatus("PENDING");
        detail.setItems(null);
        when(orderService.detail("ORD-2")).thenReturn(detail);

        OrderExportService.OrderExportFile file = orderExportService.export("ORD-2");

        assertThat(file.content()).isNotEmpty();
        assertThat(file.fileName()).isEqualTo("DO-20260715-002-订单明细.xlsx");
    }
}
