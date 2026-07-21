package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.OrderCreateRequest;
import com.rsdp.dto.request.OrderUpdateRequest;
import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderItemResponse;
import com.rsdp.dto.response.OrderListResponse;
import com.rsdp.dto.response.OrderResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.DesignOrderItem;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设计订单服务：由方案生成订单（价格快照 × 全局折扣率）、状态机迁移、归属校验。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 待确认。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已确认。 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 生产中。 */
    public static final String STATUS_PRODUCING = "PRODUCING";
    /** 已完成（终态）。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 已取消（终态）。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 状态机：当前状态 → 允许迁移的目标状态。 */
    private static final Map<String, List<String>> STATUS_TRANSITIONS = Map.of(
        STATUS_PENDING, List.of(STATUS_CONFIRMED, STATUS_CANCELLED),
        STATUS_CONFIRMED, List.of(STATUS_PRODUCING, STATUS_CANCELLED),
        STATUS_PRODUCING, List.of(STATUS_COMPLETED)
    );

    private static final int ORDER_NO_MAX_RETRY = 3;

    private final DesignOrderMapper designOrderMapper;
    private final DesignOrderItemMapper designOrderItemMapper;
    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ProjectService projectService;
    private final ConfigService configService;
    private final OrderNoGenerator orderNoGenerator;
    private final DataScopeHelper dataScopeHelper;
    private final ObjectMapper objectMapper;

    /**
     * 由方案生成订单：价格取 RSKU 当前出厂价快照 × 全局折扣率，订单生成后价格不可变。
     *
     * @param request 创建请求
     * @return 订单详情
     */
    @Transactional
    public OrderDetailResponse create(OrderCreateRequest request) {
        Scheme scheme = schemeMapper.selectById(request.getSchemeId());
        if (scheme == null) {
            throw new ResourceNotFoundException("方案不存在: " + request.getSchemeId());
        }
        assertSchemeOwnerOrAdmin(scheme);

        String projectId = null;
        if (StringUtils.hasText(request.getProjectId())) {
            Project project = projectService.getAccessibleProject(request.getProjectId());
            projectId = project.getProjectId();
        }

        List<SchemeItem> schemeItems = schemeItemMapper.selectList(new QueryWrapper<SchemeItem>()
            .eq("scheme_id", scheme.getSchemeId())
            .orderByAsc("sort_order"));
        if (schemeItems.isEmpty()) {
            throw new BusinessException("方案没有方案项，无法生成订单");
        }

        // 数据范围过滤（工厂管理员仅可见自己工厂的项）
        List<SchemeItem> filteredItems = schemeItems.stream()
            .filter(item -> !StringUtils.hasText(item.getFactoryCode())
                || dataScopeHelper.canAccessFactory(item.getFactoryCode()))
            .toList();
        if (filteredItems.isEmpty()) {
            throw new BusinessException("方案项均不在您的数据范围内，无法生成订单");
        }

        BigDecimal priceRate = configService.getOrderPriceRate();

        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        BigDecimal originalTotal = BigDecimal.ZERO;
        BigDecimal finalTotal = BigDecimal.ZERO;
        int maxLeadTime = 0;
        List<DesignOrderItem> items = new java.util.ArrayList<>();

        for (SchemeItem schemeItem : filteredItems) {
            RskuSupply rsku = rskuSupplyMapper.selectById(schemeItem.getRskuId());
            if (rsku == null) {
                throw new BusinessException("方案项 RSKU 已失效: " + schemeItem.getRskuId());
            }
            RspuMaster rspu = rspuMapper.selectById(schemeItem.getRspuId());
            int quantity = schemeItem.getQuantity() != null && schemeItem.getQuantity() > 0
                ? schemeItem.getQuantity()
                : 1;
            BigDecimal originalPrice = rsku.getFactoryPrice() != null ? rsku.getFactoryPrice() : BigDecimal.ZERO;
            BigDecimal finalPrice = originalPrice.multiply(priceRate).setScale(2, RoundingMode.HALF_UP);

            originalTotal = originalTotal.add(originalPrice.multiply(BigDecimal.valueOf(quantity)));
            finalTotal = finalTotal.add(finalPrice.multiply(BigDecimal.valueOf(quantity)));
            if (rsku.getLeadTimeDays() != null && rsku.getLeadTimeDays() > maxLeadTime) {
                maxLeadTime = rsku.getLeadTimeDays();
            }

            DesignOrderItem item = new DesignOrderItem();
            item.setOrderId(orderId);
            item.setRspuId(schemeItem.getRspuId());
            item.setRskuId(schemeItem.getRskuId());
            item.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
            item.setModel(rsku.getFactorySku());
            item.setImageId(primaryImageId(schemeItem.getRspuId()));
            item.setQuantity(quantity);
            item.setOriginalPrice(originalPrice);
            item.setFinalPrice(finalPrice);
            item.setFactoryCode(rsku.getFactoryCode());
            item.setSnapshotJson(buildSnapshotJson(rspu, rsku));
            item.setCreatedAt(LocalDateTime.now());
            items.add(item);
        }

        DesignOrder order = new DesignOrder();
        order.setOrderId(orderId);
        order.setProjectId(projectId);
        order.setSchemeId(scheme.getSchemeId());
        order.setReceiverName(request.getReceiverName());
        order.setReceiverPhone(request.getReceiverPhone());
        order.setReceiverArea(request.getReceiverArea());
        order.setReceiverAddress(request.getReceiverAddress());
        order.setOriginalTotalPrice(originalTotal);
        order.setPriceRate(priceRate);
        order.setFinalTotalPrice(finalTotal);
        order.setItemCount(items.size());
        order.setStatus(STATUS_PENDING);
        order.setExpectedLeadTime(maxLeadTime);
        order.setRemark(request.getRemark());
        order.setCreatedBy(SecurityOperatorContext.currentUserId());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        insertWithOrderNoRetry(order);

        designOrderItemMapper.insertBatchSafe(items);

        return detail(orderId);
    }

    /**
     * 分页查询订单列表（含各状态计数）。
     *
     * @param status 状态筛选（可选）
     * @param page   页码
     * @param size   每页条数
     * @return 分页与状态计数
     */
    public OrderListResponse list(String status, long page, long size) {
        QueryWrapper<DesignOrder> scope = orderScope();
        if (StringUtils.hasText(status)) {
            scope.eq("status", status);
        }
        scope.orderByDesc("created_at");
        Page<DesignOrder> result = designOrderMapper.selectPage(Page.of(page, size), scope);

        Map<String, Long> statusCounts = new HashMap<>();
        designOrderMapper.selectMaps(orderScope()
                .select("status", "COUNT(*) AS cnt")
                .groupBy("status"))
            .forEach(row -> statusCounts.put((String) row.get("status"), ((Number) row.get("cnt")).longValue()));

        OrderListResponse response = new OrderListResponse();
        response.setTotal(result.getTotal());
        response.setPage(page);
        response.setRows(result.getRecords().stream().map(this::toResponse).toList());
        response.setStatusCounts(statusCounts);
        return response;
    }

    /**
     * 查询订单详情（含明细快照）。
     *
     * @param orderId 订单 ID
     * @return 订单详情
     */
    public OrderDetailResponse detail(String orderId) {
        DesignOrder order = getAccessibleOrder(orderId);
        List<DesignOrderItem> items = designOrderItemMapper.selectList(
            new QueryWrapper<DesignOrderItem>().eq("order_id", orderId).orderByAsc("id"));

        OrderDetailResponse response = new OrderDetailResponse();
        copyBaseFields(toResponse(order), response);
        response.setItems(items.stream().map(this::toItemResponse).toList());
        return response;
    }

    /**
     * 更新订单收件信息与备注（仅 PENDING 可改）。
     *
     * @param orderId 订单 ID
     * @param request 更新请求
     * @return 更新后的订单
     */
    @Transactional
    public OrderResponse update(String orderId, OrderUpdateRequest request) {
        DesignOrder order = getAccessibleOrder(orderId);
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException("仅待确认状态的订单可以修改");
        }
        if (request.getReceiverName() != null) {
            order.setReceiverName(StringUtils.hasText(request.getReceiverName()) ? request.getReceiverName() : null);
        }
        if (request.getReceiverPhone() != null) {
            order.setReceiverPhone(StringUtils.hasText(request.getReceiverPhone()) ? request.getReceiverPhone() : null);
        }
        if (request.getReceiverArea() != null) {
            order.setReceiverArea(StringUtils.hasText(request.getReceiverArea()) ? request.getReceiverArea() : null);
        }
        if (request.getReceiverAddress() != null) {
            order.setReceiverAddress(StringUtils.hasText(request.getReceiverAddress()) ? request.getReceiverAddress() : null);
        }
        if (request.getRemark() != null) {
            order.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark() : null);
        }
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.updateById(order);
        return toResponse(order);
    }

    /**
     * 订单状态迁移（状态机校验）。
     *
     * @param orderId     订单 ID
     * @param targetStatus 目标状态
     * @return 更新后的订单
     */
    @Transactional
    public OrderResponse updateStatus(String orderId, String targetStatus) {
        DesignOrder order = getAccessibleOrder(orderId);
        List<String> allowed = STATUS_TRANSITIONS.getOrDefault(order.getStatus(), List.of());
        if (!allowed.contains(targetStatus)) {
            throw new BusinessException(
                "订单状态不允许从「" + order.getStatus() + "」变更为「" + targetStatus + "」");
        }
        order.setStatus(targetStatus);
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.updateById(order);
        return toResponse(order);
    }

    /**
     * 校验订单存在且当前用户可访问（创建人或 ADMIN）。
     *
     * @param orderId 订单 ID
     * @return 订单实体
     */
    public DesignOrder getAccessibleOrder(String orderId) {
        DesignOrder order = designOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("订单不存在: " + orderId);
        }
        if (!SecurityOperatorContext.isCurrentUserAdmin()
            && !order.getCreatedBy().equals(SecurityOperatorContext.currentUserId())) {
            throw new ForbiddenException("无权访问该订单: " + orderId);
        }
        return order;
    }

    private void assertSchemeOwnerOrAdmin(Scheme scheme) {
        if (!SecurityOperatorContext.isCurrentUserAdmin()
            && !SecurityOperatorContext.currentUsername().equals(scheme.getCreatedBy())) {
            throw new ForbiddenException("无权基于该方案创建订单: " + scheme.getSchemeId());
        }
    }

    private QueryWrapper<DesignOrder> orderScope() {
        QueryWrapper<DesignOrder> wrapper = new QueryWrapper<>();
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUserId());
        }
        return wrapper;
    }

    /**
     * 写入订单并处理订单号并发冲突（唯一索引兜底，最多重试 3 次）。
     */
    private void insertWithOrderNoRetry(DesignOrder order) {
        for (int attempt = 1; attempt <= ORDER_NO_MAX_RETRY; attempt++) {
            try {
                order.setOrderNo(orderNoGenerator.generate());
                designOrderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == ORDER_NO_MAX_RETRY) {
                    throw new BusinessException("订单号生成冲突，请重试");
                }
            }
        }
    }

    private String primaryImageId(String rspuId) {
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .eq("rspu_id", rspuId)
            .eq("is_primary", true)
            .last("LIMIT 1"));
        return images.isEmpty() ? null : images.get(0).getImageId();
    }

    private String buildSnapshotJson(RspuMaster rspu, RskuSupply rsku) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            if (rspu != null) {
                snapshot.put("positioningLabel", rspu.getPositioningLabel());
                snapshot.put("colorPrimaryName", rspu.getColorPrimaryName());
                snapshot.put("materialTags", rspu.getMaterialTags());
                snapshot.put("sceneTags", rspu.getSceneTags());
                snapshot.put("keySpecs", rspu.getKeySpecs());
            }
            snapshot.put("factorySku", rsku.getFactorySku());
            snapshot.put("materialCode", rsku.getMaterialCode());
            snapshot.put("leadTimeDays", rsku.getLeadTimeDays());
            snapshot.put("moq", rsku.getMoq());
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BusinessException("商品快照序列化失败: " + e.getMessage());
        }
    }

    private OrderResponse toResponse(DesignOrder order) {
        OrderResponse response = new OrderResponse();
        copyBaseFields(response, order);
        return response;
    }

    private void copyBaseFields(OrderResponse response, DesignOrder order) {
        response.setOrderId(order.getOrderId());
        response.setOrderNo(order.getOrderNo());
        response.setProjectId(order.getProjectId());
        response.setSchemeId(order.getSchemeId());
        response.setReceiverName(order.getReceiverName());
        response.setReceiverPhone(order.getReceiverPhone());
        response.setReceiverArea(order.getReceiverArea());
        response.setReceiverAddress(order.getReceiverAddress());
        response.setOriginalTotalPrice(order.getOriginalTotalPrice());
        response.setPriceRate(order.getPriceRate());
        response.setFinalTotalPrice(order.getFinalTotalPrice());
        response.setItemCount(order.getItemCount());
        response.setStatus(order.getStatus());
        response.setExpectedLeadTime(order.getExpectedLeadTime());
        response.setRemark(order.getRemark());
        response.setInviteExpireAt(order.getInviteExpireAt());
        response.setInviteConfirmedAt(order.getInviteConfirmedAt());
        response.setCreatedBy(order.getCreatedBy());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
    }

    private void copyBaseFields(OrderResponse source, OrderDetailResponse target) {
        target.setOrderId(source.getOrderId());
        target.setOrderNo(source.getOrderNo());
        target.setProjectId(source.getProjectId());
        target.setSchemeId(source.getSchemeId());
        target.setReceiverName(source.getReceiverName());
        target.setReceiverPhone(source.getReceiverPhone());
        target.setReceiverArea(source.getReceiverArea());
        target.setReceiverAddress(source.getReceiverAddress());
        target.setOriginalTotalPrice(source.getOriginalTotalPrice());
        target.setPriceRate(source.getPriceRate());
        target.setFinalTotalPrice(source.getFinalTotalPrice());
        target.setItemCount(source.getItemCount());
        target.setStatus(source.getStatus());
        target.setExpectedLeadTime(source.getExpectedLeadTime());
        target.setRemark(source.getRemark());
        target.setInviteExpireAt(source.getInviteExpireAt());
        target.setInviteConfirmedAt(source.getInviteConfirmedAt());
        target.setCreatedBy(source.getCreatedBy());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private OrderItemResponse toItemResponse(DesignOrderItem item) {
        OrderItemResponse response = new OrderItemResponse();
        response.setId(item.getId());
        response.setRspuId(item.getRspuId());
        response.setRskuId(item.getRskuId());
        response.setProductName(item.getProductName());
        response.setModel(item.getModel());
        response.setImageId(item.getImageId());
        response.setQuantity(item.getQuantity());
        response.setOriginalPrice(item.getOriginalPrice());
        response.setFinalPrice(item.getFinalPrice());
        response.setFactoryCode(item.getFactoryCode());
        int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
        response.setSubtotal(item.getFinalPrice() != null
            ? item.getFinalPrice().multiply(BigDecimal.valueOf(quantity))
            : null);
        return response;
    }
}
