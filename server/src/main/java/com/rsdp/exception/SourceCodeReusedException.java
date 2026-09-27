package com.rsdp.exception;

import com.rsdp.entity.SupplierProductRef;
import lombok.Getter;

/**
 * 供应商编码复用冲突（设计文档 v3.3 §6.2 {@code SOURCE_CODE_REUSED}）。
 *
 * <p>同一 {@code (factory_code, source_namespace, normalized_source_product_code)}
 * 已绑定到某 RSPU 后，新导入内容指向另一个商品时不允许自动改绑，必须抛出本异常，
 * 语义为 {@code severity = blocker / scope = product_blocking}，进入人工治理。
 * Supplier Ref 是历史可追溯身份映射，不能因供应商复用旧编码而静默篡改历史商品归属。</p>
 */
@Getter
public class SourceCodeReusedException extends BusinessException {

    /** Issue Code，与 §6.2 一致。 */
    public static final String ISSUE_CODE = "SOURCE_CODE_REUSED";
    /** Issue 严重级别。 */
    public static final String SEVERITY = "blocker";
    /** Issue 作用域。 */
    public static final String SCOPE = "product_blocking";

    private final transient SupplierProductRef existingRef;
    private final String incomingRspuId;

    public SourceCodeReusedException(SupplierProductRef existingRef, String incomingRspuId) {
        super(409, String.format(
            "%s: 工厂 %s 编码 '%s' 已绑定 RSPU %s，不允许自动改绑到 %s，需人工治理",
            ISSUE_CODE,
            existingRef.getFactoryCode(),
            existingRef.getSourceProductCode(),
            existingRef.getRspuId(),
            incomingRspuId));
        this.existingRef = existingRef;
        this.incomingRspuId = incomingRspuId;
    }

    public String getIssueCode() {
        return ISSUE_CODE;
    }

    public String getSeverity() {
        return SEVERITY;
    }

    public String getScope() {
        return SCOPE;
    }
}
