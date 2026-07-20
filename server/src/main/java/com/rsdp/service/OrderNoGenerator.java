package com.rsdp.service;

import com.rsdp.mapper.OrderNoCounterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 订单号生成器：DO-yyyyMMdd- + 当日三位序号。
 *
 * <p>基于 {@code order_no_counter} 表原子递增获取序号，避免软删除场景下
 * {@code COUNT+1} 与 {@code design_order.order_no} 唯一索引冲突；同时消除
 * 高并发下多次重试无效的问题。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderNoCounterMapper orderNoCounterMapper;

    /**
     * 生成当日订单号。
     *
     * @return 订单号，如 DO-20260715-001
     */
    public String generate() {
        String datePart = LocalDate.now().format(DATE_FORMAT);
        Long seq = orderNoCounterMapper.allocateSequence(datePart);
        if (seq == null) {
            throw new IllegalStateException("订单号序号分配失败: " + datePart);
        }
        return "DO-" + datePart + "-" + String.format("%03d", seq);
    }
}
