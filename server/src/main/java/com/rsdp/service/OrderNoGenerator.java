package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.DesignOrder;
import com.rsdp.mapper.DesignOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 订单号生成器：DO-yyyyMMdd- + 当日三位序号。
 * 依赖 design_order.order_no 唯一索引兜底，调用方遇到重复可重试。
 */
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DesignOrderMapper designOrderMapper;

    /**
     * 生成当日订单号（基于当日已存在订单数 + 1）。
     *
     * @return 订单号，如 DO-20260715-001
     */
    public String generate() {
        String datePart = LocalDate.now().format(DATE_FORMAT);
        String prefix = "DO-" + datePart + "-";
        Long count = designOrderMapper.selectCount(new QueryWrapper<DesignOrder>()
            .likeRight("order_no", prefix));
        long next = (count != null ? count : 0L) + 1;
        return prefix + String.format("%03d", next);
    }
}
