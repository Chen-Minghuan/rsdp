package com.rsdp.service;

import com.rsdp.entity.ExcelImportSensitiveValue;
import com.rsdp.mapper.ExcelImportSensitiveValueMapper;
import com.rsdp.supplychain.hash.CanonicalSerializer;
import com.rsdp.supplychain.hash.SensitiveValueHmac;
import com.rsdp.util.AesEncryptionUtil;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;

/**
 * 敏感 Preview / Issue 值写入服务（设计文档 v3.3 §24.2 / §42.5 / §43，P0-B3）。
 *
 * <p>职责：采购成本等敏感值不落 {@code normalized_payload / resolution_value} 明文，
 * 统一经本服务写入 {@code excel_import_sensitive_value}（密文 + HMAC），调用方只持有
 * Sensitive Ref（{@code SENS-...}）。</p>
 *
 * <p>职责分离（§24.2）：</p>
 * <ul>
 *   <li>ciphertext = 可恢复原值，走项目统一加密体系（当前 AES-256-GCM 单 Key，
 *       {@code encryption_key_version} 固定 1；versioned keyring 升级归 P0-E，§43.2）；</li>
 *   <li>value_hmac = 幂等 / 变更检测 / 安全等值比较，HMAC-SHA-256 + 版本化 keyring
 *       （{@link SensitiveValueHmac}）：签发永远用 Active 版本；历史
 *       {@code value_hmac + hmac_key_version} 是固定 Hash Material，本服务不提供
 *       任何 UPDATE 入口，Key 旋转不改写历史（§42.5）。</li>
 * </ul>
 *
 * <p>日志纪律（§43 规则 7/11）：本类只记录字段路径与 Key Version，永不打印明文、
 * 密文或摘要值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportSensitiveValueService {

    /** 当前 AES 加密体系的固定版本号（versioned keyring 升级归 P0-E，§43.2）。 */
    public static final int CURRENT_ENCRYPTION_KEY_VERSION = 1;

    /** 数据分级：精确成本（§42.2）。 */
    public static final String CLASSIFICATION_COST_PRICE_EXACT = "cost_price_exact";

    /** Sensitive Ref 的 ID 前缀（Snapshot / Resolution 中 {"__sensitiveRef": "SENS-..."}）。 */
    public static final String SENSITIVE_REF_PREFIX = "SENS";

    private final ExcelImportSensitiveValueMapper sensitiveValueMapper;
    private final SensitiveValueHmac sensitiveValueHmac;

    /** Prepare encrypted source rows for one immutable product group. The caller inserts it in the revision transaction. */
    public ExcelImportSensitiveValue prepareGroupSourceRows(String batchId, long revision,
                                                             String groupId, String sourceRowsJson) {
        Objects.requireNonNull(sourceRowsJson, "sourceRowsJson 不能为 null");
        SensitiveValueHmac.SignedValue signed = sensitiveValueHmac.sign(sourceRowsJson);
        ExcelImportSensitiveValue row = new ExcelImportSensitiveValue();
        row.setSensitiveValueId(IdGenerator.generate(SENSITIVE_REF_PREFIX));
        row.setBatchId(batchId);
        row.setPreviewRevision(revision);
        row.setGroupId(groupId);
        row.setValuePath("/sourceRows");
        row.setCiphertext(AesEncryptionUtil.encryptText(sourceRowsJson));
        row.setEncryptionKeyVersion(CURRENT_ENCRYPTION_KEY_VERSION);
        row.setValueHmac(signed.valueHmac());
        row.setHmacKeyVersion(signed.hmacKeyVersion());
        row.setDataClassification(CLASSIFICATION_COST_PRICE_EXACT);
        row.setRetentionUntil(LocalDateTime.now().plusDays(365));
        return row;
    }

    /** Insert a prepared group value after its owning revision and group exist. */
    public void insertPrepared(ExcelImportSensitiveValue row) {
        sensitiveValueMapper.insert(row);
    }

    /** Read and authenticate the source rows bound to a specific group and revision. */
    public String readGroupSourceRows(String batchId, long revision, String groupId, String sensitiveRef) {
        ExcelImportSensitiveValue row = sensitiveValueMapper.selectById(sensitiveRef);
        if (row == null || !Objects.equals(batchId, row.getBatchId())
            || !Objects.equals(revision, row.getPreviewRevision())
            || !Objects.equals(groupId, row.getGroupId())
            || !"/sourceRows".equals(row.getValuePath())) {
            throw new IllegalStateException("Preview Snapshot 源行引用无效");
        }
        String plain = AesEncryptionUtil.decryptText(row.getCiphertext());
        if (!sensitiveValueHmac.verify(plain, row.getValueHmac(), row.getHmacKeyVersion())) {
            throw new IllegalStateException("Preview Snapshot 源行完整性校验失败");
        }
        return plain;
    }

    /** List immutable sensitive hash inputs for a revision. */
    public List<ExcelImportSensitiveValue> listGroupValues(String batchId, long revision) {
        return sensitiveValueMapper.listGroupOwnedByRevision(batchId, revision);
    }
}
