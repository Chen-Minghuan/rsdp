package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Preview Revision 快照哈希材料（设计文档 §47.1.3 SnapshotHashMaterial）。
 *
 * <p>禁止把整行 {@code excel_import_preview_revision} 序列化后计算 Hash（自引用），
 * 本类只持有允许进入 Hash 的业务字段，从类型层面排除了：</p>
 *
 * <pre>
 * snapshot_hash / created_at / created_by / DB generated IDs
 * / runtime status / attempt result / lease / heartbeat
 * </pre>
 *
 * <p>字段与 §47.1.3 示例一一对应；{@code sourceSheetName}、{@code categoryMode}、
 * {@code categoryHint} 允许为 null 并以显式 {@code null} 进入 canonical JSON
 * （Null 是否保留由 Schema 定义，本 Schema 保留）。</p>
 *
 * <p>groups 在构造时按 {@code productGroupKey} 字典序排序（§20.1：快照哈希覆盖按
 * product_group_key 排序的全部 payload_hash），调用方传入顺序不影响结果。</p>
 */
public final class SnapshotHashMaterial {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int hashVersion;
    private final String batchId;
    private final long previewRevision;
    private final int sourceSheetIndex;
    private final String sourceSheetName;
    private final String categoryMode;
    private final String categoryHint;
    private final List<String> candidateCategoryCodes;
    private final String mappingHash;
    private final String normalizerVersion;
    private final List<GroupEntry> groups;

    private SnapshotHashMaterial(Builder builder) {
        this.hashVersion = builder.hashVersion;
        this.batchId = Objects.requireNonNull(builder.batchId, "batchId 不能为 null");
        this.previewRevision = builder.previewRevision;
        this.sourceSheetIndex = builder.sourceSheetIndex;
        this.sourceSheetName = builder.sourceSheetName;
        this.categoryMode = builder.categoryMode;
        this.categoryHint = builder.categoryHint;
        this.candidateCategoryCodes = List.copyOf(builder.candidateCategoryCodes);
        this.mappingHash = Objects.requireNonNull(builder.mappingHash, "mappingHash 不能为 null");
        this.normalizerVersion = Objects.requireNonNull(builder.normalizerVersion, "normalizerVersion 不能为 null");
        List<GroupEntry> sorted = new ArrayList<>(builder.groups);
        sorted.sort(Comparator.comparing(GroupEntry::productGroupKey));
        this.groups = List.copyOf(sorted);
    }

    public static Builder builder(int hashVersion, String batchId, long previewRevision) {
        return new Builder(hashVersion, batchId, previewRevision);
    }

    public int hashVersion() {
        return hashVersion;
    }

    public List<GroupEntry> groups() {
        return groups;
    }

    /**
     * 转为 canonical JSON 树（字段名固定，key 排序由 {@link CanonicalSerializer} 保证）。
     *
     * @return Hash Material 的 JSON 表达
     */
    public JsonNode toCanonicalJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("hashVersion", hashVersion);
        root.put("batchId", batchId);
        root.put("previewRevision", previewRevision);
        root.put("sourceSheetIndex", sourceSheetIndex);
        if (sourceSheetName == null) {
            root.putNull("sourceSheetName");
        } else {
            root.put("sourceSheetName", sourceSheetName);
        }
        ObjectNode categoryPolicy = root.putObject("categoryPolicy");
        if (categoryMode == null) {
            categoryPolicy.putNull("mode");
        } else {
            categoryPolicy.put("mode", categoryMode);
        }
        if (categoryHint == null) {
            categoryPolicy.putNull("categoryHint");
        } else {
            categoryPolicy.put("categoryHint", categoryHint);
        }
        ArrayNode candidates = categoryPolicy.putArray("candidateCategoryCodes");
        candidateCategoryCodes.forEach(candidates::add);
        root.put("mappingHash", mappingHash);
        root.put("normalizerVersion", normalizerVersion);
        ArrayNode groupArray = root.putArray("groups");
        for (GroupEntry group : groups) {
            ObjectNode g = groupArray.addObject();
            g.put("productGroupKey", group.productGroupKey());
            g.put("payloadHashVersion", group.payloadHashVersion());
            g.put("payloadHash", group.payloadHash());
        }
        return root;
    }

    /**
     * 单个 Product Group 的哈希条目（§47.1.3 groups 数组元素）。
     *
     * @param productGroupKey   组内稳定键
     * @param payloadHashVersion payload_hash_version（&gt;0）
     * @param payloadHash       该组的 payload_hash
     */
    public record GroupEntry(String productGroupKey, int payloadHashVersion, String payloadHash) {

        public GroupEntry {
            Objects.requireNonNull(productGroupKey, "productGroupKey 不能为 null");
            if (payloadHashVersion <= 0) {
                throw new IllegalArgumentException("payloadHashVersion 必须 > 0");
            }
            Objects.requireNonNull(payloadHash, "payloadHash 不能为 null");
        }
    }

    /**
     * {@link SnapshotHashMaterial} 构建器。
     */
    public static final class Builder {

        private final int hashVersion;
        private final String batchId;
        private final long previewRevision;
        private int sourceSheetIndex;
        private String sourceSheetName;
        private String categoryMode;
        private String categoryHint;
        private final List<String> candidateCategoryCodes = new ArrayList<>();
        private String mappingHash;
        private String normalizerVersion;
        private final List<GroupEntry> groups = new ArrayList<>();

        private Builder(int hashVersion, String batchId, long previewRevision) {
            if (hashVersion <= 0) {
                throw new IllegalArgumentException("hashVersion 必须 > 0（§47.1.6）");
            }
            this.hashVersion = hashVersion;
            this.batchId = batchId;
            this.previewRevision = previewRevision;
        }

        public Builder sourceSheetIndex(int sourceSheetIndex) {
            this.sourceSheetIndex = sourceSheetIndex;
            return this;
        }

        public Builder sourceSheetName(String sourceSheetName) {
            this.sourceSheetName = sourceSheetName;
            return this;
        }

        public Builder categoryPolicy(String categoryMode, String categoryHint,
                                      List<String> candidateCategoryCodes) {
            this.categoryMode = categoryMode;
            this.categoryHint = categoryHint;
            this.candidateCategoryCodes.clear();
            if (candidateCategoryCodes != null) {
                this.candidateCategoryCodes.addAll(candidateCategoryCodes);
            }
            return this;
        }

        public Builder mappingHash(String mappingHash) {
            this.mappingHash = mappingHash;
            return this;
        }

        public Builder normalizerVersion(String normalizerVersion) {
            this.normalizerVersion = normalizerVersion;
            return this;
        }

        public Builder addGroup(String productGroupKey, int payloadHashVersion, String payloadHash) {
            this.groups.add(new GroupEntry(productGroupKey, payloadHashVersion, payloadHash));
            return this;
        }

        public SnapshotHashMaterial build() {
            return new SnapshotHashMaterial(this);
        }
    }
}
