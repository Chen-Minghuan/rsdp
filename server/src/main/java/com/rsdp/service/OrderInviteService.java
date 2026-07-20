package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.InviteTokenResponse;
import com.rsdp.dto.response.OrderInviteItemResponse;
import com.rsdp.dto.response.OrderInviteViewResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.DesignOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 订单邀请服务：生成 HMAC-SHA256 签名的一次性邀请链接，
 * 客户端免登录查看订单到手价视图并确认订单。
 * 安全约束：token 只存 SHA-256 哈希，公开视图绝不泄露出厂价与工厂信息。
 */
@Slf4j
@Service
public class OrderInviteService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_LENGTH = 32;

    private final DesignOrderMapper designOrderMapper;
    private final DesignOrderItemMapper designOrderItemMapper;
    private final OrderService orderService;
    private final byte[] secretKey;
    private final int inviteExpireDays;

    public OrderInviteService(DesignOrderMapper designOrderMapper,
                              DesignOrderItemMapper designOrderItemMapper,
                              OrderService orderService,
                              @Value("${rsdp.order.invite-secret:}") String configuredSecret,
                              @Value("${rsdp.order.invite-expire-days:7}") int inviteExpireDays) {
        this.designOrderMapper = designOrderMapper;
        this.designOrderItemMapper = designOrderItemMapper;
        this.orderService = orderService;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalArgumentException(
                "rsdp.order.invite-secret 未配置，必须通过 RSDP_ORDER_INVITE_SECRET 环境变量设置长度 >= "
                    + MIN_SECRET_LENGTH + " 的密钥");
        }
        if (configuredSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                "rsdp.order.invite-secret 长度必须 >= " + MIN_SECRET_LENGTH + " 位");
        }
        this.secretKey = configuredSecret.getBytes(StandardCharsets.UTF_8);
        this.inviteExpireDays = inviteExpireDays;
    }

    /**
     * 生成邀请链接 token。重新生成会使旧链接立即失效（哈希覆盖）。
     *
     * @param orderId 订单 ID
     * @return token 与过期时间
     */
    @Transactional
    public InviteTokenResponse createInvite(String orderId) {
        DesignOrder order = orderService.getAccessibleOrder(orderId);

        long expireAtEpoch = Instant.now().plusSeconds(inviteExpireDays * 24L * 3600L).getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String payload = orderId + "." + expireAtEpoch + "." + nonce;
        String token = base64Url(payload.getBytes(StandardCharsets.UTF_8))
            + "." + base64Url(hmac(payload));

        LocalDateTime expireAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(expireAtEpoch), ZoneId.systemDefault());
        order.setInviteTokenHash(sha256Hex(token));
        order.setInviteExpireAt(expireAt);
        order.setInviteConfirmedAt(null);
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.updateById(order);
        return new InviteTokenResponse(token, expireAt);
    }

    /**
     * 免登录查看邀请页订单视图（仅到手价）。
     *
     * @param token 邀请 token
     * @return 订单公开视图
     */
    public OrderInviteViewResponse getInviteView(String token) {
        DesignOrder order = verifyAndLoad(token);
        return buildView(order);
    }

    /**
     * 免登录确认订单：PENDING → CONFIRMED，链接确认一次后即不可重复确认。
     *
     * @param token 邀请 token
     * @return 确认后的订单公开视图
     */
    @Transactional
    public OrderInviteViewResponse confirmInvite(String token) {
        DesignOrder order = verifyAndLoad(token);
        if (order.getInviteConfirmedAt() != null) {
            throw new BusinessException("该邀请链接已确认，不可重复操作");
        }
        if (!OrderService.STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException("订单当前状态不可确认");
        }
        order.setStatus(OrderService.STATUS_CONFIRMED);
        order.setInviteConfirmedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.updateById(order);
        return buildView(order);
    }

    /**
     * 仅校验 token 签名与过期时间，返回订单 ID。
     *
     * <p>用于公开资源访问前的快速授权判断，不校验数据库中的 token 哈希状态。</p>
     *
     * @param token 邀请 token
     * @return 订单 ID
     */
    public String resolveOrderIdFromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("邀请链接无效");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException("邀请链接无效");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("邀请链接无效");
        }
        String[] seg = payload.split("\\.");
        if (seg.length != 3) {
            throw new BusinessException("邀请链接无效");
        }
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("邀请链接无效");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException("邀请链接签名无效");
        }
        long expireAtEpoch;
        try {
            expireAtEpoch = Long.parseLong(seg[1]);
        } catch (NumberFormatException e) {
            throw new BusinessException("邀请链接无效");
        }
        if (Instant.now().getEpochSecond() > expireAtEpoch) {
            throw new BusinessException("邀请链接已过期");
        }
        return seg[0];
    }

    /**
     * 校验 token 签名、过期时间、库存哈希，返回订单。
     *
     * @param token 邀请 token
     * @return 订单实体
     */
    private DesignOrder verifyAndLoad(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("邀请链接无效");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException("邀请链接无效");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("邀请链接无效");
        }
        String[] seg = payload.split("\\.");
        if (seg.length != 3) {
            throw new BusinessException("邀请链接无效");
        }
        // 签名常量时间比对，防时序攻击
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("邀请链接无效");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException("邀请链接签名无效");
        }
        long expireAtEpoch;
        try {
            expireAtEpoch = Long.parseLong(seg[1]);
        } catch (NumberFormatException e) {
            throw new BusinessException("邀请链接无效");
        }
        if (Instant.now().getEpochSecond() > expireAtEpoch) {
            throw new BusinessException("邀请链接已过期，请联系设计师重新生成");
        }
        DesignOrder order = designOrderMapper.selectById(seg[0]);
        if (order == null) {
            throw new BusinessException("邀请链接无效");
        }
        if (order.getInviteTokenHash() == null || !sha256Hex(token).equals(order.getInviteTokenHash())) {
            throw new BusinessException("邀请链接已失效，请联系设计师重新生成");
        }
        return order;
    }

    /**
     * 组装公开视图：只含到手价，绝不携带 originalPrice/factoryCode/rskuId。
     */
    private OrderInviteViewResponse buildView(DesignOrder order) {
        List<DesignOrderItem> items = designOrderItemMapper.selectList(new QueryWrapper<DesignOrderItem>()
            .eq("order_id", order.getOrderId())
            .orderByAsc("id"));
        List<OrderInviteItemResponse> itemResponses = items.stream().map(item -> {
            OrderInviteItemResponse r = new OrderInviteItemResponse();
            r.setProductName(item.getProductName());
            r.setModel(item.getModel());
            r.setImageId(item.getImageId());
            r.setQuantity(item.getQuantity());
            r.setFinalPrice(item.getFinalPrice());
            if (item.getFinalPrice() != null && item.getQuantity() != null) {
                r.setSubtotal(item.getFinalPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            return r;
        }).toList();

        OrderInviteViewResponse view = new OrderInviteViewResponse();
        view.setOrderNo(order.getOrderNo());
        view.setStatus(order.getStatus());
        view.setReceiverArea(order.getReceiverArea());
        view.setFinalTotalPrice(order.getFinalTotalPrice());
        view.setItemCount(order.getItemCount());
        view.setExpectedLeadTime(order.getExpectedLeadTime());
        view.setExpireAt(order.getInviteExpireAt());
        view.setConfirmed(order.getInviteConfirmedAt() != null);
        view.setConfirmedAt(order.getInviteConfirmedAt());
        view.setItems(itemResponses);
        return view;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
