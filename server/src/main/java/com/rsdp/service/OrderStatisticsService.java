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

    private final DesignOrderMapper designOrderMapper;
    private final DesignOrderItemMapper designOrderItemMapper;
    private final FactoryMasterMapper factoryMasterMapper;

    /**
     * 订单统计入口：按维度分发。
     *
     * @param dim  统计维度（product / factory）
     * @param from 起始日期（含，可空）
     * @param to   截止日期（含，可空）
     * @return 对应维度的统计列表（按总金额降序）
     */
    public List<?> statistics(String dim, LocalDate from, LocalDate to) {
        if (DIM_PRODUCT.equals(dim)) {
            return statByProduct(from, to);
        }
        if (DIM_FACTORY.equals(dim)) {
            return statByFactory(from, to);
        }
        throw new BusinessException("不支持的统计维度: " + dim + "（仅支持 product / factory）");
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
        QueryWrapper<DesignOrder> wrapper = new QueryWrapper<>();
        wrapper.ne("status", OrderService.STATUS_CANCELLED);
        if (from != null) {
            wrapper.ge("created_at", from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt("created_at", to.plusDays(1).atStartOfDay());
        }
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUserId());
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
