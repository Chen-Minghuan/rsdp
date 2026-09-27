package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportPreviewRevision;
import com.rsdp.entity.ExcelImportProductGroup;
import com.rsdp.entity.ExcelImportSensitiveValue;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.PreviewRevisionConflictException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ExcelImportPreviewRevisionMapper;
import com.rsdp.mapper.ExcelImportProductGroupMapper;
import com.rsdp.supplychain.hash.PayloadHashMaterial;
import com.rsdp.supplychain.hash.SnapshotHashMaterial;
import com.rsdp.supplychain.hash.SnapshotHasher;
import com.rsdp.dto.request.CategoryMode;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Excel 导入 Preview Snapshot / Revision 服务（P0-B1 底座，设计文档 v3.3 §20 / §21 / §22 / §45 / §46 / §47）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>以 per-sheet batch 为边界创建 immutable Snapshot：{@link #createNextRevision}
 *       一次事务内完成「锁 batch 行 → 校验 expectedRevision（§47 Optimistic Lock）→
 *       计算 payload_hash / snapshot_hash（§47.1.2 / §47.1.3）→ 插入 Revision Header 与
 *       Product Group → CAS 推进 batch.preview_revision 指针（§45 / §46）」；</li>
 *   <li>读取：{@link #getSnapshot} / {@link #getCurrentSnapshot} 供 Confirm / Import；
 *       {@link #loadSourceRows} 从已确认 Revision 的加密源行恢复执行输入；</li>
 *   <li>完整性校验：{@link #verifySnapshotHash} 重放快照哈希（§77.3 confirmed snapshot hash 校验）。</li>
 * </ul>
 *
 * <p>不可变性：本服务只 INSERT 新 Revision，不提供任何 UPDATE / DELETE 入口（§20.1）；
 * 生产库的 Runtime Role REVOKE 与受限 GC 函数属 §85.2 B 类上线门禁运维项。</p>
 *
 * <p>后续挂载点：</p>
 * <ul>
 *   <li>B2：09 方案 SINGLE / MIXED 归一结果以 {@link CreateRevisionCommand} 物化进 Snapshot，
 *       行级 {@code finalCategoryCode} 进 {@code normalized_payload}；</li>
 *   <li>报价等字段的逐字段敏感值引用仍待 P0-F；现阶段源行整体加密，
 *       引用和 HMAC 参与 payload_hash；</li>
 *   <li>B3：Issue / Confirm / Revalidate——{@code confirmed_revision} 指针推进与
 *       {@code 410 snapshot_expired} 判定；</li>
 *   <li>P0-F：Import Attempt / AttemptItem 只读本 Snapshot，不回写。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportPreviewSnapshotService {

    /** Snapshot Hash 协议版本（§47.1.6，excel_import_preview_revision.hash_version）。 */
    public static final int HASH_VERSION = 1;

    /** Payload Hash 协议版本（§47.1.6，excel_import_product_group.payload_hash_version）。 */
    public static final int PAYLOAD_HASH_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExcelImportBatchMapper batchMapper;
    private final ExcelImportPreviewRevisionMapper revisionMapper;
    private final ExcelImportProductGroupMapper groupMapper;
    private final ExcelImportSensitiveValueService sensitiveValueService;

    /**
     * 敏感值哈希条目输入（§47.1.2）。B1 阶段敏感值表尚未落地，正常为空；
     * B2 写入 §24.2 excel_import_sensitive_value 后由调用方提供。
     */
    @Value
    public static class SensitiveValueInput {
        String valuePath;
        String sensitiveRef;
        String valueHmac;
        int hmacKeyVersion;
    }

    /**
     * 单个产品组的 immutable 输入（§21）。
     */
    @Value
    public static class GroupInput {
        /** 组内稳定键（Revision 内唯一），Issue Target / Attempt 的稳定引用 */
        String productGroupKey;
        /** 非敏感/普通级别的标准化负载（敏感值只允许 Sensitive Ref） */
        JsonNode normalizedPayload;
        /** 该组的敏感值哈希条目（可为空列表） */
        List<SensitiveValueInput> sensitiveValues;
        /** Full source rows, encrypted outside normalized_payload before persistence. */
        JsonNode sourceRows;

        public GroupInput(String productGroupKey, JsonNode normalizedPayload,
                          List<SensitiveValueInput> sensitiveValues) {
            this(productGroupKey, normalizedPayload, sensitiveValues, null);
        }

        public GroupInput(String productGroupKey, JsonNode normalizedPayload,
                          List<SensitiveValueInput> sensitiveValues, JsonNode sourceRows) {
            this.productGroupKey = productGroupKey;
            this.normalizedPayload = normalizedPayload;
            this.sensitiveValues = sensitiveValues;
            this.sourceRows = sourceRows;
        }
    }

    /**
     * Revision 创建命令（§46 / §47 的编辑命令物化）。
     *
     * <p>{@code expectedRevision} 为乐观锁版本号：与 batch 当前
     * {@code preview_revision} 不一致时抛 409 {@code preview_revision_conflict}；
     * {@code null} 表示期望尚无快照（首次创建）。</p>
     */
    @Value
    public static class CreateRevisionCommand {
        String batchId;
        Long expectedRevision;

        Integer sourceSheetIndex;
        String sourceSheetName;

        /** null = 未选导入方式的旧路径（09 方案 §16 缺省兼容） */
        CategoryMode categoryMode;
        String categoryHint;
        List<String> candidateCategoryCodes;

        String mappingHash;
        String normalizerVersion;

        List<GroupInput> groups;
        String createdBy;
    }

    /**
     * 一份完整 Snapshot 的只读视图（Revision Header + 全部 Product Group）。
     */
    @Value
    public static class SnapshotView {
        ExcelImportPreviewRevision revision;
        List<ExcelImportProductGroup> groups;
    }

    /**
     * 创建下一个 immutable Preview Revision（§46：任何编辑必须 revision + 1）。
     *
     * <p>并发语义（§47）：同事务内先 {@code SELECT ... FOR UPDATE} 锁 batch 行，
     * 校验 {@code expectedRevision} 后插入新 Revision / Group，最后以
     * {@code preview_revision IS NOT DISTINCT FROM expected} 的 CAS 推进指针；
     * 任一环节发现 Revision 已被推进即抛 {@link PreviewRevisionConflictException}
     * （409），整个事务回滚，不产生半截 Snapshot。</p>
     *
     * @param command 编辑命令物化结果
     * @return 新创建的 Revision Header
     * @throws ResourceNotFoundException        batch 不存在
     * @throws BusinessException                入参 / 品类策略不合法（400）
     * @throws PreviewRevisionConflictException Revision 冲突（409）
     */
    @Transactional
    public ExcelImportPreviewRevision createNextRevision(CreateRevisionCommand command) {
        validate(command);

        ExcelImportBatch batch = batchMapper.selectForUpdate(command.getBatchId());
        if (batch == null) {
            throw new ResourceNotFoundException("Excel 导入批次不存在: " + command.getBatchId());
        }

        Long current = batch.getPreviewRevision();
        if (!Objects.equals(current, command.getExpectedRevision())) {
            throw new PreviewRevisionConflictException(command.getBatchId(),
                command.getExpectedRevision(), current);
        }
        long newRevision = current == null ? 1L : current + 1;

        List<String> candidates = command.getCandidateCategoryCodes() == null
            ? List.of() : List.copyOf(command.getCandidateCategoryCodes());
        String categoryMode = command.getCategoryMode() == null
            ? null : command.getCategoryMode().name();

        // 逐组计算 payload_hash（§47.1.2）
        List<ExcelImportProductGroup> groups = new ArrayList<>();
        SnapshotHashMaterial.Builder material = SnapshotHashMaterial
            .builder(HASH_VERSION, command.getBatchId(), newRevision)
            .sourceSheetIndex(command.getSourceSheetIndex() == null ? 0 : command.getSourceSheetIndex())
            .sourceSheetName(command.getSourceSheetName())
            .categoryPolicy(categoryMode, command.getCategoryHint(), candidates)
            .mappingHash(command.getMappingHash())
            .normalizerVersion(command.getNormalizerVersion());
        List<ExcelImportSensitiveValue> sourceValues = new ArrayList<>();
        for (GroupInput input : command.getGroups()) {
            String groupId = IdGenerator.importGroupId();
            JsonNode payload = input.getNormalizedPayload().deepCopy();
            List<SensitiveValueInput> sensitiveInputs = new ArrayList<>(
                input.getSensitiveValues() == null ? List.of() : input.getSensitiveValues());
            if (input.getSourceRows() != null) {
                ExcelImportSensitiveValue source = sensitiveValueService.prepareGroupSourceRows(
                    command.getBatchId(), newRevision, groupId, input.getSourceRows().toString());
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                    .put("sourceRowsRef", source.getSensitiveValueId());
                sensitiveInputs.add(new SensitiveValueInput(source.getValuePath(),
                    source.getSensitiveValueId(), source.getValueHmac(), source.getHmacKeyVersion()));
                sourceValues.add(source);
            }
            String payloadHash = toPayloadHashMaterial(payload, sensitiveInputs).hash();
            material.addGroup(input.getProductGroupKey(), PAYLOAD_HASH_VERSION, payloadHash);

            ExcelImportProductGroup group = new ExcelImportProductGroup();
            group.setGroupId(groupId);
            group.setBatchId(command.getBatchId());
            group.setPreviewRevision(newRevision);
            group.setProductGroupKey(input.getProductGroupKey());
            group.setNormalizedPayload(payload.toString());
            group.setPayloadHashVersion(PAYLOAD_HASH_VERSION);
            group.setPayloadHash(payloadHash);
            group.setNormalizerVersion(command.getNormalizerVersion());
            group.setMappingHash(command.getMappingHash());
            groups.add(group);
        }

        ExcelImportPreviewRevision revision = new ExcelImportPreviewRevision();
        revision.setBatchId(command.getBatchId());
        revision.setPreviewRevision(newRevision);
        revision.setSourceSheetIndex(command.getSourceSheetIndex() == null ? 0 : command.getSourceSheetIndex());
        revision.setSourceSheetName(command.getSourceSheetName());
        revision.setCategoryMode(categoryMode);
        revision.setCategoryHint(command.getCategoryHint());
        revision.setCandidateCategoryCodes(toJson(candidates));
        revision.setMappingHash(command.getMappingHash());
        revision.setNormalizerVersion(command.getNormalizerVersion());
        revision.setHashVersion(HASH_VERSION);
        revision.setSnapshotHash(SnapshotHasher.snapshotHash(material.build()));
        revision.setCreatedBy(command.getCreatedBy());

        try {
            revisionMapper.insert(revision);
        } catch (DuplicateKeyException e) {
            // 防御：行锁之外仍撞复合主键（如锁外直写），按 Revision 冲突处理（§47）
            throw new PreviewRevisionConflictException(command.getBatchId(),
                command.getExpectedRevision(), current);
        }
        for (ExcelImportProductGroup group : groups) {
            groupMapper.insert(group);
        }
        for (ExcelImportSensitiveValue source : sourceValues) {
            sensitiveValueService.insertPrepared(source);
        }

        int advanced = batchMapper.advancePreviewRevision(command.getBatchId(),
            command.getExpectedRevision(), newRevision,
            command.getMappingHash(), command.getNormalizerVersion());
        if (advanced != 1) {
            throw new PreviewRevisionConflictException(command.getBatchId(),
                command.getExpectedRevision(), current);
        }

        log.info("Preview Snapshot 已创建: batchId={}, revision={}, groups={}, snapshotHash={}",
            command.getBatchId(), newRevision, groups.size(), revision.getSnapshotHash());
        return revision;
    }

    /**
     * 读取指定 Revision 的完整 Snapshot（§22 正式 Import 的 LOAD 入口）。
     *
     * @param batchId  批次 ID
     * @param revision Revision 号
     * @return Snapshot 视图；不存在返回 empty
     */
    public Optional<SnapshotView> getSnapshot(String batchId, long revision) {
        ExcelImportPreviewRevision header = revisionMapper.selectByRevision(batchId, revision);
        if (header == null) {
            return Optional.empty();
        }
        return Optional.of(new SnapshotView(header, groupMapper.listByRevision(batchId, revision)));
    }

    /** Load the exact source cell values sealed in a revision, ordered by physical Excel row. */
    public List<Map<String, String>> loadSourceRows(SnapshotView snapshot) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ExcelImportProductGroup group : snapshot.getGroups()) {
            try {
                JsonNode sourceRows = loadGroupSourceRows(group);
                if (sourceRows == null) {
                    throw new BusinessException(409, "当前 Preview Revision 未固化源行，请重新保存预览后确认导入");
                }
                rows.addAll(MAPPER.readValue(sourceRows.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {}));
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("Preview Snapshot 源行读取或完整性校验失败");
            }
        }
        rows.sort(Comparator.comparingInt(row -> Integer.parseInt(row.get("__rowIndex__"))));
        return rows;
    }

    /** Read a group's sealed source rows for import or for a new issue-resolution revision. */
    public JsonNode loadGroupSourceRows(ExcelImportProductGroup group) {
        try {
            JsonNode payload = MAPPER.readTree(group.getNormalizedPayload());
            JsonNode ref = payload.get("sourceRowsRef");
            if (ref == null || !ref.isTextual()) {
                return null;
            }
            String plain = sensitiveValueService.readGroupSourceRows(group.getBatchId(),
                group.getPreviewRevision(), group.getGroupId(), ref.asText());
            JsonNode rows = MAPPER.readTree(plain);
            if (!rows.isArray()) {
                throw new BusinessException("Preview Snapshot 源行格式错误");
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Preview Snapshot 源行读取或完整性校验失败");
        }
    }

    /**
     * 读取 batch 当前指针指向的 Snapshot；batch 不存在或尚无快照返回 empty。
     */
    public Optional<SnapshotView> getCurrentSnapshot(String batchId) {
        ExcelImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getPreviewRevision() == null) {
            return Optional.empty();
        }
        return getSnapshot(batchId, batch.getPreviewRevision());
    }

    /**
     * 重放校验 Snapshot 哈希（§77.3 confirmed snapshot hash 校验 / §47.1.3）。
     *
     * <p>从库里存出的 Revision Header 与 Product Group 重建 Hash Material，
     * 与存储的 {@code snapshot_hash} 做常量时间比较；任何字段被篡改都会失配。</p>
     *
     * @param batchId  批次 ID
     * @param revision Revision 号
     * @return 哈希一致返回 true；Revision 不存在返回 false
     */
    public boolean verifySnapshotHash(String batchId, long revision) {
        Optional<SnapshotView> snapshot = getSnapshot(batchId, revision);
        if (snapshot.isEmpty()) {
            return false;
        }
        List<ExcelImportSensitiveValue> values = sensitiveValueService.listGroupValues(batchId, revision);
        Map<String, List<SensitiveValueInput>> valuesByGroup = new HashMap<>();
        if (values != null) {
            for (ExcelImportSensitiveValue value : values) {
                valuesByGroup.computeIfAbsent(value.getGroupId(), ignored -> new ArrayList<>())
                    .add(new SensitiveValueInput(value.getValuePath(), value.getSensitiveValueId(),
                        value.getValueHmac(), value.getHmacKeyVersion()));
            }
        }
        for (ExcelImportProductGroup group : snapshot.get().getGroups()) {
            try {
                JsonNode payload = MAPPER.readTree(group.getNormalizedPayload());
                String actual = toPayloadHashMaterial(payload,
                    valuesByGroup.getOrDefault(group.getGroupId(), List.of())).hash();
                if (!SnapshotHasher.matches(group.getPayloadHash(), actual)) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        SnapshotHashMaterial material = rebuildMaterial(snapshot.get());
        String recomputed = SnapshotHasher.snapshotHash(material);
        return SnapshotHasher.matches(snapshot.get().getRevision().getSnapshotHash(), recomputed);
    }

    /** 从存储行重建 Hash Material（verify 用，字段与创建时一一对应）。 */
    private SnapshotHashMaterial rebuildMaterial(SnapshotView snapshot) {
        ExcelImportPreviewRevision revision = snapshot.getRevision();
        SnapshotHashMaterial.Builder material = SnapshotHashMaterial
            .builder(revision.getHashVersion(), revision.getBatchId(), revision.getPreviewRevision())
            .sourceSheetIndex(revision.getSourceSheetIndex() == null ? 0 : revision.getSourceSheetIndex())
            .sourceSheetName(revision.getSourceSheetName())
            .categoryPolicy(revision.getCategoryMode(), revision.getCategoryHint(),
                fromJsonStringList(revision.getCandidateCategoryCodes()))
            .mappingHash(revision.getMappingHash())
            .normalizerVersion(revision.getNormalizerVersion());
        for (ExcelImportProductGroup group : snapshot.getGroups()) {
            material.addGroup(group.getProductGroupKey(), group.getPayloadHashVersion(), group.getPayloadHash());
        }
        return material.build();
    }

    private PayloadHashMaterial toPayloadHashMaterial(JsonNode payload,
                                                       List<SensitiveValueInput> values) {
        List<PayloadHashMaterial.SensitiveValueEntry> entries = new ArrayList<>();
        if (values != null) {
            for (SensitiveValueInput value : values) {
                entries.add(new PayloadHashMaterial.SensitiveValueEntry(
                    value.getValuePath(), value.getSensitiveRef(),
                    value.getValueHmac(), value.getHmacKeyVersion()));
            }
        }
        return PayloadHashMaterial.of(PAYLOAD_HASH_VERSION, payload, entries);
    }

    /**
     * 入参与品类策略校验（与 DB CHECK 同口径，提前给出可读错误）：
     * SINGLE 必须有 hint 且候选集为空；MIXED 必须无 hint 且候选集 ≥ 2（§20.1 ck_import_preview_category_policy）。
     */
    private void validate(CreateRevisionCommand command) {
        if (command == null
            || !StringUtils.hasText(command.getBatchId())
            || !StringUtils.hasText(command.getMappingHash())
            || !StringUtils.hasText(command.getNormalizerVersion())) {
            throw new BusinessException("Snapshot 创建缺少必要入参：batch_id / mapping_hash / normalizer_version 均不能为空");
        }
        if (command.getGroups() == null) {
            throw new BusinessException("Snapshot 创建缺少必要入参：groups 不能为 null（可为空列表）");
        }
        Set<String> keys = new HashSet<>();
        for (GroupInput group : command.getGroups()) {
            if (group == null || !StringUtils.hasText(group.getProductGroupKey())
                || group.getNormalizedPayload() == null) {
                throw new BusinessException("Product Group 缺少必要入参：product_group_key / normalized_payload 均不能为空");
            }
            if (group.getSourceRows() != null
                && (!group.getNormalizedPayload().isObject() || !group.getSourceRows().isArray())) {
                throw new BusinessException("Product Group 源行需要 JSON 数组与对象负载");
            }
            if (!keys.add(group.getProductGroupKey())) {
                throw new BusinessException("同一 Revision 内 product_group_key 重复: " + group.getProductGroupKey());
            }
        }
        if (command.getCategoryMode() == CategoryMode.SINGLE) {
            if (!StringUtils.hasText(command.getCategoryHint())) {
                throw new BusinessException("SINGLE 模式必须提供 category_hint（§20.1）");
            }
            if (command.getCandidateCategoryCodes() != null && !command.getCandidateCategoryCodes().isEmpty()) {
                throw new BusinessException("SINGLE 模式不允许携带候选品类集（§20.1）");
            }
        } else if (command.getCategoryMode() == CategoryMode.MIXED) {
            if (StringUtils.hasText(command.getCategoryHint())) {
                throw new BusinessException("MIXED 模式不允许携带 category_hint（§20.1）");
            }
            if (command.getCandidateCategoryCodes() == null || command.getCandidateCategoryCodes().size() < 2) {
                throw new BusinessException("MIXED 模式候选品类集必须 ≥ 2 个（§20.1）");
            }
        }
    }

    private String toJson(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("候选品类集序列化失败", e);
        }
    }

    private List<String> fromJsonStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> values = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(json)) {
                values.add(node.isNull() ? null : node.asText());
            }
            return values;
        } catch (Exception e) {
            throw new IllegalStateException("候选品类集反序列化失败", e);
        }
    }
}
