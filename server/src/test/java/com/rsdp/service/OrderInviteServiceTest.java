package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.InviteTokenResponse;
import com.rsdp.dto.response.OrderInviteViewResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.DesignOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link OrderInviteService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderInviteServiceTest {

    @Mock
    private DesignOrderMapper designOrderMapper;

    @Mock
    private DesignOrderItemMapper designOrderItemMapper;

    @Mock
    private OrderService orderService;

    private OrderInviteService inviteService;

    @BeforeEach
    void setUp() {
        inviteService = new OrderInviteService(
            designOrderMapper, designOrderItemMapper, orderService,
            "test-secret-must-be-at-least-32-characters-long", 7);
    }

    private DesignOrder buildOrder() {
        DesignOrder order = new DesignOrder();
        order.setOrderId("ORD-test0001");
        order.setOrderNo("DO-20260713-001");
        order.setStatus(OrderService.STATUS_PENDING);
        order.setReceiverArea("广东省佛山市");
        order.setFinalTotalPrice(new BigDecimal("9999.00"));
        order.setItemCount(1);
        order.setExpectedLeadTime(15);
        return order;
    }

    private void mockItems() {
        DesignOrderItem item = new DesignOrderItem();
        item.setId(1L);
        item.setOrderId("ORD-test0001");
        item.setProductName("北欧实木餐椅");
        item.setModel("MC-001");
        item.setImageId("IMG-001");
        item.setQuantity(4);
        item.setFinalPrice(new BigDecimal("2499.75"));
        when(designOrderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item));
    }

    @Test
    void createInvite_shouldGenerateTokenAndPersistHash() {
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);

        InviteTokenResponse response = inviteService.createInvite("ORD-test0001");

        assertThat(response.getToken()).contains(".");
        assertThat(response.getExpireAt()).isAfter(LocalDateTime.now().plusDays(6));
        assertThat(order.getInviteTokenHash()).isNotBlank();
        assertThat(order.getInviteExpireAt()).isEqualTo(response.getExpireAt());
    }

    @Test
    void getInviteView_shouldReturnOnlyFinalPriceFields() {
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);
        mockItems();
        InviteTokenResponse invite = inviteService.createInvite("ORD-test0001");
        when(designOrderMapper.selectById("ORD-test0001")).thenReturn(order);

        OrderInviteViewResponse view = inviteService.getInviteView(invite.getToken());

        assertThat(view.getOrderNo()).isEqualTo("DO-20260713-001");
        assertThat(view.getFinalTotalPrice()).isEqualByComparingTo("9999.00");
        assertThat(view.getConfirmed()).isFalse();
        assertThat(view.getItems()).hasSize(1);
        assertThat(view.getItems().get(0).getFinalPrice()).isEqualByComparingTo("2499.75");
        assertThat(view.getItems().get(0).getSubtotal()).isEqualByComparingTo("9999.00");
    }

    @Test
    void getInviteView_forgedSignature_shouldReject() {
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);
        InviteTokenResponse invite = inviteService.createInvite("ORD-test0001");
        String forged = invite.getToken().substring(0, invite.getToken().length() - 2) + "xx";

        assertThatThrownBy(() -> inviteService.getInviteView(forged))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("签名无效");
    }

    @Test
    void getInviteView_expiredToken_shouldReject() {
        OrderInviteService shortLivedService = new OrderInviteService(
            designOrderMapper, designOrderItemMapper, orderService,
            "test-secret-must-be-at-least-32-characters-long", -1);
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);
        InviteTokenResponse invite = shortLivedService.createInvite("ORD-test0001");

        assertThatThrownBy(() -> shortLivedService.getInviteView(invite.getToken()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已过期");
    }

    @Test
    void confirmInvite_shouldTransitionToConfirmedOnce() {
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);
        mockItems();
        InviteTokenResponse invite = inviteService.createInvite("ORD-test0001");
        when(designOrderMapper.selectById("ORD-test0001")).thenReturn(order);

        OrderInviteViewResponse view = inviteService.confirmInvite(invite.getToken());

        assertThat(order.getStatus()).isEqualTo(OrderService.STATUS_CONFIRMED);
        assertThat(view.getConfirmed()).isTrue();
        assertThat(view.getConfirmedAt()).isNotNull();

        assertThatThrownBy(() -> inviteService.confirmInvite(invite.getToken()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不可重复");
    }

    @Test
    void regenerateToken_shouldInvalidateOldToken() {
        DesignOrder order = buildOrder();
        when(orderService.getAccessibleOrder("ORD-test0001")).thenReturn(order);
        InviteTokenResponse first = inviteService.createInvite("ORD-test0001");
        inviteService.createInvite("ORD-test0001");
        when(designOrderMapper.selectById("ORD-test0001")).thenReturn(order);

        assertThatThrownBy(() -> inviteService.getInviteView(first.getToken()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已失效");
    }
}
