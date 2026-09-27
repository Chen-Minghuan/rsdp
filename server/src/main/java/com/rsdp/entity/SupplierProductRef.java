package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商源商品身份映射（设计文档 v3.3 §6 {@code supplier_product_ref}）。
 *
 * <p>Identity 唯一键为 {@code (factory_code, source_namespace, normalized_source_product_code)}；
 * {@code source_system} 只记录 provenance（数据从哪个系统/渠道进入），不参与身份判定：
 * 同一工厂同一商品编码无论从 Excel 还是 ERP 进入，都必须命中同一条记录。</p>
 *
 * <p>{@code source_product_code} 保存工厂原文（绝不改写），
 * {@code normalized_source_product_code} 保存按 {@code code_normalizer_version}
 * 指定规则集归一后的值（{@link com.rsdp.util.SourceCodeNormalizer}）。</p>
 */
@Data
@TableName("supplier_product_ref")
public class SupplierProductRef {

    /** 自增记录 ID（§3.2 BIGSERIAL）。 */
    @TableId(type = IdType.AUTO)
    private Long refId;

    /** 绑定的平台商品（RSPU）主键。 */
    private String rspuId;

    /** 工厂编码（当前 Supplier Source 仅支持 Factory，§6）。 */
    private String factoryCode;

    /** 供应商编码所属命名空间，参与 Identity；默认 {@code default}（§6.1）。 */
    private String sourceNamespace;

    /** 首次进入系统时的来源系统/渠道（provenance，不参与 Identity）。 */
    private String firstSeenSourceSystem;

    /** 最近一次见到该编码时的来源系统/渠道（provenance，不参与 Identity）。 */
    private String lastSeenSourceSystem;

    /** 工厂/供应商命名空间内的商品编码原文，原样保存不修改。 */
    private String sourceProductCode;

    /** 按 code_normalizer_version 规则集归一后的编码，参与 Identity 唯一键。 */
    private String normalizedSourceProductCode;

    /** 归一规则集版本（对应 {@link com.rsdp.util.SourceCodeNormalizer} 的版本号字符串）。 */
    private String codeNormalizerVersion;

    /** 供应商侧商品名称（展示辅助，不参与 Identity）。 */
    private String sourceProductName;

    /** 首次引入该编码的导入批次。 */
    private String firstImportBatchId;

    /** 最近一次见到该编码的导入批次。 */
    private String lastImportBatchId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
