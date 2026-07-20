package com.rsdp.service;

import com.rsdp.entity.SysConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 系统配置服务。
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    /** 订单全局折扣率配置键。 */
    public static final String ORDER_PRICE_RATE_KEY = "order.price_rate";

    private final SysConfigMapper sysConfigMapper;

    /**
     * 读取订单全局折扣率（缺省为 1）。
     *
     * @return 折扣率
     */
    public BigDecimal getOrderPriceRate() {
        SysConfig config = sysConfigMapper.selectById(ORDER_PRICE_RATE_KEY);
        if (config == null || !StringUtils.hasText(config.getConfigValue())) {
            return BigDecimal.ONE;
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(config.getConfigValue().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("订单折扣率配置格式错误: " + config.getConfigValue());
        }
        validateOrderPriceRate(rate);
        return rate;
    }

    /**
     * 校验订单全局折扣率是否在合法范围 [0, 1] 内。
     *
     * @param rate 折扣率
     */
    public static void validateOrderPriceRate(BigDecimal rate) {
        if (rate == null) {
            throw new BusinessException("订单折扣率不能为空");
        }
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("订单折扣率必须在 0 到 1 之间（含），当前值: " + rate);
        }
    }

    /**
     * 读取配置值。
     *
     * @param key 配置键
     * @return 配置
     */
    public SysConfig get(String key) {
        SysConfig config = sysConfigMapper.selectById(key);
        if (config == null) {
            throw new ResourceNotFoundException("配置不存在: " + key);
        }
        return config;
    }

    /**
     * 更新配置值（不存在则创建）。
     *
     * @param key   配置键
     * @param value 配置值
     * @return 更新后的配置
     */
    @Transactional
    public SysConfig set(String key, String value) {
        if (ORDER_PRICE_RATE_KEY.equals(key)) {
            BigDecimal rate;
            try {
                rate = new BigDecimal(value.trim());
            } catch (NumberFormatException | NullPointerException e) {
                throw new BusinessException("订单折扣率配置格式错误: " + value);
            }
            validateOrderPriceRate(rate);
        }
        SysConfig config = sysConfigMapper.selectById(key);
        if (config == null) {
            config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setUpdatedAt(LocalDateTime.now());
            sysConfigMapper.insert(config);
        } else {
            config.setConfigValue(value);
            config.setUpdatedAt(LocalDateTime.now());
            sysConfigMapper.updateById(config);
        }
        return config;
    }
}
