package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.response.FloorPlanActivationReadiness;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CadAnalysisFeedback;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanFurniture;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.FloorPlanSceneRevision;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.CadAnalysisFeedbackMapper;
import com.rsdp.mapper.FloorPlanFurnitureMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 户型 Scene Revision 服务（V20，设计文档 V1.2.3 §8 / §41 / §44 不变量）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>Revision 创建（PROCESSING）与终态落库（READY 快照冻结 / FAILED）；</li>
 *   <li>Revision 行级锁（§8.4.6 锁顺序：先 analysis 后 revision，本类只提供后者）；</li>
 *   <li>Scene Activation Validator（§8.5.1）与 READY/EDITING ↔ READY_TO_ACTIVATE 自动迁移；</li>
 *   <li>工作 Revision 派生（MANUAL_EDIT，复制源 Revision 对象并生成新对象 ID）；</li>
 *   <li>Recognition Diff 反馈落库（cad_analysis_feedback）。</li>
 * </ul>
 *
 * <p>本类不感知归属校验与响应装配（由 {@link FloorPlanService} 负责）；
 * 所有对象读写强制 (analysis_id, analysis_revision) 双条件（Invariant 1/2/3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanSceneRevisionService {

    /** Revision 可编辑状态（Invariant 10：ACTIVE/SUPERSEDED/FAILED/PROCESSING 不可原地编辑）。 */
    public static final Set<String> EDITABLE_STATUSES = Set.of(
        FloorPlanSceneRevision.STATUS_READY,
        FloorPlanSceneRevision.STATUS_EDITING,
        FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);

    private final FloorPlanSceneRevisionMapper revisionMapper;
    private final FloorPlanRoomMapper roomMapper;
    private final FloorPlanFurnitureMapper furnitureMapper;
    private final CadAnalysisFeedbackMapper feedbackMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Revision 生命周期：创建 / READY / FAILED
    // ------------------------------------------------------------------

    /**
     * 创建 PROCESSING Revision（analyze/retry/target-scene 提交时同步创建，
     * 序号 = latest+1，新分析为 1），并推进 analysis.latest_analysis_revision。
     *
     * @param analysis       分析记录（已落库；本方法内 updateById 推进 latest 指针）
     * @param trigger        触发来源（INITIAL_PARSE/REPARSE/TARGET_SCENE_SWITCH）
     * @param parentRevision 父 Revision 序号，可空
     * @param targetSceneId  目标 Scene ID（target-scene 切换时确定），可空
     * @param operator       操作人
     * @return 新建 Revision
     */
    public FloorPlanSceneRevision createProcessingRevision(FloorPlanAnalysis analysis, String trigger,
                                                           Integer parentRevision, String targetSceneId,
                                                           String operator) {
        int revisionNo = (analysis.getLatestAnalysisRevision() == null ? 0
            : analysis.getLatestAnalysisRevision()) + 1;
        FloorPlanSceneRevision revision = new FloorPlanSceneRevision();
        revision.setAnalysisId(analysis.getAnalysisId());
        revision.setAnalysisRevision(revisionNo);
        revision.setParentAnalysisRevision(parentRevision);
        revision.setRevisionTrigger(trigger);
        revision.setTargetSceneId(targetSceneId);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_PROCESSING);
        revision.setRoomRevision(1);
        revision.setFurnitureRevision(1);
        revision.setCreatedBy(operator);
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);

        analysis.setLatestAnalysisRevision(revisionNo);
        log.info("Scene Revision 已创建，analysisId={}，revision={}，trigger={}",
            analysis.getAnalysisId(), revisionNo, trigger);
        return revision;
    }

    /**
     * 按双条件读取 Revision；不存在返回 null。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo Revision 序号
     * @return Revision 或 null
     */
    public FloorPlanSceneRevision getRevision(String analysisId, Integer revisionNo) {
        if (revisionNo == null) {
            return null;
        }
        return revisionMapper.selectOne(new QueryWrapper<FloorPlanSceneRevision>()
            .eq("analysis_id", analysisId)
            .eq("analysis_revision", revisionNo));
    }

    /**
     * Revision 行级锁（调用前必须先锁 floor_plan_analysis，§8.4.6 锁顺序）。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo Revision 序号
     * @return Revision 或 null
     */
    public FloorPlanSceneRevision lockRevision(String analysisId, Integer revisionNo) {
        if (revisionNo == null) {
            return null;
        }
        return revisionMapper.selectForUpdate(analysisId, revisionNo);
    }

    /**
     * 视觉通道解析完成：快照（raw_result=视觉结果，parser_version='vision'，parse_status=NULL）
     * 一次性写入并冻结，Revision → READY，工作指针指向本 Revision，分析 → awaiting_confirm。
     * 房间明细由 {@link FloorPlanService#buildRooms} 在本方法前落库。
     *
     * @param analysis   分析记录（本方法内 updateById）
     * @param revisionNo 目标 Revision（须为 PROCESSING）
     * @param detected   视觉识别结果
     */
    public void completeVisionRevision(FloorPlanAnalysis analysis, Integer revisionNo,
                                       com.rsdp.dto.FloorPlanDetectResult detected) {
        FloorPlanSceneRevision revision = getRevision(analysis.getAnalysisId(), revisionNo);
        if (revision == null) {
            revision = recreateMissingRevision(analysis, revisionNo);
        }
        if (!FloorPlanSceneRevision.STATUS_PROCESSING.equals(revision.getRevisionStatus())) {
            log.warn("Revision 非 PROCESSING，跳过 READY 落库，analysisId={}，revision={}，status={}",
                analysis.getAnalysisId(), revisionNo, revision.getRevisionStatus());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        revision.setRawResult(toJson(detected));
        revision.setParserVersion("vision");
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY);
        revision.setReadyAt(now);
        revisionMapper.updateByRevision(revision);

        markAnalysisReady(analysis, revisionNo, now);
        log.info("视觉通道 Scene Revision 已 READY，analysisId={}，revision={}",
            analysis.getAnalysisId(), revisionNo);
    }

    /**
     * CAD 通道解析完成：快照（raw_result 去 pngBase64/unit_resolution/candidates/bounds/
     * parse_status/scene_version/parser_version）一次性写入并冻结，Revision → READY，
     * 工作指针指向本 Revision，分析 → awaiting_confirm + 投影列同步（preview/quality）。
     * 房间/家具明细由 {@link FloorPlanService} 在本方法前落库。
     *
     * @param analysis       分析记录（本方法内 updateById）
     * @param revisionNo     目标 Revision（须为 PROCESSING）
     * @param cadResult      CAD 解析结果（preview.pngBase64 已被解析器适配层清空）
     * @param previewImageId 规范预览图 ID（已存 image_assets），可空
     */
    public void completeCadRevision(FloorPlanAnalysis analysis, Integer revisionNo,
                                    com.rsdp.floorplan.parser.dto.CadParseResult cadResult,
                                    String previewImageId) {
        FloorPlanSceneRevision revision = getRevision(analysis.getAnalysisId(), revisionNo);
        if (revision == null) {
            revision = recreateMissingRevision(analysis, revisionNo);
        }
        if (!FloorPlanSceneRevision.STATUS_PROCESSING.equals(revision.getRevisionStatus())) {
            log.warn("Revision 非 PROCESSING，跳过 READY 落库，analysisId={}，revision={}，status={}",
                analysis.getAnalysisId(), revisionNo, revision.getRevisionStatus());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        revision.setRawResult(toJson(cadResult));
        revision.setParseStatus(StringUtils.hasText(cadResult.getParseStatus())
            ? cadResult.getParseStatus() : "SUCCESS");
        revision.setSceneVersion(cadResult.getSceneVersion());
        revision.setParserVersion(cadResult.getParserVersion());
        revision.setUnitResolution(toJson(cadResult.getUnitResolution()));
        revision.setTargetSceneCandidates(toJson(cadResult.getTargetSceneCandidates()));
        revision.setDrawingBounds(toJson(cadResult.getDrawingBounds()));
        revision.setPreviewBounds(toJson(cadResult.getPreview() != null
            ? cadResult.getPreview().getBounds() : cadResult.getDrawingBounds()));
        revision.setQualityIssues(toJson(cadResult.getQualityIssues() != null
            ? cadResult.getQualityIssues() : List.of()));
        revision.setPreviewImageId(previewImageId);
        if (!StringUtils.hasText(revision.getTargetSceneId()) && cadResult.getTargetScene() != null) {
            revision.setTargetSceneId(cadResult.getTargetScene().getTargetId());
        }
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY);
        revision.setReadyAt(now);
        revisionMapper.updateByRevision(revision);

        // NEW-1：target-scene 切换/重解析（REPARSE）以 insert_path 为 sourceIdentityKey
        // 建立与父 Revision 的跨 Revision 对象映射（§8.7 matchedFromObjectId）
        backfillMatchedFromObjectIds(revision);

        // 分析级 preview_image_id / quality_issues 列作为投影在 READY 时同步（列表页缩略图/质量徽标依赖）
        analysis.setPreviewImageId(previewImageId != null ? previewImageId : analysis.getPreviewImageId());
        analysis.setQualityIssues(revision.getQualityIssues());
        markAnalysisReady(analysis, revisionNo, now);
        log.info("CAD 通道 Scene Revision 已 READY，analysisId={}，revision={}，parseStatus={}",
            analysis.getAnalysisId(), revisionNo, revision.getParseStatus());
    }

    /**
     * 跨 Revision 家具匹配（NEW-1，§8.7）：仅 TARGET_SCENE_SWITCH/REPARSE 且存在父
     * Revision 时执行。将本 Revision 的 INSERT 家具与父 Revision 未软删家具按
     * {@code insert_path}（规范化 JSON 精确匹配）一对一配对，唯一命中回填
     * {@code matched_from_object_id} = 父版 furniture_id；未命中保持 null（NO_MATCH，
     * 不为"ID 稳定"错误继承旧对象）；任一侧同一 insertPath 出现多件（不应出现）→
     * 跳过该键并记日志。MANUAL/EXPLODED 来源不参与。
     *
     * @param revision 已 READY 的目标 Revision（家具明细已落库）
     */
    private void backfillMatchedFromObjectIds(FloorPlanSceneRevision revision) {
        Integer parentNo = revision.getParentAnalysisRevision();
        String trigger = revision.getRevisionTrigger();
        if (parentNo == null
            || (!FloorPlanSceneRevision.TRIGGER_TARGET_SCENE_SWITCH.equals(trigger)
                && !FloorPlanSceneRevision.TRIGGER_REPARSE.equals(trigger))) {
            return;
        }
        List<FloorPlanFurniture> current = listFurniture(revision.getAnalysisId(),
            revision.getAnalysisRevision());
        List<FloorPlanFurniture> parents = listFurniture(revision.getAnalysisId(), parentNo);
        Map<String, List<FloorPlanFurniture>> parentsByPath = new java.util.HashMap<>();
        for (FloorPlanFurniture parent : parents) {
            String key = normalizeInsertPath(parent.getInsertPath());
            if (key != null) {
                parentsByPath.computeIfAbsent(key, k -> new ArrayList<>()).add(parent);
            }
        }
        Map<String, Integer> currentPathCounts = new java.util.HashMap<>();
        for (FloorPlanFurniture furniture : current) {
            String key = normalizeInsertPath(furniture.getInsertPath());
            if (key != null) {
                currentPathCounts.merge(key, 1, Integer::sum);
            }
        }
        int matched = 0;
        for (FloorPlanFurniture furniture : current) {
            if (!FloorPlanFurniture.SOURCE_KIND_INSERT.equals(furniture.getSourceKind())) {
                continue;
            }
            String key = normalizeInsertPath(furniture.getInsertPath());
            if (key == null) {
                continue;
            }
            List<FloorPlanFurniture> candidates = parentsByPath.getOrDefault(key, List.of());
            if (candidates.size() == 1 && currentPathCounts.getOrDefault(key, 0) == 1) {
                furniture.setMatchedFromObjectId(candidates.get(0).getFurnitureId());
                furnitureMapper.updateById(furniture);
                matched++;
            } else if (!candidates.isEmpty() || currentPathCounts.getOrDefault(key, 0) > 1) {
                log.warn("insertPath 匹配非一对一，跳过（NO_MATCH），analysisId={}，revision={}，"
                        + "furnitureId={}，insertPath={}，父侧={}，本侧={}",
                    revision.getAnalysisId(), revision.getAnalysisRevision(),
                    furniture.getFurnitureId(), key, candidates.size(),
                    currentPathCounts.getOrDefault(key, 0));
            }
        }
        if (matched > 0) {
            log.info("跨 Revision 家具匹配完成，analysisId={}，revision={}（父 {}），命中 {} 件",
                revision.getAnalysisId(), revision.getAnalysisRevision(), parentNo, matched);
        }
    }

    /** insert_path 规范化匹配键（JSON 解析后规范序列化；空/非法返回 null 不参与匹配）。 */
    private String normalizeInsertPath(String insertPathJson) {
        if (!StringUtils.hasText(insertPathJson)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(insertPathJson);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("解析 insert_path 失败，按不参与匹配处理: {}", insertPathJson, e);
            return null;
        }
    }

    /**
     * 解析失败：Revision → FAILED（保存诊断，不产生对象）。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo 目标 Revision
     */
    public void markRevisionFailed(String analysisId, Integer revisionNo) {
        FloorPlanSceneRevision revision = getRevision(analysisId, revisionNo);
        if (revision == null
            || !FloorPlanSceneRevision.STATUS_PROCESSING.equals(revision.getRevisionStatus())) {
            return;
        }
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_FAILED);
        revision.setParseStatus("FAILED");
        revisionMapper.updateByRevision(revision);
        log.info("Scene Revision 已置 FAILED，analysisId={}，revision={}", analysisId, revisionNo);
    }

    /** READY 公共收尾：工作指针指向本 Revision、分析 → awaiting_confirm（不写分析级 raw_result）。 */
    private void markAnalysisReady(FloorPlanAnalysis analysis, Integer revisionNo, LocalDateTime now) {
        analysis.setWorkingAnalysisRevision(revisionNo);
        if (analysis.getLatestAnalysisRevision() == null
            || analysis.getLatestAnalysisRevision() < revisionNo) {
            analysis.setLatestAnalysisRevision(revisionNo);
        }
        analysis.setStatus(FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setErrorMessage(null);
        analysis.setUpdatedAt(now);
    }

    /** 防御：任务 input_data 指向的 Revision 缺失时补建（LEGACY_IMPORT 语义之外的兜底）。 */
    private FloorPlanSceneRevision recreateMissingRevision(FloorPlanAnalysis analysis, Integer revisionNo) {
        log.warn("任务指向的 Revision 不存在，补建 PROCESSING Revision，analysisId={}，revision={}",
            analysis.getAnalysisId(), revisionNo);
        FloorPlanSceneRevision revision = new FloorPlanSceneRevision();
        revision.setAnalysisId(analysis.getAnalysisId());
        revision.setAnalysisRevision(revisionNo);
        revision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_REPARSE);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_PROCESSING);
        revision.setRoomRevision(1);
        revision.setFurnitureRevision(1);
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);
        return revision;
    }

    // ------------------------------------------------------------------
    // Scene Activation Validator（§8.5.1）
    // ------------------------------------------------------------------

    /**
     * Scene Activation Validator（P0-B）：实时计算目标 Revision 的激活就绪度。
     *
     * <p>阻断项：parseStatus=FAILED；CAD Revision（parse_status 非空）未确定 Target Scene；
     * unitResolution.canonicalUnit=INVALID（ASSUMED 不阻断）；Room 未确认
     * （room_revision&lt;=1 或无未软删房间）；存在 confirmation_status=AUTO 的未软删家具
     * （无家具则通过）。墙/门/窗 P0 未实现，按 capability contract 不检查。</p>
     *
     * @param revision 目标 Revision
     * @return 就绪度（ready + issues）
     */
    public FloorPlanActivationReadiness evaluateReadiness(FloorPlanSceneRevision revision) {
        List<String> issues = new ArrayList<>();
        if (revision == null) {
            return new FloorPlanActivationReadiness(false, List.of("Scene Revision 不存在"));
        }
        if ("FAILED".equals(revision.getParseStatus())) {
            issues.add("解析状态为 FAILED，不允许激活");
        }
        // CAD Revision（parse_status 非空）要求 target_scene_id 非空；视觉通道（NULL）不要求
        if (StringUtils.hasText(revision.getParseStatus())
            && !"FAILED".equals(revision.getParseStatus())
            && !StringUtils.hasText(revision.getTargetSceneId())) {
            issues.add("CAD Revision 未确定 Target Scene");
        }
        if (isUnitResolutionInvalid(revision.getUnitResolution())) {
            issues.add("单位解析结果无效（INVALID），请先确认比例单位");
        }
        List<FloorPlanRoom> rooms = listRooms(revision.getAnalysisId(), revision.getAnalysisRevision());
        if (revision.getRoomRevision() == null || revision.getRoomRevision() <= 1) {
            issues.add("Room 尚未完成人工确认");
        } else if (rooms.isEmpty()) {
            issues.add("Room 尚未完成人工确认（无有效房间）");
        }
        List<FloorPlanFurniture> furniture = listFurniture(revision.getAnalysisId(),
            revision.getAnalysisRevision());
        long unconfirmed = furniture.stream()
            .filter(f -> FloorPlanFurniture.CONFIRM_AUTO.equals(f.getConfirmationStatus()))
            .count();
        if (unconfirmed > 0) {
            issues.add("存在 " + unconfirmed + " 件未确认家具");
        }
        // NEW-2 引用完整性：未软删家具的 room_id 必须为空或指向同 Revision 未软删房间
        // （跨 Revision 引用/引用已软删房间 = 悬空引用，阻断激活）
        java.util.Set<String> roomIds = rooms.stream()
            .map(FloorPlanRoom::getRoomId).collect(java.util.stream.Collectors.toSet());
        long dangling = furniture.stream()
            .filter(f -> StringUtils.hasText(f.getRoomId()) && !roomIds.contains(f.getRoomId()))
            .count();
        if (dangling > 0) {
            issues.add("存在 " + dangling + " 件家具归属房间引用失效（房间不存在或已删除），请重新指定归属房间");
        }
        return new FloorPlanActivationReadiness(issues.isEmpty(), issues);
    }

    /**
     * 编辑后按 readiness 自动迁移 READY/EDITING ↔ READY_TO_ACTIVATE（§8.5.1：
     * 通过则转 READY_TO_ACTIVATE，未通过保留可编辑状态）。
     *
     * <p>O-N1 等价性说明：编辑 READY_TO_ACTIVATE 的 Revision 后，本方法即时复评——
     * 通过则保持 READY_TO_ACTIVATE，未通过才回落 EDITING，而非先无条件回落 EDITING
     * 再晋升。与 §8.4.6 字面顺序不同但安全性等价：激活事务内必然重跑
     * {@link #evaluateReadiness}（{@code activateRevision} 步骤 ⑥），不会仅凭历史状态放行。</p>
     *
     * @param revision 已锁 Revision（本方法内 updateByRevision）
     */
    public void applyReadinessTransition(FloorPlanSceneRevision revision) {
        FloorPlanActivationReadiness readiness = evaluateReadiness(revision);
        if (readiness.isReady()) {
            revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
        } else if (FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE.equals(revision.getRevisionStatus())) {
            // 编辑 READY_TO_ACTIVATE 的 Revision 后回到 EDITING 重新校验
            revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_EDITING);
        }
        revisionMapper.updateByRevision(revision);
    }

    /** unitResolution.canonicalUnit == INVALID 判定（快照缺失按有效处理，不阻断）。 */
    private boolean isUnitResolutionInvalid(String unitResolutionJson) {
        if (!StringUtils.hasText(unitResolutionJson)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(unitResolutionJson);
            JsonNode canonical = node.get("canonicalUnit");
            return canonical != null && "INVALID".equalsIgnoreCase(canonical.asText());
        } catch (Exception e) {
            log.warn("解析 unitResolution 失败，按有效处理", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Revision scoped 对象读取（Invariant 1/2：单 Revision 装配，禁止跨 Revision 拼装）
    // ------------------------------------------------------------------

    /**
     * 读取某 Revision 全部未软删房间（sort_order 升序）。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo Revision 序号
     * @return 房间列表
     */
    public List<FloorPlanRoom> listRooms(String analysisId, Integer revisionNo) {
        return roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
            .eq("analysis_id", analysisId)
            .eq("analysis_revision", revisionNo)
            .orderByAsc("sort_order"));
    }

    /**
     * 读取某 Revision 全部未软删家具（sort_order 升序）。
     *
     * @param analysisId 分析批次 ID
     * @param revisionNo Revision 序号
     * @return 家具列表
     */
    public List<FloorPlanFurniture> listFurniture(String analysisId, Integer revisionNo) {
        return furnitureMapper.selectList(new QueryWrapper<FloorPlanFurniture>()
            .eq("analysis_id", analysisId)
            .eq("analysis_revision", revisionNo)
            .orderByAsc("sort_order"));
    }

    // ------------------------------------------------------------------
    // 工作 Revision 派生（§8.4.6：修改已生效 Scene 须派生新工作 Revision）
    // ------------------------------------------------------------------

    /**
     * 从源 Revision 派生 MANUAL_EDIT 工作 Revision（复制未软删 Room/Furniture 对象为
     * 新行并生成新对象 ID，家具用 matched_from_object_id 记录来源；复制自动快照与
     * 预览/质量问题引用）。复制已确认对象视同人工确认起点：room_revision 置 2
     * （与存量回填口径一致），readiness 通过则直接 READY_TO_ACTIVATE，否则 EDITING。
     *
     * @param analysis 已锁分析记录（working 指针须为空，调用方校验）
     * @param source   源 Revision（active ?? latest）
     * @param operator 操作人
     * @return 新 Revision（已落库；analysis 的 working/latest 指针已推进，调用方负责 updateById）
     */
    public FloorPlanSceneRevision deriveWorkingRevision(FloorPlanAnalysis analysis,
                                                        FloorPlanSceneRevision source,
                                                        String operator) {
        LocalDateTime now = LocalDateTime.now();
        int newNo = analysis.getLatestAnalysisRevision() + 1;

        FloorPlanSceneRevision derived = new FloorPlanSceneRevision();
        derived.setAnalysisId(analysis.getAnalysisId());
        derived.setAnalysisRevision(newNo);
        derived.setParentAnalysisRevision(source.getAnalysisRevision());
        derived.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_MANUAL_EDIT);
        derived.setTargetSceneId(source.getTargetSceneId());
        derived.setParseStatus(source.getParseStatus());
        derived.setRevisionStatus(FloorPlanSceneRevision.STATUS_EDITING);
        // 复制的对象是源 Revision 已确认对象，视同人工确认起点（roomRevision=2，与回填口径一致）
        derived.setRoomRevision(2);
        derived.setFurnitureRevision(1);
        derived.setParserVersion(source.getParserVersion());
        derived.setParserConfigHash(source.getParserConfigHash());
        derived.setSourceProfileVersion(source.getSourceProfileVersion());
        derived.setSceneVersion(source.getSceneVersion());
        derived.setUnitResolution(source.getUnitResolution());
        derived.setRawResult(source.getRawResult());
        derived.setPreviewImageId(source.getPreviewImageId());
        derived.setQualityIssues(source.getQualityIssues());
        derived.setTargetSceneCandidates(source.getTargetSceneCandidates());
        derived.setDrawingBounds(source.getDrawingBounds());
        derived.setPreviewBounds(source.getPreviewBounds());
        derived.setCreatedBy(operator);
        derived.setCreatedAt(now);
        derived.setReadyAt(now);
        revisionMapper.insert(derived);

        List<FloorPlanRoom> sourceRooms = listRooms(analysis.getAnalysisId(), source.getAnalysisRevision());
        // NEW-2：房间复制产生新对象 ID，建立 源 roomId → 新 roomId 映射，
        // 家具归属按映射重写；引用房间不在复制集合（已软删/不存在）→ null（禁止跨 Revision 引用）
        Map<String, String> roomIdMap = new java.util.HashMap<>();
        for (FloorPlanRoom room : sourceRooms) {
            FloorPlanRoom copy = copyRoom(room, newNo, now);
            roomIdMap.put(room.getRoomId(), copy.getRoomId());
            roomMapper.insert(copy);
        }
        List<FloorPlanFurniture> sourceFurniture = listFurniture(analysis.getAnalysisId(),
            source.getAnalysisRevision());
        for (FloorPlanFurniture furniture : sourceFurniture) {
            FloorPlanFurniture copy = copyFurniture(furniture, newNo, operator, now);
            if (StringUtils.hasText(copy.getRoomId())) {
                String mapped = roomIdMap.get(copy.getRoomId());
                if (mapped == null) {
                    log.info("派生工作 Revision：家具 {} 引用房间 {} 不在复制集合（已软删/不存在），room_id 置 null",
                        furniture.getFurnitureId(), copy.getRoomId());
                }
                copy.setRoomId(mapped);
            }
            furnitureMapper.insert(copy);
        }

        analysis.setWorkingAnalysisRevision(newNo);
        analysis.setLatestAnalysisRevision(newNo);

        applyReadinessTransition(derived);
        log.info("工作 Revision 已派生，analysisId={}，revision={}（源 {}），status={}",
            analysis.getAnalysisId(), newNo, source.getAnalysisRevision(), derived.getRevisionStatus());
        return derived;
    }

    /** 复制房间到新 Revision（新对象 ID，保留几何与确认状态）。 */
    private FloorPlanRoom copyRoom(FloorPlanRoom source, int newRevision, LocalDateTime now) {
        FloorPlanRoom copy = new FloorPlanRoom();
        copy.setRoomId(IdGenerator.floorPlanRoomId());
        copy.setAnalysisId(source.getAnalysisId());
        copy.setAnalysisRevision(newRevision);
        copy.setRoomType(source.getRoomType());
        copy.setBbox(source.getBbox());
        copy.setWidthMm(source.getWidthMm());
        copy.setDepthMm(source.getDepthMm());
        copy.setAreaM2(source.getAreaM2());
        copy.setDimensionSource(source.getDimensionSource());
        copy.setDimensionConfidence(source.getDimensionConfidence());
        copy.setDimensionText(source.getDimensionText());
        copy.setSortOrder(source.getSortOrder());
        copy.setLabel(source.getLabel());
        copy.setPolygon(source.getPolygon());
        copy.setCentroid(source.getCentroid());
        copy.setGeometrySource(source.getGeometrySource());
        copy.setConfirmationStatus(source.getConfirmationStatus());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        return copy;
    }

    /** 复制家具到新 Revision（新对象 ID + matched_from_object_id 记录来源，保留确认状态）。 */
    private FloorPlanFurniture copyFurniture(FloorPlanFurniture source, int newRevision,
                                             String operator, LocalDateTime now) {
        FloorPlanFurniture copy = new FloorPlanFurniture();
        copy.setFurnitureId(IdGenerator.floorPlanFurnitureId());
        copy.setAnalysisId(source.getAnalysisId());
        copy.setAnalysisRevision(newRevision);
        copy.setSourceKind(source.getSourceKind());
        copy.setCategory(source.getCategory());
        copy.setSemanticLabel(source.getSemanticLabel());
        copy.setRoomId(source.getRoomId());
        copy.setBlockName(source.getBlockName());
        copy.setSourceHandles(source.getSourceHandles());
        copy.setInsertPath(source.getInsertPath());
        copy.setPosition(source.getPosition());
        copy.setRotationDeg(source.getRotationDeg());
        copy.setFootprintPolygon(source.getFootprintPolygon());
        copy.setGeometryWidthMm(source.getGeometryWidthMm());
        copy.setGeometryDepthMm(source.getGeometryDepthMm());
        copy.setAnnotatedWidthMm(source.getAnnotatedWidthMm());
        copy.setAnnotatedDepthMm(source.getAnnotatedDepthMm());
        copy.setEffectiveWidthMm(source.getEffectiveWidthMm());
        copy.setEffectiveDepthMm(source.getEffectiveDepthMm());
        copy.setHeightMm(source.getHeightMm());
        copy.setDimensionSource(source.getDimensionSource());
        copy.setSemanticSource(source.getSemanticSource());
        copy.setEvidence(source.getEvidence());
        copy.setQualityFlags(source.getQualityFlags());
        copy.setDetectionConfidence(source.getDetectionConfidence());
        copy.setCategoryConfidence(source.getCategoryConfidence());
        copy.setConfirmationStatus(source.getConfirmationStatus());
        copy.setMatchedFromObjectId(source.getFurnitureId());
        copy.setDerivedFromIds(source.getDerivedFromIds());
        copy.setSortOrder(source.getSortOrder());
        copy.setCreatedBy(operator);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        return copy;
    }

    // ------------------------------------------------------------------
    // Recognition Diff 反馈（§28.2：只记录变化，不反向修改原始结果）
    // ------------------------------------------------------------------

    /**
     * 落一条 Recognition Diff 反馈。
     *
     * @param analysisId   分析批次 ID
     * @param revisionNo   Revision 序号
     * @param parserVersion Parser 版本（取自 Revision 快照）
     * @param objectType   ROOM / FURNITURE
     * @param objectId     对象 ID（ADD 时为新对象 ID）
     * @param feedbackType ADD / DELETE / UPDATE / SPLIT / MERGE
     * @param detail       变化细节（可空）
     * @param operator     操作人
     */
    public void recordFeedback(String analysisId, Integer revisionNo, String parserVersion,
                               String objectType, String objectId, String feedbackType,
                               Object detail, String operator) {
        CadAnalysisFeedback feedback = new CadAnalysisFeedback();
        feedback.setFeedbackId(IdGenerator.cadAnalysisFeedbackId());
        feedback.setAnalysisId(analysisId);
        feedback.setAnalysisRevision(revisionNo);
        feedback.setParserVersion(parserVersion);
        feedback.setObjectType(objectType);
        feedback.setObjectId(objectId);
        feedback.setFeedbackType(feedbackType);
        feedback.setDetail(toJson(detail));
        feedback.setCreatedBy(operator);
        feedback.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(feedback);
    }

    // ------------------------------------------------------------------
    // 幂等提交哈希（rooms/furniture PUT 重复提交判定）
    // ------------------------------------------------------------------

    /**
     * 计算提交内容的 SHA-256 哈希（前 64 位十六进制，与 room_submit_hash 列等长）。
     *
     * @param payload 提交内容（rooms/furniture 列表）
     * @return 哈希；序列化失败返回 null（退化为不幂等，不影响正确性）
     */
    public String submitHash(Object payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算提交哈希失败，按不幂等处理", e);
            return null;
        }
    }

    /**
     * 是否存在仍在执行的解析任务（激活前置校验：任务 pending/processing 视为运行中）。
     *
     * @param taskId 分析当前关联任务 ID，可空
     * @return true = 有运行中任务
     */
    public boolean hasRunningTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        return task != null && (FloorPlanService.STATUS_PENDING.equals(task.getStatus())
            || "processing".equals(task.getStatus()));
    }

    /** 409 版本冲突异常（契约错误码 VERSION_CONFLICT）。 */
    public static BusinessException versionConflict(String detail) {
        return new BusinessException(409, "VERSION_CONFLICT: " + detail);
    }

    /** 409 Revision 不可编辑异常（契约错误码 REVISION_NOT_EDITABLE）。 */
    public static BusinessException revisionNotEditable(String detail) {
        return new BusinessException(409, "REVISION_NOT_EDITABLE: " + detail);
    }

    /** 对象版本一致性断言（null 安全）。 */
    public static boolean revisionEquals(Integer a, Integer b) {
        return Objects.equals(a, b);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }
}
