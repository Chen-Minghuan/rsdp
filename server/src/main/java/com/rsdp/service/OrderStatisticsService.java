package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.OrderFactoryStatResponse;
import com.rsdp.dto.response.OrderInviterStatResponse;
import com.rsdp.dto.response.OrderProductStatResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单统计服务：基于 design_order / design_order_item 的只读聚合。
 * 排除已取消订单；非 ADMIN 仅统计自己创建的订单。
 * 注意：到手价为 AES 加密列，无法 SQL 聚合，统一实体级解密后内存聚合；
 * 订单项数据量显著增长后可评估改为物化视图或解密 UDF。
 */
@Service
@RequiredArgsConstructor
public class OrderStatisticsService {

    /** 统计维度：按产品。 */
    public static final String DIM_PRODUCT = "product";
    /** 统计维度：按工厂。 */
    public static final String DIM_FACTORY = "factory";
    /** 统计维度：按邀请人。 */
    public static final String DIM_INVITER = "inviter";

    private final DesignOrderMapper designOrderMapper;
    private final DesignOrderItemMapper designOrderItemMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final SysUserMapper sysUserMapper;

    private static final int DEFAULT_STAT_DAYS = 90;
    private static final int MAX_STAT_DAYS = 365;
    private static final int RESULT_TOP_LIMIT = 50;

    /**
     * 订单统计入口：按维度分发。
     *
     * @param dim  统计维度（product / factory / inviter）
     * @param from 起始日期（含，可空；空时默认最近 90 天）
     * @param to   截止日期（含，可空；空时默认今天）
     * @return 对应维度的统计列表（按总金额降序）
     */
    public List<?> statistics(String dim, LocalDate from, LocalDate to) {
        LocalDate[] range = resolveDateRange(from, to);
        if (DIM_PRODUCT.equals(dim)) {
            return statByProduct(range[0], range[1]);
        }
        if (DIM_FACTORY.equals(dim)) {
            return statByFactory(range[0], range[1]);
        }
        if (DIM_INVITER.equals(dim)) {
            return statByInviter(range[0], range[1]);
        }
        throw new BusinessException("不支持的统计维度: " + dim + "（仅支持 product / factory / inviter）");
    }

