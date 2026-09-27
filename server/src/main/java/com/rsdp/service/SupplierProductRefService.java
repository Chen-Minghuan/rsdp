package com.rsdp.service;

import com.rsdp.entity.SupplierProductRef;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.SourceCodeReusedException;
import com.rsdp.mapper.SupplierProductRefMapper;
import com.rsdp.util.SourceCodeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 供应商源商品身份服务（设计文档 v3.3 §6 / §8）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>身份解析/查重：按 {@code (factory_code, source_namespace, normalized code)} 命中 Supplier Ref，
 *       供导入链路查重与幂等；{@code source_system} 只作 provenance 记录，不参与查询条件（§6）；</li>
 *   <li>创建：写入时固定使用 {@link SourceCodeNormalizer#CURRENT_VERSION} 归一并持久化版本号，
 *       原文 {@code source_product_code} 原样保存；</li>
 *   <li>冲突治理：同键已绑定其他 RSPU 时抛 {@link SourceCodeReusedException}
 *       （{@code SOURCE_CODE_REUSED / blocker / product_blocking}，§6.2），绝不自动改绑；</li>
 *   <li>并发：并发导入同键撞唯一约束时回读既有记录（§77.1「并发导入相同 Factory / S001」）。</li>
 * </ul>
 *
 * <p>Phase 3（Switch Read）预留：{@link #findByIdentity(String, String, String, int)}
 * 支持按历史 normalizer 版本读取，用于切换前兼容旧版本数据；
 * {@link #countStaleNormalizerVersion()} 提供迁移完成条件
 * 「zero stale normalizer version」的核验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierProductRefService {

    /** 默认编码命名空间（§6.1：只有确认存在互相独立的编码空间时才允许新增其他值）。 */
    public static final String DEFAULT_NAMESPACE = "default";

    private final SupplierProductRefMapper supplierProductRefMapper;

    /**
     * 身份解析入参。
     *
     * <p>{@code sourceSystem} / {@code importBatchId} 只作 provenance；
     * {@code sourceNamespace} 为空时落 {@link #DEFAULT_NAMESPACE}。</p>
     */
    @Value
    public static class ResolveCommand {
        String rspuId;
        String factoryCode;
        String sourceNamespace;
        String sourceSystem;
        String sourceProductCode;
        String sourceProductName;
        String importBatchId;
    }

    /**
     * 按当前活跃 normalizer 版本解析 Supplier Ref 身份（导入查重入口）。
     *
     * @param factoryCode 工厂编码
     * @param sourceNamespace 编码命名空间（空 → {@code default}）
     * @param rawSourceProductCode 源商品编码原文（内部先归一再查）
     * @return 命中的 Supplier Ref
     */
    public Optional<SupplierProductRef> findByIdentity(String factoryCode,
                                                       String sourceNamespace,
                                                       String rawSourceProductCode) {
        return findByIdentity(factoryCode, sourceNamespace, rawSourceProductCode,
            SourceCodeNormalizer.CURRENT_VERSION);
    }

    /**
     * 按指定 normalizer 版本解析 Supplier Ref 身份。
     *
     * <p>Phase 3 切换前兼容读取旧版本数据的入口；不支持的版本直接抛
     * {@link IllegalArgumentException}（不允许静默回落，避免 Identity 被错误归并）。</p>
     *
     * @param factoryCode 工厂编码
     * @param sourceNamespace 编码命名空间（空 → {@code default}）
     * @param rawSourceProductCode 源商品编码原文
     * @param normalizerVersion 归一规则集版本
     * @return 命中的 Supplier Ref
     * @throws IllegalArgumentException 版本不受支持
     */
    public Optional<SupplierProductRef> findByIdentity(String factoryCode,
                                                       String sourceNamespace,
                                                       String rawSourceProductCode,
                                                       int normalizerVersion) {
        if (!StringUtils.hasText(factoryCode) || !StringUtils.hasText(rawSourceProductCode)) {
            return Optional.empty();
        }
        String normalized = SourceCodeNormalizer.normalize(rawSourceProductCode, normalizerVersion);
        return Optional.ofNullable(supplierProductRefMapper.selectByIdentity(
            factoryCode.trim(), resolveNamespace(sourceNamespace), normalized));
    }

    /**
     * 解析或创建 Supplier Ref（Expand 阶段导入链路的写入入口）。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>同键已存在且绑定同一 RSPU：只刷新 provenance
     *       （{@code last_seen_source_system / last_import_batch_id}）并返回既有记录，
     *       不生成新 Ref（§6：不同 source_system 不得拆成两条）；</li>
     *   <li>同键已存在但绑定其他 RSPU：抛 {@link SourceCodeReusedException}（§6.2），不自动改绑；</li>
     *   <li>不存在：以 {@link SourceCodeNormalizer#CURRENT_VERSION} 归一并插入，
     *       持久化 {@code code_normalizer_version}；并发撞唯一约束时回读既有记录走上述分支。</li>
     * </ul>
     *
     * @param command 解析入参
     * @return 命中或新建的 Supplier Ref
     * @throws BusinessException 入参缺失（factory/rspu/编码为空）
     * @throws SourceCodeReusedException 编码已被其他 RSPU 绑定
     */
    public SupplierProductRef resolveOrCreate(ResolveCommand command) {
        validate(command);
        String factoryCode = command.getFactoryCode().trim();
        String namespace = resolveNamespace(command.getSourceNamespace());
        String rawCode = command.getSourceProductCode();
        String normalized = SourceCodeNormalizer.normalize(rawCode);

        SupplierProductRef existing = supplierProductRefMapper.selectByIdentity(factoryCode, namespace, normalized);
        if (existing != null) {
            return reuseExisting(existing, command);
        }

        SupplierProductRef ref = new SupplierProductRef();
        ref.setRspuId(command.getRspuId());
        ref.setFactoryCode(factoryCode);
        ref.setSourceNamespace(namespace);
        ref.setFirstSeenSourceSystem(command.getSourceSystem());
        ref.setLastSeenSourceSystem(command.getSourceSystem());
        ref.setSourceProductCode(rawCode);
        ref.setNormalizedSourceProductCode(normalized);
        ref.setCodeNormalizerVersion(String.valueOf(SourceCodeNormalizer.CURRENT_VERSION));
        ref.setSourceProductName(command.getSourceProductName());
        ref.setFirstImportBatchId(command.getImportBatchId());
        ref.setLastImportBatchId(command.getImportBatchId());
        try {
            supplierProductRefMapper.insert(ref);
            return ref;
        } catch (DuplicateKeyException e) {
            // 并发导入同键撞唯一约束：回读既有记录，按复用语义处理（§77.1）
            SupplierProductRef concurrent = supplierProductRefMapper.selectByIdentity(factoryCode, namespace, normalized);
            if (concurrent == null) {
                throw e;
            }
            log.debug("Supplier Ref 并发插入撞唯一约束，回读既有记录: factory={}, namespace={}, code={}",
                factoryCode, namespace, normalized);
            return reuseExisting(concurrent, command);
        }
    }

    /**
     * 统计 normalizer 版本为空或非当前版本的记录数。
     *
     * <p>迁移完成条件「zero stale normalizer version」（§7 第五步）的核验入口，
     * 供 Backfill / Switch Read 阶段在切换前断言。</p>
     *
     * @return 落后或缺失版本的记录数
     */
    public long countStaleNormalizerVersion() {
        return supplierProductRefMapper.countStaleOrMissingNormalizerVersion(
            String.valueOf(SourceCodeNormalizer.CURRENT_VERSION));
    }

    /**
     * 命中既有记录：同 RSPU 刷新 provenance 后返回；不同 RSPU 抛 SOURCE_CODE_REUSED。
     */
    private SupplierProductRef reuseExisting(SupplierProductRef existing, ResolveCommand command) {
        if (!existing.getRspuId().equals(command.getRspuId())) {
            throw new SourceCodeReusedException(existing, command.getRspuId());
        }
        boolean dirty = false;
        if (StringUtils.hasText(command.getSourceSystem())
            && !command.getSourceSystem().equals(existing.getLastSeenSourceSystem())) {
            existing.setLastSeenSourceSystem(command.getSourceSystem());
            dirty = true;
        }
        if (StringUtils.hasText(command.getImportBatchId())
            && !command.getImportBatchId().equals(existing.getLastImportBatchId())) {
            existing.setLastImportBatchId(command.getImportBatchId());
            dirty = true;
        }
        if (dirty) {
            supplierProductRefMapper.updateById(existing);
        }
        return existing;
    }

    private void validate(ResolveCommand command) {
        if (command == null
            || !StringUtils.hasText(command.getFactoryCode())
            || !StringUtils.hasText(command.getRspuId())
            || !StringUtils.hasText(command.getSourceProductCode())) {
            throw new BusinessException("Supplier Ref 解析缺少必要入参：factory_code / rspu_id / source_product_code 均不能为空");
        }
        if (!StringUtils.hasText(SourceCodeNormalizer.normalize(command.getSourceProductCode()))) {
            throw new BusinessException("源商品编码归一后为空，无法建立 Supplier Ref 身份");
        }
    }

    private String resolveNamespace(String sourceNamespace) {
        return StringUtils.hasText(sourceNamespace) ? sourceNamespace.trim() : DEFAULT_NAMESPACE;
    }
}
