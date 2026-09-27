package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuFactoryMapping;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RspuFactoryMappingMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.SupplierProductRefMapper;
import com.rsdp.util.SourceCodeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商源商品身份统一解析入口（设计文档 v3.3 §9 Phase 3 Switch Read）。
 *
 * <p>所有「以供应商源商品编码解析 RSPU 身份」的读路径必须经本服务，解析顺序：</p>
 * <ol>
 *   <li><b>supplier_product_ref 优先</b>：按身份键
 *       {@code (factory_code, source_namespace, normalized code)} 命中
 *       （{@link SupplierProductRefService#findByIdentity}，归一语义见 §7）；</li>
 *   <li><b>受限 legacy fallback</b>：仅当同时满足以下两个条件时才允许回落
 *       {@code rspu_master.external_code}：
 *       <ul>
 *         <li><b>factory context 能验证</b>：调用方提供 factory_code 且该工厂在
 *             {@code factory_master} 真实存在；</li>
 *         <li><b>明确无歧义</b>：{@code external_code} 命中的候选 RSPU 中，恰好一个
 *             与该工厂存在 active 的 {@code rspu_factory_mapping} 供应关系。
 *             0 个（无法验证归属）或 &gt;1 个（歧义）一律返回未命中，绝不猜测，
 *             歧义情况记 warn 日志转人工治理（§77.1「legacy fallback 歧义」）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>禁止裸 fallback</b>：本服务不提供「无 factory context 的全局 external_code 查询」入口——
 * {@code factoryCode} 为空时直接返回未命中（ref 身份键本身含 factory_code，同样无法解析），
 * 代码上不存在绕过工厂上下文解析身份的途径（§9 Phase 3）。</p>
 *
 * <p>legacy fallback 的编码匹配沿用历史精确语义（trim 后等值 {@code external_code}），
 * 与 Phase 3 之前的读行为一致；归一化匹配只发生在 supplier_product_ref 路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierIdentityResolutionService {

    /** 身份解析来源。 */
    public enum ResolutionSource {
        /** 命中 supplier_product_ref（首选身份源）。 */
        SUPPLIER_PRODUCT_REF,
        /** 受限 legacy fallback 命中 rspu_master.external_code（历史兼容投影，§8）。 */
        LEGACY_EXTERNAL_CODE
    }

    /**
     * 身份解析结果。
     */
    @Value
    public static class ResolvedIdentity {
        /** 命中的 RSPU ID。 */
        String rspuId;
        /** 解析来源。 */
        ResolutionSource source;
        /** 命中 Supplier Ref 时的 ref_id；legacy fallback 命中为 null。 */
        Long refId;
    }

    private final SupplierProductRefService supplierProductRefService;
    private final SupplierProductRefMapper supplierProductRefMapper;
    private final RspuMapper rspuMapper;
    private final RspuFactoryMappingMapper rspuFactoryMappingMapper;
    private final FactoryMasterMapper factoryMasterMapper;

    /**
     * 解析单个供应商源商品编码对应的 RSPU 身份。
     *
     * @param factoryCode 工厂上下文（空 → 直接未命中；不提供无工厂上下文的解析入口）
     * @param sourceNamespace 编码命名空间（空 → {@code default}，§6.1）
     * @param rawSourceProductCode 源商品编码原文
     * @return 命中的身份；未命中/不允许回落时返回 {@link Optional#empty()}
     */
    public Optional<ResolvedIdentity> resolve(String factoryCode,
                                              String sourceNamespace,
                                              String rawSourceProductCode) {
        if (!StringUtils.hasText(rawSourceProductCode) || !StringUtils.hasText(factoryCode)) {
            // 无工厂上下文：禁止裸 fallback（§9 Phase 3），ref 身份键也含 factory_code，直接未命中
            return Optional.empty();
        }
        String factory = factoryCode.trim();

        // 1. supplier_product_ref 优先
        Optional<SupplierProductRef> ref =
            supplierProductRefService.findByIdentity(factory, sourceNamespace, rawSourceProductCode);
        if (ref.isPresent()) {
            return Optional.of(new ResolvedIdentity(
                ref.get().getRspuId(), ResolutionSource.SUPPLIER_PRODUCT_REF, ref.get().getRefId()));
        }

        // 2. 受限 legacy fallback（factory context 可验证 + 该工厂下唯一无歧义）
        return resolveLegacyFallback(factory, rawSourceProductCode.trim());
    }

    /**
     * 批量解析（导入批次预加载等 N+1 消除场景），解析顺序与 {@link #resolve} 完全一致。
     *
     * @param factoryCode 工厂上下文（空 → 返回空 Map，无裸查询）
     * @param sourceNamespace 编码命名空间（空 → {@code default}）
     * @param rawSourceProductCodes 源商品编码原文集合
     * @return key = trim 后的入参编码，value = 命中身份；未命中的编码不出现在返回 Map 中
     *         （是否 negative cache 由调用方决定）
     */
    public Map<String, ResolvedIdentity> resolveBatch(String factoryCode,
                                                      String sourceNamespace,
                                                      Collection<String> rawSourceProductCodes) {
        Map<String, ResolvedIdentity> result = new HashMap<>();
        if (rawSourceProductCodes == null || !StringUtils.hasText(factoryCode)) {
            return result;
        }
        Set<String> codes = rawSourceProductCodes.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) {
            return result;
        }
        String factory = factoryCode.trim();

        // 1. supplier_product_ref 批量优先：归一后一把 in 查询；
        //    多个原文编码可能归一到同值（如 "MJ-S96" 与 "mj - s96"），全部共享同一命中
        Map<String, List<String>> normalizedToCodes = new LinkedHashMap<>();
        for (String code : codes) {
            String normalized = SourceCodeNormalizer.normalize(code);
            if (StringUtils.hasText(normalized)) {
                normalizedToCodes.computeIfAbsent(normalized, k -> new ArrayList<>()).add(code);
            }
        }
        Set<String> remaining = new LinkedHashSet<>(codes);
        if (!normalizedToCodes.isEmpty()) {
            String namespace = StringUtils.hasText(sourceNamespace)
                ? sourceNamespace.trim() : SupplierProductRefService.DEFAULT_NAMESPACE;
            List<SupplierProductRef> refs = supplierProductRefMapper.selectList(
                new QueryWrapper<SupplierProductRef>()
                    .eq("factory_code", factory)
                    .eq("source_namespace", namespace)
                    .in("normalized_source_product_code", normalizedToCodes.keySet()));
            for (SupplierProductRef ref : refs) {
                List<String> hitCodes = normalizedToCodes.get(ref.getNormalizedSourceProductCode());
                if (hitCodes == null) {
                    continue;
                }
                for (String code : hitCodes) {
                    result.put(code, new ResolvedIdentity(
                        ref.getRspuId(), ResolutionSource.SUPPLIER_PRODUCT_REF, ref.getRefId()));
                    remaining.remove(code);
                }
            }
        }

        // 2. 受限 legacy fallback（批量）：工厂真实存在才允许回落
        if (!remaining.isEmpty() && factoryExists(factory)) {
            List<RspuMaster> candidates = rspuMapper.selectList(
                new QueryWrapper<RspuMaster>()
                    .select("rspu_id", "external_code")
                    .in("external_code", remaining));
            Map<String, List<String>> codeToRspuIds = new LinkedHashMap<>();
            for (RspuMaster candidate : candidates) {
                if (StringUtils.hasText(candidate.getExternalCode())) {
                    codeToRspuIds.computeIfAbsent(candidate.getExternalCode().trim(), k -> new ArrayList<>())
                        .add(candidate.getRspuId());
                }
            }
            Set<String> verified = verifiedRspuIds(factory,
                candidates.stream().map(RspuMaster::getRspuId).distinct().toList());
            for (Map.Entry<String, List<String>> entry : codeToRspuIds.entrySet()) {
                List<String> hits = entry.getValue().stream().filter(verified::contains).distinct().toList();
                if (hits.size() == 1) {
                    result.put(entry.getKey(), new ResolvedIdentity(
                        hits.get(0), ResolutionSource.LEGACY_EXTERNAL_CODE, null));
                } else if (hits.size() > 1) {
                    logAmbiguous(entry.getKey(), factory, hits);
                } else {
                    logUnverifiable(entry.getKey(), factory, entry.getValue());
                }
            }
        }
        return result;
    }

    /**
     * 受限 legacy fallback（单条）：{@code external_code} 精确命中后，仅当恰好一个候选
     * 与该工厂存在 active 供应关系时才命中；否则未命中（绝不猜测）。
     */
    private Optional<ResolvedIdentity> resolveLegacyFallback(String factory, String trimmedCode) {
        if (!StringUtils.hasText(trimmedCode) || !factoryExists(factory)) {
            return Optional.empty();
        }
        List<RspuMaster> candidates = rspuMapper.selectList(
            new QueryWrapper<RspuMaster>()
                .select("rspu_id", "external_code")
                .eq("external_code", trimmedCode));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidateIds = candidates.stream().map(RspuMaster::getRspuId).distinct().toList();
        List<String> hits = candidateIds.stream()
            .filter(verifiedRspuIds(factory, candidateIds)::contains)
            .toList();
        if (hits.size() == 1) {
            return Optional.of(new ResolvedIdentity(
                hits.get(0), ResolutionSource.LEGACY_EXTERNAL_CODE, null));
        }
        if (hits.size() > 1) {
            logAmbiguous(trimmedCode, factory, hits);
        } else {
            logUnverifiable(trimmedCode, factory, candidateIds);
        }
        return Optional.empty();
    }

    /** factory context 可验证：工厂在 factory_master 真实存在。 */
    private boolean factoryExists(String factoryCode) {
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null) {
            log.warn("legacy fallback 拒绝：工厂上下文不可验证（工厂不存在），factoryCode={}", factoryCode);
            return false;
        }
        return true;
    }

    /** 候选 RSPU 中与该工厂存在 active 供应关系（rspu_factory_mapping）的 rspu_id 集合。 */
    private Set<String> verifiedRspuIds(String factoryCode, List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Set.of();
        }
        return rspuFactoryMappingMapper.selectList(new QueryWrapper<RspuFactoryMapping>()
                .select("rspu_id")
                .eq("factory_code", factoryCode)
                .eq("status", "active")
                .in("rspu_id", rspuIds))
            .stream()
            .map(RspuFactoryMapping::getRspuId)
            .collect(Collectors.toSet());
    }

    private void logAmbiguous(String code, String factoryCode, List<String> hits) {
        log.warn("legacy fallback 歧义：编码 {} 在工厂 {} 下命中多个候选 RSPU {}，按未命中处理，转人工治理",
            code, factoryCode, hits);
    }

    private void logUnverifiable(String code, String factoryCode, List<String> candidates) {
        log.warn("legacy fallback 拒绝：编码 {} 命中的候选 RSPU {} 均无法验证属于工厂 {}，按未命中处理",
            code, candidates, factoryCode);
    }
}
