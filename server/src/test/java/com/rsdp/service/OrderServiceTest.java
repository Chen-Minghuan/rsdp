package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.dto.request.OrderCreateRequest;
import com.rsdp.dto.request.OrderUpdateRequest;
import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderListResponse;
import com.rsdp.dto.response.OrderResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private DesignOrderMapper designOrderMapper;

    @Mock
    private DesignOrderItemMapper designOrderItemMapper;

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private ProjectService projectService;

    @Mock
    private ConfigService configService;

    @Mock
    private CompanyService companyService;

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderService orderService;

    private Scheme ownedScheme() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setSchemeName("客厅方案");
        scheme.setCreatedBy("designer1");
        return scheme;
    }

    private DesignOrder pendingOrder() {
        DesignOrder order = new DesignOrder();
        order.setOrderId("ORD-1");
        order.setOrderNo("DO-20260715-001");
        order.setStatus(OrderService.STATUS_PENDING);
        order.setCreatedBy("user-1");
        return order;
    }

    private void stubDataScope() {
        lenient().when(dataScopeHelper.canAccessFactory(anyString())).thenReturn(true);
    }

    @Test
    void createShouldSnapshotPricesWithPriceRate() {
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(ownedScheme());

        SchemeItem item = new SchemeItem();
        item.setSchemeId("SCHEME-1");
        item.setRspuId("RSPU-001");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setQuantity(2);
        when(schemeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item));
        stubDataScope();

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactorySku("FS-100");
        rsku.setFactoryPrice(new BigDecimal("1000.00"));
        rsku.setLeadTimeDays(25);
        when(rskuSupplyMapper.selectBatchIds(anyList())).thenReturn(List.of(rsku));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("布艺沙发");
        when(rspuMapper.selectBatchIds(anyList())).thenReturn(List.of(rspu));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(configService.getOrderPriceRate()).thenReturn(new BigDecimal("0.8"));
        when(orderNoGenerator.generate()).thenReturn("DO-20260715-001");

        AtomicReference<DesignOrder> insertedOrder = new AtomicReference<>();
        when(designOrderMapper.insert(any(DesignOrder.class))).thenAnswer(inv -> {
            insertedOrder.set(inv.getArgument(0));
            return 1;
        });
        when(designOrderMapper.selectById(anyString())).thenAnswer(inv -> {
            DesignOrder saved = insertedOrder.get();
            return saved != null && saved.getOrderId().equals(inv.getArgument(0)) ? saved : null;
        });
        AtomicReference<List<DesignOrderItem>> insertedItems = new AtomicReference<>(List.of());
        when(designOrderItemMapper.insertBatchSafe(anyList())).thenAnswer(inv -> {
            insertedItems.set(inv.getArgument(0));
            return 1;
        });
        when(designOrderItemMapper.selectList(any(QueryWrapper.class)))
            .thenAnswer(inv -> insertedItems.get());

        OrderCreateRequest request = new OrderCreateRequest();
        request.setSchemeId("SCHEME-1");
        request.setReceiverName("张三");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            OrderDetailResponse response = orderService.create(request);

            // 快照价：1000 × 0.8 = 800，数量 2
            assertThat(response.getOriginalTotalPrice()).isEqualByComparingTo("2000.00");
            assertThat(response.getFinalTotalPrice()).isEqualByComparingTo("1600.00");
            assertThat(response.getPriceRate()).isEqualByComparingTo("0.8");
            assertThat(response.getStatus()).isEqualTo(OrderService.STATUS_PENDING);
            assertThat(response.getItemCount()).isEqualTo(1);
            assertThat(response.getExpectedLeadTime()).isEqualTo(25);
            assertThat(response.getItems()).hasSize(1);
            assertThat(response.getItems().get(0).getFinalPrice()).isEqualByComparingTo("800.00");
            assertThat(response.getItems().get(0).getProductName()).isEqualTo("布艺沙发");
            assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo("1600.00");
        }
    }

    @Test
    void createShouldUseCompanyPriceRatioWhenUserInCompany() {
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(ownedScheme());

        SchemeItem item = new SchemeItem();
        item.setSchemeId("SCHEME-1");
        item.setRspuId("RSPU-001");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setQuantity(2);
        when(schemeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item));
        stubDataScope();

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactorySku("FS-100");
        rsku.setFactoryPrice(new BigDecimal("1000.00"));
        when(rskuSupplyMapper.selectBatchIds(anyList())).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(anyList())).thenReturn(List.of(new RspuMaster()));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        // 企业折扣率 0.9 优先于全局 0.8
        when(companyService.resolveOrderPriceRate("user-1")).thenReturn(new BigDecimal("0.9"));
        when(orderNoGenerator.generate()).thenReturn("DO-20260715-002");

        AtomicReference<DesignOrder> insertedOrder = new AtomicReference<>();
        when(designOrderMapper.insert(any(DesignOrder.class))).thenAnswer(inv -> {
            insertedOrder.set(inv.getArgument(0));
            return 1;
        });
        when(designOrderMapper.selectById(anyString())).thenAnswer(inv -> {
            DesignOrder saved = insertedOrder.get();
            return saved != null && saved.getOrderId().equals(inv.getArgument(0)) ? saved : null;
        });
        AtomicReference<List<DesignOrderItem>> insertedItems = new AtomicReference<>(List.of());
        when(designOrderItemMapper.insertBatchSafe(anyList())).thenAnswer(inv -> {
            insertedItems.set(inv.getArgument(0));
            return 1;
        });
        when(designOrderItemMapper.selectList(any(QueryWrapper.class)))
            .thenAnswer(inv -> insertedItems.get());

        OrderCreateRequest request = new OrderCreateRequest();
        request.setSchemeId("SCHEME-1");
        request.setReceiverName("张三");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            OrderDetailResponse response = orderService.create(request);

            // 快照价：1000 × 0.9（企业折扣率）= 900，数量 2
            assertThat(response.getPriceRate()).isEqualByComparingTo("0.9");
            assertThat(response.getFinalTotalPrice()).isEqualByComparingTo("1800.00");
            assertThat(response.getItems().get(0).getFinalPrice()).isEqualByComparingTo("900.00");
        }
    }

    @Test
    void createShouldRejectMissingScheme() {
        when(schemeMapper.selectById("SCHEME-X")).thenReturn(null);

        OrderCreateRequest request = new OrderCreateRequest();
        request.setSchemeId("SCHEME-X");

        assertThatThrownBy(() -> orderService.create(request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("方案不存在");
    }

    @Test
    void createShouldRejectNonOwnerScheme() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setCreatedBy("other");
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        OrderCreateRequest request = new OrderCreateRequest();
        request.setSchemeId("SCHEME-1");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer1");

            assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("无权");
        }
    }

    @Test
    void updateShouldRejectNonPendingOrder() {
        DesignOrder order = pendingOrder();
        order.setStatus(OrderService.STATUS_CONFIRMED);
        when(designOrderMapper.selectById("ORD-1")).thenReturn(order);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> orderService.update("ORD-1", new OrderUpdateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认");
        }
    }

    @Test
    void updateStatusShouldAllowLegalTransition() {
        when(designOrderMapper.selectById("ORD-1")).thenReturn(pendingOrder());
        when(designOrderMapper.update(any(DesignOrder.class), any(QueryWrapper.class))).thenReturn(1);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            OrderResponse response = orderService.updateStatus("ORD-1", OrderService.STATUS_CONFIRMED);

            assertThat(response.getStatus()).isEqualTo(OrderService.STATUS_CONFIRMED);
        }
        verify(designOrderMapper).update(any(DesignOrder.class), any(QueryWrapper.class));
    }

    @Test
    void updateStatusShouldFailWhenConcurrentlyModified() {
        when(designOrderMapper.selectById("ORD-1")).thenReturn(pendingOrder());
        when(designOrderMapper.update(any(DesignOrder.class), any(QueryWrapper.class))).thenReturn(0);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> orderService.updateStatus("ORD-1", OrderService.STATUS_CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他操作修改");
        }
    }

    @Test
    void updateStatusShouldRejectIllegalTransition() {
        when(designOrderMapper.selectById("ORD-1")).thenReturn(pendingOrder());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> orderService.updateStatus("ORD-1", OrderService.STATUS_COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");
        }
    }

    @Test
    void updateStatusShouldRejectTerminalState() {
        DesignOrder order = pendingOrder();
        order.setStatus(OrderService.STATUS_CANCELLED);
        when(designOrderMapper.selectById("ORD-1")).thenReturn(order);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> orderService.updateStatus("ORD-1", OrderService.STATUS_CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");
        }
    }

    @Test
    void listShouldReturnStatusCounts() {
        Page<DesignOrder> page = Page.of(1, 10);
        page.setRecords(List.of(pendingOrder()));
        page.setTotal(1);
        when(designOrderMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(designOrderMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of(
            Map.of("status", "PENDING", "cnt", 1L)
        ));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            OrderListResponse response = orderService.list(null, 1, 10);

            assertThat(response.getTotal()).isEqualTo(1);
            assertThat(response.getStatusCounts()).containsEntry("PENDING", 1L);
        }
    }
}
