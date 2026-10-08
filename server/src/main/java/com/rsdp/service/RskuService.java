package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.supplychain.rsku.QuotePolicyDecider;
import com.rsdp.supplychain.rsku.SupplierConfigurationKeys;
import com.rsdp.util.ManualOverrideFields;
import com.rsdp.util.SupplierConfigurationNormalizer;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.rsdp.dto.request.RskuBatchCreateRequest;
import com.rsdp.dto.request.RskuBatchFactoryQuote;
import com.rsdp.dto.response.RskuBatchCreateResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.rsdp.util.PriceBands;
import com.rsdp.util.IdGenerator;
import java.util.stream.Collectors;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RSKU 报价服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RskuService {

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryService factoryService;
    private final DictService dictService;
    private final RspuVariantService rspuVariantService;
    private final PriceHistoryMapper priceHistoryMapper;
    private final AuditLogService auditLogService;
    private final DataScopeHelper dataScopeHelper;
    private final PlatformTransactionManager transactionManager;
    private final RskuCodeService rskuCodeService;
    private final RspuPriceSummaryService rspuPriceSummaryService;

    /**
     * 查询某 RSPU 下的所有 RSKU 报价。
     *
     * @param rspuId RSPU ID
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByRspu(String rspuId) {
        QueryWrapper<RskuSupply> wrapper = new QueryWrapper<RskuSupply>()
            .eq("rspu_id", rspuId)
            .orderByDesc("created_at");
        dataScopeHelper.applyRskuScope(wrapper);
        List<RskuSupply> list = rskuSupplyMapper.selectList(wrapper);
        return toResponses(list);
    }

    /**
     * 查询单个 RSKU 报价详情。
     *
     * @param rspuId RSPU ID
     * @param rskuId RSKU ID
     * @return RSKU 报价详情
     */
    public RskuResponse getRsku(String rspuId, String rskuId) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!rspuId.equals(rsku.getRspuId())) {
            throw new ResourceNotFoundException("RSKU 不属于该产品: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        return toResponses(List.of(rsku)).get(0);
    }

    /**
     * 查询某工厂的所有 RSKU 报价。
     *
     * @param factoryCode 工厂代码
     * @return RSKU 报价列表
     */
    public List<RskuResponse> listByFactory(String factoryCode) {
        if (!dataScopeHelper.canAccessRskuFactory(factoryCode)) {
            return List.of();
        }
        List<RskuSupply> list = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>()
                .eq("factory_code", factoryCode)
                .orderByDesc("created_at")
        );
        return toResponses(list);
    }

    /**
     * 为 RSPU 新增工厂报价。
     *
     * @param request 报价请求
     * @return 新创建的 RSKU ID
     */
    @Transactional
    public String createRsku(RskuCreateRequest request) {
        // 服务层兜底：出厂价必须 > 0（与 updateRskuPrice 口径一致，防绕过 Bean Validation 的内部调用）
        if (request.getFactoryPrice() == null || request.getFactoryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("出厂价必须大于 0");
        }

        RspuMaster rspu = rspuMapper.selectById(request.getRspuId());
        if (rspu == null) {
            throw new ResourceNotFoundException("产品不存在: " + request.getRspuId());
        }

        FactoryMaster factory = factoryMasterMapper.selectById(request.getFactoryCode());
        if (factory == null) {
            throw new ResourceNotFoundException("工厂不存在: " + request.getFactoryCode());
        }
        if (!dataScopeHelper.canAccessRskuFactory(request.getFactoryCode())) {
            throw new BusinessException("无权为该工厂录入报价: " + request.getFactoryCode());
        }

        RspuVariant variant = rspuVariantService.findById(request.getVariantId());
        if (!request.getRspuId().equals(variant.getRspuId())) {
            throw new BusinessException("变体不属于该产品: " + request.getVariantId());
        }

        String productLevel = resolveProductLevel(request, rspu, variant);
        validateProductLevel(productLevel);

        List<String> capableLevels = factoryService.getFactoryCapableLevels(request.getFactoryCode());
        if (!capableLevels.contains(productLevel)) {
            if (Boolean.TRUE.equals(request.getAutoExtendCapability())) {
                factoryService.extendCapability(request.getFactoryCode(), productLevel);
            } else {
                throw new BusinessException(
                    String.format("工厂 %s 未声明 %s 级能力，无法录入该等级产品报价。请先更新工厂能力等级或开启自动扩展。",
                        request.getFactoryCode(), productLevel));
            }
        }

        RskuSupply rsku = buildNewRsku(request, productLevel, variant);

        // 生成并写入 RSKU 业务编码（容错发号：所属 RSPU 未发号等原因导致发号失败时不阻断创建，
        // 价格等供应数据先入库，业务编码待 RSPU 发号后由 RskuCodeService.backfillCodesByRspu 补发）
        String rskuCode = rskuCodeService.tryAssignCode(rsku.getRskuId(), request.getRspuId(), request.getFactoryCode(),
            rsku.getMaterialCode());
        if (rskuCode == null) {
            log.warn("RSKU 无码创建，rsku_code 留空待补发，rskuId={}，rspuId={}，factoryCode={}（所属 RSPU 无 rspu_code 或发号失败）",
                rsku.getRskuId(), request.getRspuId(), request.getFactoryCode());
        }
        rsku.setRskuCode(rskuCode);

        try {
            rskuSupplyMapper.insert(rsku);
        } catch (DataIntegrityViolationException e) {
            // 细化约束冲突类型，给出可操作的错误信息
            String detail = e.getMessage() != null ? e.getMessage() : "";
            if (detail.contains("unique") || detail.contains("duplicate")) {
                throw new BusinessException("该工厂对该变体的同一供应配置已有报价");
            }
            if (detail.contains("foreign key")) {
                throw new BusinessException("关联数据不存在（产品/变体/工厂），请刷新后重试");
            }
            throw new BusinessException("数据约束冲突，请检查输入");
        }

        auditLogService.logCreate("rsku_supply", rsku.getRskuId(), rsku, SecurityOperatorContext.currentUsername());
        rspuPriceSummaryService.recalculate(rsku.getRspuId());
        return rsku.getRskuId();
    }

    /**
     * 构造新 RSKU 实体（createRsku / upsertRsku 并发插入共用）。
     *
     * <p>v3.3 §34：supplierConfigurationKey / normalizerVersion 必须显式写入
     * （无数据库 DEFAULT）；Key 优先级：请求显式携带（Snapshot 固化路径）→
     * grade code/text 推导（§18.2）→ BASE。</p>
     */
    private RskuSupply buildNewRsku(RskuCreateRequest request, String productLevel, RspuVariant variant) {
        RskuSupply rsku = new RskuSupply();
        String rskuId = IdGenerator.rskuId();
        rsku.setRskuId(rskuId);
        rsku.setRspuId(request.getRspuId());
        rsku.setVariantId(request.getVariantId());
        rsku.setFactoryCode(request.getFactoryCode());
        rsku.setFactorySku(request.getFactorySku());
        rsku.setFactoryPrice(request.getFactoryPrice());
        rsku.setPriceBand(resolvePriceBand(request.getFactoryPrice()));
        rsku.setProductLevel(productLevel);
        String materialCode = resolveMaterialCode(request.getMaterialCode(), variant);
        rsku.setMaterialCode(materialCode);
        rsku.setMaterialDescription(request.getMaterialDescription());
        rsku.setLeadTimeDays(request.getLeadTimeDays());
        rsku.setMoq(request.getMoq());
        rsku.setWarrantyYears(request.getWarrantyYears());
        rsku.setShippingFrom(request.getShippingFrom());
        rsku.setShippingWarehouseId(request.getShippingWarehouseId());
        rsku.setDiffNotes(request.getDiffNotes());
        rsku.setQuoteConfidence(request.getQuoteConfidence());
        rsku.setPriceUpdated(LocalDate.now());
        rsku.setCreatedAt(LocalDateTime.now());
        rsku.setUpdatedAt(LocalDateTime.now());

        // v3.3 P0-D：Supplier Configuration Identity（§18.2/§32.1）显式赋值
        rsku.setSupplierConfigurationKey(resolveConfigKey(request));
        rsku.setSupplierConfigurationNormalizerVersion(
            String.valueOf(SupplierConfigurationNormalizer.CURRENT_VERSION));
        rsku.setSupplierMaterialGradeCode(request.getSupplierMaterialGradeCode());
        rsku.setSupplierMaterialGradeText(request.getSupplierMaterialGradeText());
        // Quote 基础口径 / 有效期（§34；请求未携带时由数据库默认/NULL 承担）
        if (StringUtils.hasText(request.getCurrencyCode())) {
            rsku.setCurrencyCode(request.getCurrencyCode().trim().toUpperCase());
        }
        rsku.setTaxIncluded(request.getTaxIncluded());
        rsku.setPriceUnit(request.getPriceUnit());
        rsku.setMoqUnit(request.getMoqUnit());
        rsku.setQuoteDate(request.getQuoteDate());
        rsku.setEffectiveFrom(request.getEffectiveFrom());
        rsku.setEffectiveTo(request.getEffectiveTo());
        return rsku;
    }

    /**
     * 解析请求落定的 Supplier Configuration Key：显式携带（须形态合法）→
     * grade code/text 推导（§18.2 优先级）→ BASE。
     */
    private String resolveConfigKey(RskuCreateRequest request) {
        if (StringUtils.hasText(request.getSupplierConfigurationKey())) {
            String key = request.getSupplierConfigurationKey().trim();
            if (!SupplierConfigurationKeys.isWellFormed(key)) {
                throw new BusinessException("supplierConfigurationKey 形态非法（须为 BASE 或 CODE:/TEXT: 前缀）: " + key);
            }
            return key;
        }
        return SupplierConfigurationKeys.of(
            request.getSupplierMaterialGradeCode(), request.getSupplierMaterialGradeText());
    }

    /**
     * 按新 RSKU Identity（变体 + 工厂 + 供应配置，v3.3 §32.1）upsert 工厂报价。
     *
     * <p>已存在未软删的同配置报价时更新价格/交期/MOQ/材质等字段（价格变化写
     * price_history，报价更新 quote_version+1，§37），否则走 {@link #createRsku} 新建。
     * 用于 Excel AI 导入的重复导入（updateIfExists）与同组模块行重复报价场景。
     * 横向 A/AA/S 等级以不同 supplierConfigurationKey 生成各自 RSKU，互不覆盖（§56）。</p>
     *
     * <p>manualCorrectionPolicy=preserve（§47.1.5 默认）：{@code /factoryPrice} 存在活跃
     * 人工修正标记时导入价格不覆盖既有值（§34.1，写入前 FOR UPDATE 锁定并同事务重读标记）。</p>
     *
     * @param request 报价请求
     * @return 已存在或新建的 RSKU ID
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public String upsertRsku(RskuCreateRequest request) {
        // v3.3 §32.1：Identity = (variant_id, factory_code, supplier_configuration_key)；
        // 横向 A/AA/S 等级以不同 Key 生成各自 RSKU，互不覆盖（§56）
        String configKey = resolveConfigKey(request);
        RskuSupply existing = rskuSupplyMapper.selectAliveByConfig(
            request.getVariantId(), request.getFactoryCode(), configKey);
        if (existing == null) {
            return createRsku(request);
        }

        // §34.1：写入前锁定目标实体并在同一事务重读 manual_override_fields，
        // 不使用 Preview 时缓存的过期标记覆盖并发人工修正
        RskuSupply locked = rskuSupplyMapper.selectAliveByIdForUpdate(existing.getRskuId());
        if (locked == null) {
            throw new BusinessException("该供应配置报价已被删除，请重试");
        }
        RskuSupply oldSnapshot = snapshot(locked);
        BigDecimal oldPrice = locked.getFactoryPrice();
        LocalDateTime now = LocalDateTime.now();

        // P0-F（§47.1.5）：Attempt 治理链路携带 quotePolicy 时按日期/同值条件表决策；
        // 执行优先级：权限/数据库完整性 → manualCorrectionPolicy → nullValuePolicy → quotePolicy。
        // legacy 链路（quotePolicy == null）维持原覆盖语义不变
        QuotePolicyDecider.Outcome quoteOutcome = null;
        if (StringUtils.hasText(request.getQuotePolicy())) {
            quoteOutcome = QuotePolicyDecider.decide(request.getQuotePolicy(),
                request.getQuoteDate(), locked.getQuoteDate(),
                quoteValueSame(request, locked), quoteBasisSame(request, locked));
            switch (quoteOutcome.decision()) {
                case KEEP_STALE_IGNORED -> {
                    addQuotePolicyNote(request, "STALE_QUOTE_IGNORED: 导入报价日期早于既有报价，已保留既有值");
                    return locked.getRskuId();
                }
                case KEEP_EXISTING_SKIPPED -> {
                    addQuotePolicyNote(request, "quotePolicy=keep_existing: 命中已有报价，跳过不改写");
                    return locked.getRskuId();
                }
                case CONFLICT_SAME_DATE, CONFLICT_DATE_MISSING, CONFLICT_UNDATED, CONFLICT_QUOTE_BASIS ->
                    throw new BusinessException(quoteOutcome.issueCode()
                        + ": 导入报价与既有报价冲突（quotePolicy=replace_if_newer 条件表，§47.1.5），"
                        + "请经 Preview 处置或具备权限的 force_replace 处理");
                case FORCE_REPLACE -> {
                    // force_replace：仅具备报价覆盖权限的人工导入可用，reason 必填并记审计；
                    // 不隐式绕过 manualCorrectionPolicy=preserve（§47.1.5）
                    if (!StringUtils.hasText(request.getQuoteOverrideReason())) {
                        throw new BusinessException("quotePolicy=force_replace 必须携带 overrideReason（§47.1.5）");
                    }
                    if (!SecurityOperatorContext.isCurrentUserAdmin()) {
                        throw new com.rsdp.exception.ForbiddenException(
                            "quotePolicy=force_replace 仅管理员可用（报价覆盖权限，§47.1.5）");
                    }
                }
                default -> {
                    // UPDATE / UPDATE_VALIDITY_ONLY / NO_OP 走下方流程
                }
            }
        }

        // 与 RskuImportService.updateExistingRsku 同语义：仅更新请求中显式有值的字段；
        // manualCorrectionPolicy=preserve（§47.1.5 默认）：/factoryPrice 存在活跃人工修正标记时
        // Excel/AI 导入不覆盖价格（§34.1：manual_override_fields 是唯一当前事实源）
        boolean priceChanged = request.getFactoryPrice() != null
            && (oldPrice == null || oldPrice.compareTo(request.getFactoryPrice()) != 0);
        boolean pricePreservedByOverride = false;
        // NO_OP / UPDATE_VALIDITY_ONLY：quote 事实不写（no-op 不增长 quote_version / History；
        // validity_update 只更新日期/有效期，不记伪价格变更，§47.1.5 条件表）
        boolean quoteFactFrozen = quoteOutcome != null
            && (quoteOutcome.decision() == QuotePolicyDecider.Decision.NO_OP
                || quoteOutcome.decision() == QuotePolicyDecider.Decision.UPDATE_VALIDITY_ONLY);
        if (priceChanged
            && ManualOverrideFields.isActive(locked.getManualOverrideFields(), "/factoryPrice")) {
            pricePreservedByOverride = true;
            log.warn("RSKU /factoryPrice 存在活跃人工修正标记，导入价格被保留策略跳过: rskuId={}", locked.getRskuId());
        } else if (request.getFactoryPrice() != null && !quoteFactFrozen) {
            locked.setFactoryPrice(request.getFactoryPrice());
            locked.setPriceBand(resolvePriceBand(request.getFactoryPrice()));
            locked.setPriceUpdated(LocalDate.now());
        }
        // 非 quote 事实字段（材质/交期/发货地/等级等）：变更置 dirty，用于 governed NO_OP
        // 的真空转判定（无任何字段变化时不写库、不增长 quote_version，§47.1.5 同值 no-op）
        boolean nonQuoteDirty = false;
        if (StringUtils.hasText(request.getMaterialCode())
            && !request.getMaterialCode().equals(locked.getMaterialCode())) {
            locked.setMaterialCode(request.getMaterialCode());
            nonQuoteDirty = true;
        }
        if (StringUtils.hasText(request.getMaterialDescription())
            && !request.getMaterialDescription().equals(locked.getMaterialDescription())) {
            locked.setMaterialDescription(request.getMaterialDescription());
            nonQuoteDirty = true;
        }
        if (request.getLeadTimeDays() != null
            && !request.getLeadTimeDays().equals(locked.getLeadTimeDays())) {
            locked.setLeadTimeDays(request.getLeadTimeDays());
            nonQuoteDirty = true;
        }
        // MOQ / MOQ 单位属 quoteValueSame 比较字段（quote 事实）：NO_OP 时不写
        if (request.getMoq() != null && !quoteFactFrozen) {
            locked.setMoq(request.getMoq());
        }
        if (StringUtils.hasText(request.getMoqUnit()) && !quoteFactFrozen) {
            locked.setMoqUnit(request.getMoqUnit());
        }
        if (StringUtils.hasText(request.getShippingFrom())
            && !request.getShippingFrom().equals(locked.getShippingFrom())) {
            locked.setShippingFrom(request.getShippingFrom());
            nonQuoteDirty = true;
        }
        if (StringUtils.hasText(request.getShippingWarehouseId())
            && !request.getShippingWarehouseId().equals(locked.getShippingWarehouseId())) {
            locked.setShippingWarehouseId(request.getShippingWarehouseId());
            nonQuoteDirty = true;
        }
        if (StringUtils.hasText(request.getProductLevel())
            && !request.getProductLevel().equals(locked.getProductLevel())) {
            locked.setProductLevel(request.getProductLevel());
            nonQuoteDirty = true;
        }
        // Quote 基础口径 / 有效期（§34；nullValuePolicy=preserve_existing：空单元格不清空已有值）；
        // quoteFactFrozen（NO_OP）：quote 事实字段（口径/MOQ/日期/有效期）一律不写；
        // UPDATE_VALIDITY_ONLY：只更新日期/有效期，不动价格与口径（§47.1.5 条件表）
        boolean validityUpdateOnly = quoteOutcome != null
            && quoteOutcome.decision() == QuotePolicyDecider.Decision.UPDATE_VALIDITY_ONLY;
        if (!quoteFactFrozen) {
            if (StringUtils.hasText(request.getCurrencyCode())) {
                locked.setCurrencyCode(request.getCurrencyCode().trim().toUpperCase());
            }
            if (request.getTaxIncluded() != null) {
                locked.setTaxIncluded(request.getTaxIncluded());
            }
            if (StringUtils.hasText(request.getPriceUnit())) {
                locked.setPriceUnit(request.getPriceUnit());
            }
        }
        if (!quoteFactFrozen || validityUpdateOnly) {
            if (request.getQuoteDate() != null) {
                locked.setQuoteDate(request.getQuoteDate());
            }
            if (request.getEffectiveFrom() != null) {
                locked.setEffectiveFrom(request.getEffectiveFrom());
            }
            if (request.getEffectiveTo() != null) {
                locked.setEffectiveTo(request.getEffectiveTo());
            }
        }
        if (StringUtils.hasText(request.getSupplierMaterialGradeCode())) {
            locked.setSupplierMaterialGradeCode(request.getSupplierMaterialGradeCode());
        }
        if (StringUtils.hasText(request.getSupplierMaterialGradeText())) {
            locked.setSupplierMaterialGradeText(request.getSupplierMaterialGradeText());
        }

        boolean governedNoOp = quoteOutcome != null
            && quoteOutcome.decision() == QuotePolicyDecider.Decision.NO_OP;
        if (governedNoOp && !nonQuoteDirty) {
            // §47.1.5 同值 no-op：不增长 quote_version / 不写 History / 不动 updated_at
            return locked.getRskuId();
        }
        locked.setUpdatedAt(now);
        rskuSupplyMapper.updateById(locked);

        // §37 报价乐观并发版本：FOR UPDATE 行锁下读取的期望版本必然命中；影响 0 行即冲突
        // governed NO_OP 且仅非 quote 字段变化：quote 事实未变，不增长 quote_version（§50.4）
        if (!governedNoOp) {
            int bumped = rskuSupplyMapper.bumpQuoteVersion(
                locked.getRskuId(), oldSnapshot.getQuoteVersion(), now);
            if (bumped == 0) {
                throw new BusinessException("quote_version_conflict: 报价已被并发修改，请刷新后重试");
            }
        }

        // 价格变化写价格历史（与批量导入更新同语义；人工修正保留跳过 / quote 事实冻结时
        // 无价格变化，不写历史——UPDATE_VALIDITY_ONLY 只记 validity_update，不记伪价格变更）
        if (priceChanged && !pricePreservedByOverride && !quoteFactFrozen) {
            PriceHistory history = new PriceHistory();
            history.setRskuId(locked.getRskuId());
            history.setOldPrice(oldPrice);
            history.setNewPrice(request.getFactoryPrice());
            history.setChangedBy(SecurityOperatorContext.currentUsername());
            history.setChangeReason(validityUpdateOnly ? "Excel 导入有效期更新" : "Excel 导入更新");
            history.setCreatedAt(LocalDateTime.now());
            priceHistoryMapper.insert(history);
        }

        auditLogService.logUpdate("rsku_supply", locked.getRskuId(), oldSnapshot, locked,
            SecurityOperatorContext.currentUsername());
        if (quoteOutcome != null
            && quoteOutcome.decision() == QuotePolicyDecider.Decision.FORCE_REPLACE) {
            // §47.1.5：force_replace 必须记审计（操作人 + reason）
            auditLogService.logUpdate("rsku_supply", locked.getRskuId(),
                Map.of("quotePolicy", "replace_if_newer"),
                Map.of("action", "forceReplace",
                    "reason", request.getQuoteOverrideReason(),
                    "note", "具备报价覆盖权限的人工强制覆盖"),
                SecurityOperatorContext.currentUsername());
        }
        if (pricePreservedByOverride) {
            auditLogService.logUpdate("rsku_supply", locked.getRskuId(),
                Map.of("factoryPrice", oldPrice == null ? "" : "***"),
                Map.of("action", "manualOverridePreserved",
                    "field", "/factoryPrice",
                    "note", "活跃人工修正标记生效，导入价格未覆盖"),
                SecurityOperatorContext.currentUsername());
        }
        rspuPriceSummaryService.recalculate(locked.getRspuId());
        return locked.getRskuId();
    }

    /**
     * quoteValueSame（§47.1.5）：比较标准化后的价格、MOQ 与有效期等业务字段，
     * 排除 quote_date、来源坐标与 Runtime 字段。请求字段为 null 视为「未提供」
     * （nullValuePolicy=preserve_existing），不参与差异判定。
     */
    private boolean quoteValueSame(RskuCreateRequest request, RskuSupply existing) {
        if (request.getFactoryPrice() != null && (existing.getFactoryPrice() == null
            || existing.getFactoryPrice().compareTo(request.getFactoryPrice()) != 0)) {
            return false;
        }
        if (request.getMoq() != null && !request.getMoq().equals(existing.getMoq())) {
            return false;
        }
        if (StringUtils.hasText(request.getMoqUnit())
            && !request.getMoqUnit().equals(existing.getMoqUnit())) {
            return false;
        }
        if (request.getEffectiveFrom() != null
            && !request.getEffectiveFrom().equals(existing.getEffectiveFrom())) {
            return false;
        }
        return request.getEffectiveTo() == null
            || request.getEffectiveTo().equals(existing.getEffectiveTo());
    }

    /**
     * Quote 基础口径一致性（§47.1.5：currency_code + price_unit + tax_included）。
     * 请求字段为 null 视为「未提供」，以既有值为准（不产生口径差异）。
     */
    private boolean quoteBasisSame(RskuCreateRequest request, RskuSupply existing) {
        if (StringUtils.hasText(request.getCurrencyCode())
            && !request.getCurrencyCode().trim().toUpperCase()
                .equals(existing.getCurrencyCode() == null ? "CNY" : existing.getCurrencyCode())) {
            return false;
        }
        if (StringUtils.hasText(request.getPriceUnit())
            && !request.getPriceUnit().equals(existing.getPriceUnit())) {
            return false;
        }
        // null 与 false 是不同材料（§38.1 同口径）；请求未提供（null）时不构成差异
        return request.getTaxIncluded() == null
            || request.getTaxIncluded().equals(existing.getTaxIncluded());
    }

    /** 追加 Quote Policy 决策说明（导入行循环汇总为用户可见的行级问题）。 */
    private void addQuotePolicyNote(RskuCreateRequest request, String note) {
        if (request.getQuotePolicyNotes() != null) {
            request.getQuotePolicyNotes().add(note);
        }
    }

    /**
     * 批量为 RSPU 创建多家工厂报价。
     *
     * <p>每个（变体，工厂）组合单独一个事务处理，失败只回滚当前组合，不影响其他组合。
     * 同一家工厂的报价信息会应用到所有选中的变体。</p>
     *
     * @param rspuId  RSPU ID
     * @param request 批量创建请求
     * @return 批量创建结果
     */
    public RskuBatchCreateResponse batchCreateRskus(String rspuId, RskuBatchCreateRequest request) {
        RskuBatchCreateResponse response = new RskuBatchCreateResponse();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        for (String variantId : request.getVariantIds()) {
            RspuVariant variant;
            try {
                variant = rspuVariantService.findById(variantId);
                if (!rspuId.equals(variant.getRspuId())) {
                    throw new BusinessException("变体不属于该产品: " + variantId);
                }
            } catch (Exception e) {
                for (RskuBatchFactoryQuote quote : request.getFactoryQuotes()) {
                    response.setFailedCount(response.getFailedCount() + 1);
                    response.getFailures().add(new RskuBatchCreateResponse.FailureDetail(
                        quote.getFactoryCode(), variantId, e.getMessage()));
                }
                continue;
            }

            for (RskuBatchFactoryQuote quote : request.getFactoryQuotes()) {
                RskuCreateRequest item = new RskuCreateRequest();
                item.setRspuId(rspuId);
                item.setVariantId(variantId);
                item.setFactoryCode(quote.getFactoryCode());
                item.setFactorySku(quote.getFactorySku());
                item.setFactoryPrice(quote.getFactoryPrice());
                item.setMaterialCode(quote.getMaterialCode());
                item.setMaterialDescription(quote.getMaterialDescription());
                item.setLeadTimeDays(quote.getLeadTimeDays());
                item.setMoq(quote.getMoq());
                item.setWarrantyYears(quote.getWarrantyYears());
                item.setShippingFrom(quote.getShippingFrom());
                item.setDiffNotes(quote.getDiffNotes());
                item.setQuoteConfidence(quote.getQuoteConfidence());
                item.setProductLevel(request.getProductLevel());
                item.setAutoExtendCapability(request.getAutoExtendCapability());

                try {
                    String rskuId = transactionTemplate.execute(status -> createRsku(item));
                    response.getRskuIds().add(rskuId);
                    response.setSuccessCount(response.getSuccessCount() + 1);
                } catch (Exception e) {
                    response.setFailedCount(response.getFailedCount() + 1);
                    response.getFailures().add(new RskuBatchCreateResponse.FailureDetail(
                        quote.getFactoryCode(), variantId, e.getMessage()));
                }
            }
        }

        return response;
    }

    private String resolveProductLevel(RskuCreateRequest request, RspuMaster rspu, RspuVariant variant) {
        if (StringUtils.hasText(request.getProductLevel())) {
            return request.getProductLevel().trim();
        }
        if (StringUtils.hasText(variant.getProductLevel())) {
            return variant.getProductLevel();
        }
        if (StringUtils.hasText(rspu.getProductLevel())) {
            return rspu.getProductLevel();
        }
        throw new BusinessException("请为产品或变体设置产品等级，或在报价中指定产品等级");
    }

    private String resolveMaterialCode(String requestMaterialCode, RspuVariant variant) {
        if (StringUtils.hasText(requestMaterialCode)) {
            return requestMaterialCode.trim().toUpperCase();
        }
        if (variant != null && StringUtils.hasText(variant.getMaterialCode())) {
            return variant.getMaterialCode().trim().toUpperCase();
        }
        throw new BusinessException("材质码不能为空，无法生成 RSKU 业务编码");
    }

    private void validateProductLevel(String level) {
        boolean exists = dictService.listByType("factory_level").stream()
            .anyMatch(d -> level.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException("产品等级不存在: " + level);
        }
    }

    /**
     * 软删除 RSKU 报价。
     *
     * @param rskuId RSKU ID
     */
    @Transactional
    public void deleteRsku(String rskuId) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }

        RskuSupply oldSnapshot = snapshot(rsku);
        int affected = rskuSupplyMapper.deleteById(rskuId);
        if (affected == 0) {
            throw new ResourceNotFoundException("RSKU 不存在或已被删除: " + rskuId);
        }

        auditLogService.logDelete("rsku_supply", rskuId, oldSnapshot, SecurityOperatorContext.currentUsername());
        rspuPriceSummaryService.recalculate(rsku.getRspuId());
    }

    /**
     * 更新 RSKU 出厂价，并记录价格历史。
     *
     * @param rspuId      RSPU ID
     * @param rskuId      RSKU ID
     * @param newPrice    新价格
     * @param changeReason 变更原因
     * @throws ResourceNotFoundException 当 RSKU 不存在时
     * @throws BusinessException         当 RSKU 不属于该 RSPU 或价格非法时
     */
    @Transactional
    public void updateRskuPrice(String rspuId, String rskuId, BigDecimal newPrice, String changeReason) {
        RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
        if (rsku == null) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (!rspuId.equals(rsku.getRspuId())) {
            throw new BusinessException("RSKU 不属于该产品: " + rskuId);
        }
        if (!dataScopeHelper.canAccessRskuFactory(rsku.getFactoryCode())) {
            throw new ResourceNotFoundException("RSKU 不存在: " + rskuId);
        }
        if (newPrice == null || newPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException("价格必须大于 0");
        }

        // §34.1：人工改价必须在同一数据库事务设置 /factoryPrice 人工修正标记并写 Audit Log；
        // FOR UPDATE 行锁 + §37 quote_version 乐观版本（锁内读取的期望版本必然命中）
        RskuSupply locked = rskuSupplyMapper.selectAliveByIdForUpdate(rskuId);
        if (locked == null) {
            throw new ResourceNotFoundException("RSKU 不存在或已被删除: " + rskuId);
        }
        RskuSupply oldSnapshot = snapshot(locked);
        BigDecimal oldPrice = locked.getFactoryPrice();
        LocalDateTime now = LocalDateTime.now();
        String operator = SecurityOperatorContext.currentUsername();

        locked.setFactoryPrice(newPrice);
        locked.setPriceBand(resolvePriceBand(newPrice));
        locked.setPriceUpdated(LocalDate.now());
        locked.setUpdatedAt(now);
        locked.setManualOverrideFields(ManualOverrideFields.markActive(
            locked.getManualOverrideFields(), "/factoryPrice", operator,
            ManualOverrideFields.SOURCE_PRODUCT_UI));
        rskuSupplyMapper.updateById(locked);

        int bumped = rskuSupplyMapper.bumpQuoteVersion(rskuId, oldSnapshot.getQuoteVersion(), now);
        if (bumped == 0) {
            throw new BusinessException("quote_version_conflict: 报价已被并发修改，请刷新后重试");
        }

        PriceHistory history = new PriceHistory();
        history.setRskuId(rskuId);
        history.setOldPrice(oldPrice);
        history.setNewPrice(newPrice);
        history.setChangedBy(operator);
        history.setChangeReason(changeReason);
        history.setCreatedAt(LocalDateTime.now());
        priceHistoryMapper.insert(history);

        auditLogService.logUpdate("rsku_supply", rskuId, oldSnapshot, locked, operator);
        rspuPriceSummaryService.recalculate(rspuId);
    }

    private RskuSupply snapshot(RskuSupply source) {
        RskuSupply copy = new RskuSupply();
        copy.setRskuId(source.getRskuId());
        copy.setRskuCode(source.getRskuCode());
        copy.setRspuId(source.getRspuId());
        copy.setVariantId(source.getVariantId());
        copy.setFactoryCode(source.getFactoryCode());
        copy.setFactorySku(source.getFactorySku());
        copy.setFactoryPrice(source.getFactoryPrice());
        copy.setPriceBand(source.getPriceBand());
        copy.setProductLevel(source.getProductLevel());
        copy.setMaterialCode(source.getMaterialCode());
        copy.setMaterialDescription(source.getMaterialDescription());
        copy.setLeadTimeDays(source.getLeadTimeDays());
        copy.setMoq(source.getMoq());
        copy.setWarrantyYears(source.getWarrantyYears());
        copy.setShippingFrom(source.getShippingFrom());
        copy.setDiffNotes(source.getDiffNotes());
        copy.setQuoteConfidence(source.getQuoteConfidence());
        copy.setPriceUpdated(source.getPriceUpdated());
        copy.setStatus(source.getStatus());
        copy.setCurrencyCode(source.getCurrencyCode());
        copy.setTaxIncluded(source.getTaxIncluded());
        copy.setPriceUnit(source.getPriceUnit());
        copy.setMoqUnit(source.getMoqUnit());
        copy.setQuoteDate(source.getQuoteDate());
        copy.setEffectiveFrom(source.getEffectiveFrom());
        copy.setEffectiveTo(source.getEffectiveTo());
        copy.setSupplierMaterialGradeCode(source.getSupplierMaterialGradeCode());
        copy.setSupplierMaterialGradeText(source.getSupplierMaterialGradeText());
        copy.setSupplierConfigurationKey(source.getSupplierConfigurationKey());
        copy.setSupplierConfigurationNormalizerVersion(source.getSupplierConfigurationNormalizerVersion());
        copy.setQuoteVersion(source.getQuoteVersion());
        copy.setManualOverrideFields(source.getManualOverrideFields());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String resolvePriceBand(BigDecimal price) {
        if (price == null) {
            return "unknown";
        }
        return PriceBands.of(price);
    }

    private List<RskuResponse> toResponses(List<RskuSupply> rskus) {
        List<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        Map<String, FactoryMaster> factoryMap = factoryCodes.isEmpty()
            ? Map.of()
            : factoryMasterMapper.selectBatchIds(factoryCodes).stream()
                .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));
        Map<String, List<String>> capabilityMap = factoryCodes.isEmpty()
            ? Map.of()
            : factoryService.batchListCapableLevels(factoryCodes);

        return rskus.stream()
            .map(rsku -> toResponse(rsku, factoryMap, capabilityMap))
            .collect(Collectors.toList());
    }

    private RskuResponse toResponse(RskuSupply rsku,
                                    Map<String, FactoryMaster> factoryMap,
                                    Map<String, List<String>> capabilityMap) {
        RskuResponse response = new RskuResponse();
        response.setRskuId(rsku.getRskuId());
        response.setRskuCode(rsku.getRskuCode());
        response.setRspuId(rsku.getRspuId());
        response.setVariantId(rsku.getVariantId());
        response.setFactoryCode(rsku.getFactoryCode());
        response.setFactorySku(rsku.getFactorySku());
        // 出厂价按角色掩码：仅平台运营人员与本厂管理员可见，设计师/普通用户返回 null
        response.setFactoryPrice(dataScopeHelper.canViewFactoryPrice(rsku.getFactoryCode())
            ? rsku.getFactoryPrice() : null);
        response.setPriceBand(rsku.getPriceBand());
        response.setProductLevel(rsku.getProductLevel());
        response.setMaterialCode(rsku.getMaterialCode());
        response.setMaterialDescription(rsku.getMaterialDescription());
        response.setLeadTimeDays(rsku.getLeadTimeDays());
        response.setMoq(rsku.getMoq());
        response.setWarrantyYears(rsku.getWarrantyYears());
        response.setShippingFrom(rsku.getShippingFrom());
        response.setDiffNotes(rsku.getDiffNotes());
        response.setQuoteConfidence(rsku.getQuoteConfidence());
        response.setPriceUpdated(rsku.getPriceUpdated());
        response.setCreatedAt(rsku.getCreatedAt());
        response.setUpdatedAt(rsku.getUpdatedAt());

        FactoryMaster factory = factoryMap.get(rsku.getFactoryCode());
        if (factory != null) {
            response.setFactoryName(factory.getFactoryName());
        }
        List<String> capableLevels = capabilityMap.getOrDefault(rsku.getFactoryCode(), List.of());
        // 老数据兼容： capability 表无记录时，回退到工厂主等级
        if (capableLevels.isEmpty() && factory != null && factory.getFactoryLevel() != null) {
            capableLevels = List.of(factory.getFactoryLevel());
        }
        response.setFactoryCapableLevels(capableLevels);
        return response;
    }
}