    /**
     * 邀请维度统计：按邀请人聚合「邀请成功人数（有订单的被邀请人去重）/订单数/支付金额」，
     * 行展开被邀请人明细。非 ADMIN 仅统计「我邀请的人」产生的订单。
     *
     * @param from 起始日期（含）
     * @param to   截止日期（含）
     * @return 邀请人统计列表（按支付金额降序）
     */
    public List<OrderInviterStatResponse> statByInviter(LocalDate from, LocalDate to) {
        // 非 ADMIN：先把「我邀请的人」筛出来，订单范围限定到这些创建人
        QueryWrapper<DesignOrder> wrapper = baseScope(from, to);
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            String currentUserId = SecurityOperatorContext.currentUserId();
            List<String> inviteeIds = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                    .eq("invited_by", currentUserId))
                .stream().map(SysUser::getUserId).toList();
            if (inviteeIds.isEmpty()) {
                return List.of();
            }
            wrapper.in("created_by", inviteeIds);
        }
        List<DesignOrder> orders = designOrderMapper.selectList(wrapper);
        if (orders.isEmpty()) {
            return List.of();
        }

        // 批量加载订单创建人，拿到邀请归因（invited_by）
        List<String> creatorIds = orders.stream().map(DesignOrder::getCreatedBy).distinct().toList();
        Map<String, SysUser> creatorMap = sysUserMapper.selectBatchIds(creatorIds).stream()
            .collect(Collectors.toMap(SysUser::getUserId, u -> u, (a, b) -> a));

        // 按邀请人聚合
        Map<String, InviterAgg> byInviter = new LinkedHashMap<>();
        for (DesignOrder order : orders) {
            SysUser creator = creatorMap.get(order.getCreatedBy());
            if (creator == null || creator.getInvitedBy() == null) {
                continue;
            }
            InviterAgg agg = byInviter.computeIfAbsent(creator.getInvitedBy(), k -> new InviterAgg());
            OrderInviterStatResponse.InviteeStat invitee = agg.invitees.computeIfAbsent(
                creator.getUserId(), userId -> {
                    OrderInviterStatResponse.InviteeStat stat = new OrderInviterStatResponse.InviteeStat();
                    stat.setUserId(userId);
                    stat.setUsername(creator.getUsername());
                    stat.setNickname(creator.getNickname());
                    stat.setOrderCount(0L);
                    stat.setTotalAmount(BigDecimal.ZERO);
                    return stat;
                });
            BigDecimal amount = order.getFinalTotalPrice() != null ? order.getFinalTotalPrice() : BigDecimal.ZERO;
            invitee.setOrderCount(invitee.getOrderCount() + 1);
            invitee.setTotalAmount(invitee.getTotalAmount().add(amount));
            agg.orderCount++;
            agg.totalAmount = agg.totalAmount.add(amount);
        }
        if (byInviter.isEmpty()) {
            return List.of();
        }

        // 批量加载邀请人信息
        Map<String, SysUser> inviterMap = sysUserMapper.selectBatchIds(byInviter.keySet()).stream()
            .collect(Collectors.toMap(SysUser::getUserId, u -> u, (a, b) -> a));

        return byInviter.entrySet().stream()
            .sorted(Map.Entry.<String, InviterAgg>comparingByValue(
                Comparator.comparing(agg -> agg.totalAmount)).reversed())
            .limit(RESULT_TOP_LIMIT)
            .map(entry -> {
                InviterAgg agg = entry.getValue();
                SysUser inviter = inviterMap.get(entry.getKey());
                OrderInviterStatResponse response = new OrderInviterStatResponse();
                response.setInviterId(entry.getKey());
                response.setInviterUsername(inviter != null ? inviter.getUsername() : null);
                response.setInviterNickname(inviter != null ? inviter.getNickname() : null);
                response.setInviteSuccessCount((long) agg.invitees.size());
                response.setOrderCount(agg.orderCount);
                response.setTotalAmount(scale(agg.totalAmount));
                response.setInvitees(agg.invitees.values().stream()
                    .sorted(Comparator.comparing(OrderInviterStatResponse.InviteeStat::getTotalAmount).reversed())
                    .peek(stat -> stat.setTotalAmount(scale(stat.getTotalAmount())))
                    .toList());
                return response;
            })
            .toList();
    }

    /** 邀请人聚合中间态。 */
    private static final class InviterAgg {
        private final Map<String, OrderInviterStatResponse.InviteeStat> invitees = new LinkedHashMap<>();
        private long orderCount = 0L;
        private BigDecimal totalAmount = BigDecimal.ZERO;
    }

    /**
     * 解析并校验统计时间窗。
     *
     * @param from 起始日期（可空）
     * @param to   截止日期（可空）
     * @return [from, to]
     */
    private LocalDate[] resolveDateRange(LocalDate from, LocalDate to) {
        LocalDate now = LocalDate.now();
        LocalDate effectiveTo = to != null ? to : now;
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_STAT_DAYS - 1L);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BusinessException("起始日期不能晚于截止日期");
        }
        if (effectiveFrom.plusDays(MAX_STAT_DAYS).isBefore(effectiveTo.plusDays(1))) {
            throw new BusinessException("统计时间窗不能超过 " + MAX_STAT_DAYS + " 天");
        }
        return new LocalDate[] { effectiveFrom, effectiveTo };
    }

    /**
     * 产品维度统计：按 RSPU 聚合总件数与总到手金额，商品名/图取订单明细快照。
     *
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 产品统计列表（按总金额降序）
     */
    public List<OrderProductStatResponse> statByProduct(LocalDate from, LocalDate to) {
        Map<String, OrderProductStatResponse> byRspu = new LinkedHashMap<>();
        for (DesignOrderItem item : listScopedItems(from, to)) {
            if (item.getRspuId() == null) {
                continue;
            }
            OrderProductStatResponse stat = byRspu.computeIfAbsent(item.getRspuId(), rspuId -> {
                OrderProductStatResponse s = new OrderProductStatResponse();
                s.setRspuId(rspuId);
                s.setProductName(item.getProductName());
                s.setImageId(item.getImageId());
                s.setTotalQuantity(0L);
                s.setTotalAmount(BigDecimal.ZERO);
                return s;
            });
            int quantity = quantityOf(item);
            stat.setTotalQuantity(stat.getTotalQuantity() + quantity);
            stat.setTotalAmount(stat.getTotalAmount().add(subtotalOf(item, quantity)));
        }
        return byRspu.values().stream()
            .sorted(Comparator.comparing(OrderProductStatResponse::getTotalAmount).reversed())
            .limit(RESULT_TOP_LIMIT)
            .peek(s -> s.setTotalAmount(scale(s.getTotalAmount())))
            .toList();
    }

    /**
     * 工厂维度统计：按工厂编码聚合订单数（distinct）、总件数与总到手金额，批量补工厂名。
     *
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 工厂统计列表（按总金额降序）
     */
    public List<OrderFactoryStatResponse> statByFactory(LocalDate from, LocalDate to) {
        Map<String, Long> quantityByFactory = new HashMap<>();
        Map<String, BigDecimal> amountByFactory = new HashMap<>();
        Map<String, Set<String>> ordersByFactory = new HashMap<>();
        for (DesignOrderItem item : listScopedItems(from, to)) {
            if (item.getFactoryCode() == null) {
                continue;
            }
            int quantity = quantityOf(item);
            quantityByFactory.merge(item.getFactoryCode(), (long) quantity, Long::sum);
            amountByFactory.merge(item.getFactoryCode(), subtotalOf(item, quantity), BigDecimal::add);
            ordersByFactory.computeIfAbsent(item.getFactoryCode(), k -> new HashSet<>()).add(item.getOrderId());
        }
        if (amountByFactory.isEmpty()) {
            return List.of();
        }

        Map<String, String> factoryNames = factoryMasterMapper.selectList(
                new QueryWrapper<FactoryMaster>().in("factory_code", amountByFactory.keySet()))
            .stream()
            .collect(Collectors.toMap(FactoryMaster::getFactoryCode, FactoryMaster::getFactoryName, (a, b) -> a));

        return amountByFactory.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(RESULT_TOP_LIMIT)
            .map(entry -> {
                OrderFactoryStatResponse stat = new OrderFactoryStatResponse();
                stat.setFactoryCode(entry.getKey());
                stat.setFactoryName(factoryNames.getOrDefault(entry.getKey(), entry.getKey()));
                stat.setOrderCount((long) ordersByFactory.get(entry.getKey()).size());
                stat.setTotalQuantity(quantityByFactory.getOrDefault(entry.getKey(), 0L));
                stat.setTotalAmount(scale(entry.getValue()));
                return stat;
            })
            .toList();
    }

    /**
     * 查询当前用户可见范围内（排除已取消 + 时间过滤 + 归属隔离）的订单明细。
     *
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 订单明细列表（到手价已实体级解密）
     */
    private List<DesignOrderItem> listScopedItems(LocalDate from, LocalDate to) {
        List<DesignOrder> orders = designOrderMapper.selectList(orderScope(from, to).select("order_id"));
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = orders.stream().map(DesignOrder::getOrderId).toList();
        return designOrderItemMapper.selectList(
            new QueryWrapper<DesignOrderItem>().in("order_id", orderIds));
    }

    /**
     * 订单统计范围：排除已取消；from 取当日 00:00:00，to 取次日 00:00:00（不含）；
     * 非 ADMIN 仅自己创建的订单（design_order.created_by 存 user_id）。
     *
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 订单查询条件
     */
    private QueryWrapper<DesignOrder> orderScope(LocalDate from, LocalDate to) {
        QueryWrapper<DesignOrder> wrapper = baseScope(from, to);
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUserId());
        }
        return wrapper;
    }

    /**
     * 订单统计基础范围：排除已取消 + 时间窗过滤（不含创建人限制）。
     *
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 订单查询条件
     */
    private QueryWrapper<DesignOrder> baseScope(LocalDate from, LocalDate to) {
        QueryWrapper<DesignOrder> wrapper = new QueryWrapper<>();
        wrapper.ne("status", OrderService.STATUS_CANCELLED);
        if (from != null) {
            wrapper.ge("created_at", from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt("created_at", to.plusDays(1).atStartOfDay());
        }
        return wrapper;
    }

    private int quantityOf(DesignOrderItem item) {
        return item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
    }

    private BigDecimal subtotalOf(DesignOrderItem item, int quantity) {
        BigDecimal price = item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO;
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
