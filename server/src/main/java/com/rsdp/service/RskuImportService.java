package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.rsdp.dto.excel.RskuImportRow;
import com.rsdp.dto.response.RskuImportFailure;
import com.rsdp.dto.response.RskuImportResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.util.ExcelFileValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工厂 RSKU 报价 Excel 批量导入服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RskuImportService {

    private static final int MAX_ROWS = 1000;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryService factoryService;
    private final DictService dictService;
    private final AuditLogService auditLogService;
    private final PriceHistoryMapper priceHistoryMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 批量导入 RSKU 报价。
     *
     * <p>本方法负责文件校验、Excel 解析、数据预加载与行级分发；每一行有效数据
     * 通过独立的 {@link #processRowInTransaction} 处理并提交，避免单点失败导致
     * 整批回滚，同时缩短锁持有时间。</p>
     *
     * @param file           Excel 文件
     * @param updateIfExists 当工厂+变体已存在报价时，是否更新价格；false 则跳过
     * @return 导入结果
     */
    public RskuImportResult importRskus(MultipartFile file, boolean updateIfExists) {
        validateFile(file);

        List<RskuImportRow> rows = parseExcel(file);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException("单次导入不能超过 " + MAX_ROWS + " 行");
        }

        RskuImportResult result = new RskuImportResult();
        result.setTotalRows(rows.size());

        // 预加载所有相关工厂、RSPU、变体、现有报价、工厂能力等级
        Map<String, FactoryMaster> factoryMap = preloadFactories(rows);
        Map<String, RspuMaster> rspuMap = preloadRspus(rows);
        Map<String, RspuVariant> variantMap = preloadVariants(rows);
        Map<String, RskuSupply> existingMap = preloadExisting(rows);
        Map<String, List<String>> capableLevelsMap = preloadCapableLevels(factoryMap);
        List<CategoryDict> productLevels = dictService.listByType("factory_level");
        List<CategoryDict> quoteConfidenceLevels = dictService.listByType("quote_confidence");
        if (productLevels == null) {
            productLevels = List.of();
        }
        if (quoteConfidenceLevels == null) {
            quoteConfidenceLevels = List.of();
        }

        Set<String> processedKeys = new HashSet<>();

        int rowIndex = 1; // Excel 行号，第 1 行为表头，数据从第 2 行开始
        for (RskuImportRow row : rows) {
            rowIndex++;
            if (!dataScopeHelper.canAccessRskuFactory(row.getFactoryCode())) {
                result.getFailures().add(new RskuImportFailure(rowIndex, row.getRspuId(), row.getFactoryCode(), row.getVariantId(), "无权为该工厂导入报价"));
                continue;
            }
            normalizeRow(row, productLevels, quoteConfidenceLevels);
            String error = validateRow(row, factoryMap, rspuMap, variantMap, capableLevelsMap, productLevels);
            if (error != null) {
                result.getFailures().add(new RskuImportFailure(rowIndex, row.getRspuId(), row.getFactoryCode(), row.getVariantId(), error));
                continue;
            }

            String key = row.getFactoryCode() + "|" + row.getVariantId();
            if (!processedKeys.add(key)) {
                result.getFailures().add(new RskuImportFailure(rowIndex, row.getRspuId(), row.getFactoryCode(), row.getVariantId(), "Excel 中存在重复报价行（同一工厂+变体）"));
                continue;
            }

            RskuSupply existing = existingMap.get(key);
            String productLevel = resolveProductLevel(row, variantMap.get(row.getVariantId()), rspuMap.get(row.getRspuId()));

            try {
                if (existing != null) {
                    if (updateIfExists) {
                        updateSingleRsku(existing, row, productLevel);
                        result.setSuccessCount(result.getSuccessCount() + 1);
                    } else {
                        result.getFailures().add(new RskuImportFailure(rowIndex, row.getRspuId(), row.getFactoryCode(), row.getVariantId(), "该工厂对该变体已有报价，已跳过"));
                    }
                } else {
                    insertSingleRsku(buildRskuSupply(row, productLevel));
                    result.setSuccessCount(result.getSuccessCount() + 1);
                }
            } catch (DataIntegrityViolationException e) {
                log.warn("导入报价冲突: {} - {}", row.getFactoryCode(), row.getVariantId(), e);
                result.getFailures().add(new RskuImportFailure(
                    rowIndex,
                    row.getRspuId(),
                    row.getFactoryCode(),
                    row.getVariantId(),
                    "数据库唯一约束冲突（可能与其他导入并发或存在重复报价）"
                ));
            } catch (BusinessException e) {
                result.getFailures().add(new RskuImportFailure(rowIndex, row.getRspuId(), row.getFactoryCode(), row.getVariantId(), e.getMessage()));
            }
        }

        result.setFailedCount(result.getFailures().size());
        return result;
    }

    /**
     * 在独立事务中插入单条 RSKU 报价。
     *
     * @param rsku 待插入的 RSKU
     */
    @Transactional
    protected void insertSingleRsku(RskuSupply rsku) {
        rskuSupplyMapper.insert(rsku);
        auditLogService.logCreate("rsku_supply", rsku.getRskuId(), rsku, SecurityOperatorContext.currentUsername());
    }

    /**
     * 在独立事务中更新单条 RSKU 报价，并记录价格历史。
     *
     * @param existing     现有报价
     * @param row          导入行
     * @param productLevel 解析后的产品等级
     */
    @Transactional
    protected void updateSingleRsku(RskuSupply existing, RskuImportRow row, String productLevel) {
        RskuSupply oldSnapshot = snapshot(existing);
        BigDecimal oldPrice = updateExistingRsku(existing, row, productLevel);
        rskuSupplyMapper.updateById(existing);
        if (shouldRecordPriceHistory(oldPrice, existing.getFactoryPrice())) {
            priceHistoryMapper.insert(buildPriceHistory(existing, oldPrice));
        }
        auditLogService.logUpdate("rsku_supply", existing.getRskuId(), oldSnapshot, existing, SecurityOperatorContext.currentUsername());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 10MB");
        }
        if (!ExcelFileValidator.isExcelOrCsv(file)) {
            throw new BusinessException("仅支持 Excel (.xlsx/.xls) 或 CSV 文件");
        }
    }

    private List<RskuImportRow> parseExcel(MultipartFile file) {
        List<RskuImportRow> rows = new ArrayList<>();
        try (InputStream stream = file.getInputStream()) {
            EasyExcel.read(stream, RskuImportRow.class, new ReadListener<RskuImportRow>() {
                @Override
                public void invoke(RskuImportRow row, AnalysisContext context) {
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().doRead();
        } catch (IOException e) {
            log.error("读取 Excel 文件失败", e);
            throw new BusinessException("读取 Excel 文件失败");
        } catch (Exception e) {
            log.error("解析 Excel 文件失败，请检查文件格式是否与模板一致", e);
            throw new BusinessException("解析 Excel 文件失败，请检查文件格式是否与模板一致");
        }
        return rows;
    }

    private Map<String, FactoryMaster> preloadFactories(List<RskuImportRow> rows) {
        List<String> codes = rows.stream()
            .map(RskuImportRow::getFactoryCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return factoryMasterMapper.selectBatchIds(codes).stream()
            .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f, (a, b) -> a));
    }

    private Map<String, RspuMaster> preloadRspus(List<RskuImportRow> rows) {
        List<String> ids = rows.stream()
            .map(RskuImportRow::getRspuId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
    }

    private Map<String, RspuVariant> preloadVariants(List<RskuImportRow> rows) {
        List<String> ids = rows.stream()
            .map(RskuImportRow::getVariantId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rspuVariantMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(RspuVariant::getVariantId, v -> v, (a, b) -> a));
    }

    private Map<String, RskuSupply> preloadExisting(List<RskuImportRow> rows) {
        List<String> variantIds = rows.stream()
            .map(RskuImportRow::getVariantId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return rskuSupplyMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>()
                .in("variant_id", variantIds)
                .isNull("deleted_at")
        ).stream()
            .collect(Collectors.toMap(
                r -> r.getFactoryCode() + "|" + r.getVariantId(),
                r -> r,
                (a, b) -> a
            ));
    }

    private Map<String, List<String>> preloadCapableLevels(Map<String, FactoryMaster> factoryMap) {
        List<String> codes = factoryMap.keySet().stream().toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return factoryService.batchListCapableLevels(codes);
    }

    private String validateRow(RskuImportRow row,
                               Map<String, FactoryMaster> factoryMap,
                               Map<String, RspuMaster> rspuMap,
                               Map<String, RspuVariant> variantMap,
                               Map<String, List<String>> capableLevelsMap,
                               List<CategoryDict> productLevels) {
        if (!StringUtils.hasText(row.getRspuId())) {
            return "RSPU编码 不能为空";
        }
        if (row.getRspuId().length() > 64) {
            return "RSPU编码 长度不能超过 64";
        }
        if (!StringUtils.hasText(row.getFactoryCode())) {
            return "工厂编码 不能为空";
        }
        if (row.getFactoryCode().length() > 16) {
            return "工厂编码 长度不能超过 16";
        }
        if (!StringUtils.hasText(row.getVariantId())) {
            return "变体编码 不能为空";
        }
        if (row.getVariantId().length() > 64) {
            return "变体编码 长度不能超过 64";
        }
        if (row.getFactoryPrice() == null || row.getFactoryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "出厂价 必须大于 0";
        }
        if (row.getFactoryPrice().compareTo(new BigDecimal("99999999.99")) > 0) {
            return "出厂价 超出最大允许范围（99999999.99）";
        }
        if (StringUtils.hasText(row.getFactorySku()) && row.getFactorySku().length() > 64) {
            return "工厂SKU 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getMaterialCode()) && row.getMaterialCode().length() > 8) {
            return "材质编码 长度不能超过 8";
        }
        if (StringUtils.hasText(row.getMaterialDescription()) && row.getMaterialDescription().length() > 1000) {
            return "材质说明 长度不能超过 1000";
        }
        if (row.getLeadTimeDays() != null && (row.getLeadTimeDays() < 0 || row.getLeadTimeDays() > 999)) {
            return "交期（天） 必须在 0~999 之间";
        }
        if (row.getMoq() != null && (row.getMoq() < 0 || row.getMoq() > 999999)) {
            return "最小起订量 必须在 0~999999 之间";
        }
        if (row.getWarrantyYears() != null && (row.getWarrantyYears() < 0 || row.getWarrantyYears() > 50)) {
            return "质保年限 必须在 0~50 之间";
        }
        if (StringUtils.hasText(row.getShippingFrom()) && row.getShippingFrom().length() > 128) {
            return "发货地 长度不能超过 128";
        }
        if (StringUtils.hasText(row.getDiffNotes()) && row.getDiffNotes().length() > 1000) {
            return "差异备注 长度不能超过 1000";
        }

        RspuMaster rspu = rspuMap.get(row.getRspuId());
        if (rspu == null) {
            return "产品不存在或已删除";
        }

        FactoryMaster factory = factoryMap.get(row.getFactoryCode());
        if (factory == null) {
            return "工厂不存在或已删除";
        }

        RspuVariant variant = variantMap.get(row.getVariantId());
        if (variant == null) {
            return "变体不存在或已删除";
        }
        if (!row.getRspuId().equals(variant.getRspuId())) {
            return "变体不属于该产品";
        }

        if (StringUtils.hasText(row.getProductLevel()) && !isValidProductLevel(row.getProductLevel(), productLevels)) {
            return "产品等级不存在: " + row.getProductLevel();
        }

        String productLevel = resolveProductLevel(row, variant, rspu);
        if (!isValidProductLevel(productLevel, productLevels)) {
            return "无法解析有效产品等级";
        }

        List<String> capableLevels = capableLevelsMap.get(row.getFactoryCode());
        if (capableLevels == null || !capableLevels.contains(productLevel)) {
            return String.format("工厂 %s 未声明 %s 级能力", row.getFactoryCode(), productLevel);
        }

        return null;
    }

    private void normalizeRow(RskuImportRow row,
                              List<CategoryDict> productLevels,
                              List<CategoryDict> quoteConfidenceLevels) {
        row.setRspuId(trim(row.getRspuId()));
        row.setFactoryCode(trim(row.getFactoryCode()));
        row.setVariantId(trim(row.getVariantId()));
        row.setFactorySku(trim(row.getFactorySku()));
        row.setMaterialCode(trim(row.getMaterialCode()));
        row.setMaterialDescription(trim(row.getMaterialDescription()));
        row.setShippingFrom(trim(row.getShippingFrom()));
        row.setDiffNotes(trim(row.getDiffNotes()));
        row.setQuoteConfidence(trim(row.getQuoteConfidence()));
        row.setProductLevel(trim(row.getProductLevel()));

        if (StringUtils.hasText(row.getQuoteConfidence())) {
            row.setQuoteConfidence(normalizeDictCode(row.getQuoteConfidence(), quoteConfidenceLevels));
        }
        if (StringUtils.hasText(row.getProductLevel())) {
            row.setProductLevel(normalizeDictCode(row.getProductLevel(), productLevels));
        }
    }

    private String normalizeDictCode(String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        String trimmed = input.trim();
        // 先按 dictCode 精确匹配
        for (CategoryDict d : dicts) {
            if (trimmed.equalsIgnoreCase(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        // 再按 dictName 模糊匹配（如 "S级" / "S级战略厂" 都能匹配到 S）
        for (CategoryDict d : dicts) {
            if (StringUtils.hasText(d.getDictName()) && (trimmed.equals(d.getDictName()) || d.getDictName().startsWith(trimmed))) {
                return d.getDictCode();
            }
        }
        return trimmed;
    }

    private boolean isValidProductLevel(String level, List<CategoryDict> productLevels) {
        if (!StringUtils.hasText(level)) {
            return false;
        }
        return productLevels.stream()
            .anyMatch(d -> level.equals(d.getDictCode()));
    }

    private String resolveProductLevel(RskuImportRow row, RspuVariant variant, RspuMaster rspu) {
        if (StringUtils.hasText(row.getProductLevel())) {
            return row.getProductLevel();
        }
        if (variant != null && StringUtils.hasText(variant.getProductLevel())) {
            return variant.getProductLevel();
        }
        if (rspu != null && StringUtils.hasText(rspu.getProductLevel())) {
            return rspu.getProductLevel();
        }
        return null;
    }

    private RskuSupply buildRskuSupply(RskuImportRow row, String productLevel) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rsku.setRspuId(row.getRspuId().trim());
        rsku.setVariantId(row.getVariantId().trim());
        rsku.setFactoryCode(row.getFactoryCode().trim());
        rsku.setFactorySku(trim(row.getFactorySku()));
        rsku.setFactoryPrice(row.getFactoryPrice());
        rsku.setPriceBand(resolvePriceBand(row.getFactoryPrice()));
        rsku.setProductLevel(productLevel);
        rsku.setMaterialCode(trim(row.getMaterialCode()));
        rsku.setMaterialDescription(trim(row.getMaterialDescription()));
        rsku.setLeadTimeDays(row.getLeadTimeDays());
        rsku.setMoq(row.getMoq());
        rsku.setWarrantyYears(row.getWarrantyYears());
        rsku.setShippingFrom(trim(row.getShippingFrom()));
        rsku.setDiffNotes(trim(row.getDiffNotes()));
        rsku.setQuoteConfidence(trim(row.getQuoteConfidence()));
        rsku.setReviewStatus("待复核");
        rsku.setPriceUpdated(LocalDate.now());
        rsku.setCreatedAt(LocalDateTime.now());
        rsku.setUpdatedAt(LocalDateTime.now());
        return rsku;
    }

    private BigDecimal updateExistingRsku(RskuSupply existing, RskuImportRow row, String productLevel) {
        BigDecimal oldPrice = existing.getFactoryPrice();
        existing.setFactorySku(trim(row.getFactorySku()));
        existing.setFactoryPrice(row.getFactoryPrice());
        existing.setPriceBand(resolvePriceBand(row.getFactoryPrice()));
        existing.setProductLevel(productLevel);
        existing.setMaterialCode(trim(row.getMaterialCode()));
        existing.setMaterialDescription(trim(row.getMaterialDescription()));
        existing.setLeadTimeDays(row.getLeadTimeDays());
        existing.setMoq(row.getMoq());
        existing.setWarrantyYears(row.getWarrantyYears());
        existing.setShippingFrom(trim(row.getShippingFrom()));
        existing.setDiffNotes(trim(row.getDiffNotes()));
        existing.setQuoteConfidence(trim(row.getQuoteConfidence()));
        existing.setPriceUpdated(LocalDate.now());
        existing.setUpdatedAt(LocalDateTime.now());
        return oldPrice;
    }

    private boolean shouldRecordPriceHistory(BigDecimal oldPrice, BigDecimal newPrice) {
        if (newPrice == null) {
            return oldPrice != null;
        }
        return oldPrice == null || oldPrice.compareTo(newPrice) != 0;
    }

    private PriceHistory buildPriceHistory(RskuSupply rsku, BigDecimal oldPrice) {
        PriceHistory history = new PriceHistory();
        history.setRskuId(rsku.getRskuId());
        history.setOldPrice(oldPrice);
        history.setNewPrice(rsku.getFactoryPrice());
        history.setChangedBy(SecurityOperatorContext.currentUsername());
        history.setChangeReason("批量导入更新");
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    private String resolvePriceBand(BigDecimal price) {
        if (price == null) {
            return "unknown";
        }
        if (price.compareTo(new BigDecimal("1000")) < 0) {
            return "low";
        } else if (price.compareTo(new BigDecimal("5000")) < 0) {
            return "mid";
        }
        return "high";
    }

    private String trim(String value) {
        return value != null ? value.trim() : null;
    }

    private RskuSupply snapshot(RskuSupply source) {
        RskuSupply copy = new RskuSupply();
        copy.setRskuId(source.getRskuId());
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
        copy.setReviewStatus(source.getReviewStatus());
        copy.setPriceUpdated(source.getPriceUpdated());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
