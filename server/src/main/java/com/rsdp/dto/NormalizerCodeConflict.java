package com.rsdp.dto;

import lombok.Value;

import java.util.List;

/**
 * Normalizer 升级 Dry Run 检出的唯一键冲突（设计文档 v3.3 §7 第二步）。
 *
 * <p>按目标版本归一后，同一 {@code (factory_code, source_namespace, newNormalizedCode)}
 * 将对应多于一个不同的源编码原文，迁移会撞唯一键
 * {@code uk_supplier_product_ref}。此类冲突必须进入人工治理，
 * 不允许自动合并或改绑 {@code rspu_id}；冲突未治理前不得切换版本。</p>
 */
@Value
public class NormalizerCodeConflict {

    String factoryCode;
    String sourceNamespace;
    /** 按目标版本归一后发生撞码的值。 */
    String newNormalizedCode;
    /** 撞码涉及的全部不同源编码原文。 */
    List<String> conflictingSourceCodes;
}
