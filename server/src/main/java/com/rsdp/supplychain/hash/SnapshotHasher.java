package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.util.ContentHashes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 普通完整性 / 幂等 Hash（设计文档 §47.1）：canonical bytes 上的公开 SHA-256。
 *
 * <p>适用：payload_hash / snapshot_hash / mapping_hash / policy_hash 及
 * variantKey / quoteKey 等以「内容完整性 / 确定性」为目标的摘要。
 * 敏感低熵值（如精确采购成本）禁止使用本类，必须使用
 * {@link SensitiveValueHmac}（§47.1.7）。</p>
 *
 * <p>hashVersion 概念（§47.1.6）：数据库保存实际版本；Canonical 规则未来变更时必须升级
 * 版本，不能静默改变历史 Revision / Attempt 的 Hash 结果。本类要求调用方显式传入
 * hashVersion 且 &gt;0，hashVersion 本身由调用方放入 Material（见
 * {@link SnapshotHashMaterial#toCanonicalJson()} 的 {@code hashVersion} 字段）。</p>
 */
public final class SnapshotHasher {

    private SnapshotHasher() {
        // 工具类禁止实例化
    }

    /**
     * 对任意 Hash Material 计算版本化 SHA-256。
     *
     * @param hashVersion 哈希协议版本，必须 &gt; 0
     * @param material    Hash Material（先经 {@link CanonicalSerializer} canonical 化）
     * @return 64 位小写十六进制摘要
     */
    public static String hash(int hashVersion, JsonNode material) {
        if (hashVersion <= 0) {
            throw new IllegalArgumentException("hashVersion 必须 > 0（§47.1.6）");
        }
        Objects.requireNonNull(material, "Hash Material 不能为 null");
        return ContentHashes.sha256Hex(CanonicalSerializer.canonicalBytes(material));
    }

    /**
     * 计算 Preview Revision 的 snapshot_hash（§47.1.3）。
     *
     * @param material 快照哈希材料（类型层面已排除 snapshot_hash 自身与 Runtime 字段）
     * @return 64 位小写十六进制摘要
     */
    public static String snapshotHash(SnapshotHashMaterial material) {
        Objects.requireNonNull(material, "SnapshotHashMaterial 不能为 null");
        return hash(material.hashVersion(), material.toCanonicalJson());
    }

    /**
     * 常量时间校验摘要是否匹配（confirm 时重放校验，§77.3 confirmed snapshot hash 校验）。
     *
     * @param expectedHex 期望的十六进制摘要
     * @param actualHex   实际算出的十六进制摘要
     * @return 是否一致
     */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expectedHex.getBytes(StandardCharsets.UTF_8),
            actualHex.getBytes(StandardCharsets.UTF_8));
    }
}
