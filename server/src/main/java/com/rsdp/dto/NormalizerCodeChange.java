package com.rsdp.dto;

import lombok.Value;

/**
 * Normalizer 升级 Dry Run 中单条「旧值 → 新值」变化记录（设计文档 v3.3 §7 第一步）。
 */
@Value
public class NormalizerCodeChange {

    String factoryCode;
    String sourceNamespace;
    /** 源商品编码原文（不变）。 */
    String sourceProductCode;
    /** 旧归一值。 */
    String oldNormalizedCode;
    /** 按目标版本计算的新归一值。 */
    String newNormalizedCode;
    /** 旧规则集版本（字符串形式，与列类型一致）。 */
    String oldNormalizerVersion;
    /** 目标规则集版本。 */
    int targetNormalizerVersion;
}
