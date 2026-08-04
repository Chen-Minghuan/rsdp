package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.DictUnresolvedValue;
import com.rsdp.mapper.DictUnresolvedValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 未归一值采集服务。
 *
 * <p>字典解析（尺寸码/颜色码/材质码等）未命中时，原文自动采集计数，
 * 供运营在治理页面渐进归一（写 dict_alias 自学习）。采集失败绝不阻断导入主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictUnresolvedService {

    private final DictUnresolvedValueMapper mapper;

    /**
     * 采集一个未归一值：首次插入，重复出现则计数 +1 并更新最近出现信息。
     *
     * @param dictType 字典类型（size/color/material 等）
     * @param rawValue 未归一的工厂原文
     * @param batchId  导入批次 ID（可为 null）
     * @param username 操作人（可为 null）
     */
    public void record(String dictType, String rawValue, String batchId, String username) {
        if (!StringUtils.hasText(dictType) || !StringUtils.hasText(rawValue)) {
            return;
        }
        String value = rawValue.trim();
        if (value.isEmpty() || value.length() > 128) {
            return; // 超长按非法数据处理，不采集
        }
        try {
            DictUnresolvedValue existing = mapper.selectOne(new QueryWrapper<DictUnresolvedValue>()
                .eq("dict_type", dictType)
                .eq("raw_value", value));
            if (existing != null) {
                existing.setOccurrenceCount(existing.getOccurrenceCount() + 1);
                existing.setLastSeenAt(LocalDateTime.now());
                existing.setLastBatchId(batchId);
                existing.setLastUsername(username);
                // 已归一/忽略的值再次出现时保持原状态，仅计数
                mapper.updateById(existing);
                return;
            }
            DictUnresolvedValue record = new DictUnresolvedValue();
            record.setDictType(dictType);
            record.setRawValue(value);
            record.setOccurrenceCount(1);
            record.setLastBatchId(batchId);
            record.setLastUsername(username);
            record.setStatus("pending");
            mapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发首次插入撞唯一约束：退化为他人的计数，安全忽略
            log.debug("未归一值并发采集撞唯一约束，忽略: {}={}", dictType, value);
        } catch (Exception e) {
            // 采集是辅助链路，任何失败都不能阻断导入主流程
            log.warn("未归一值采集失败: {}={}: {}", dictType, value, e.getMessage());
        }
    }
}
