package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.FloorPlanActivateRequest;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.request.FloorPlanFurnitureConfirmRequest;
import com.rsdp.dto.request.FloorPlanTargetSceneRequest;
import com.rsdp.dto.response.FloorPlanActivationReadiness;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.FloorPlanFurnitureEditResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CadAnalysisFeedback;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanFurniture;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.FloorPlanSceneRevision;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.floorplan.parser.FloorPlanParserRegistry;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.CadAnalysisFeedbackMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanFurnitureMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAD Scene Revision 全流程测试（V19，设计文档 V1.2.3 §8/§18.6-18.7/§44 不变量）。
 *
 * <p>使用真实 {@link FloorPlanSceneRevisionService}（含 Scene Activation Validator）
 * + 真实 {@link FloorPlanService}，持久层全部 Mock；覆盖：Revision 化 Room/Furniture 编辑
 * （写保护/版本冲突/幂等重放/整体替换+软删+Diff）、原子激活全链路、工作 Revision 派生、
 * Target Scene 切换、legacy wrapper 回归与隐式激活、GET 装配与跨 Revision 不混读。</p>
 */
@ExtendWith(MockitoExtension.class)
class FloorPlanSceneRevisionFlowTest {

    @Mock
    private FloorPlanAnalysisMapper analysisMapper;
    @Mock
    private FloorPlanRoomMapper roomMapper;
    @Mock
    private AsyncTaskMapper asyncTaskMapper;
    @Mock
    private ImageAssetsMapper imageAssetsMapper;
    @Mock
    private ImageUploadValidator imageUploadValidator;
    @Mock
    private StorageService storageService;
    @Mock
    private VisionService visionService;
    @Mock
    private AsyncTaskProcessor asyncTaskProcessor;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private FloorPlanParserRegistry parserRegistry;
    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private FloorPlanSceneRevisionMapper sceneRevisionMapper;
    @Mock
    private FloorPlanFurnitureMapper furnitureMapper;
    @Mock
    private CadAnalysisFeedbackMapper feedbackMapper;
    @Mock
    private ManualLayoutGeometryService manualLayoutGeometryService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FloorPlanSceneRevisionService revisionService;
    private FloorPlanService floorPlanService;

    @BeforeEach
    void setUp() {
        revisionService = new FloorPlanSceneRevisionService(sceneRevisionMapper, roomMapper,
            furnitureMapper, feedbackMapper, asyncTaskMapper, objectMapper);
        floorPlanService = new FloorPlanService(analysisMapper, roomMapper, asyncTaskMapper,
            imageAssetsMapper, imageUploadValidator, storageService, visionService, asyncTaskProcessor,
            auditLogService, parserRegistry, projectService, projectMapper, objectMapper,
            transactionManager, revisionService, sceneRevisionMapper, furnitureMapper,
            manualLayoutGeometryService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== PUT rooms（Revision-aware 编辑） ====================

    @Test
    void editRooms_happyPath_shouldBumpRoomRevisionAndTransitReadyToActivate() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_READY, 1, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        FloorPlanRoom kept = room("FPR-1", 1);
        FloorPlanRoom removed = room("FPR-2", 1);
        FloorPlanRoom postEdit = room("FPR-1", 1);
        when(roomMapper.selectList(any()))
            .thenReturn(List.of(kept, removed))   // 替换前加载
            .thenReturn(List.of(postEdit));        // readiness / 装配
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revision);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(1);
        FloorPlanConfirmRequest.RoomItem keptItem = new FloorPlanConfirmRequest.RoomItem();
        keptItem.setRoomId("FPR-1");
        keptItem.setRoomType("BEDROOM");
        keptItem.setLabel("主卧");
        FloorPlanConfirmRequest.RoomItem newItem = new FloorPlanConfirmRequest.RoomItem();
        newItem.setRoomType("BALCONY");
        newItem.setWidthMm(2000);
        newItem.setDepthMm(1500);
        request.setRooms(List.of(keptItem, newItem));

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        // room_revision 1→2，readiness 通过自动 READY_TO_ACTIVATE
        assertThat(revision.getRoomRevision()).isEqualTo(2);
        assertThat(revision.getRevisionStatus())
            .isEqualTo(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
        // Invariant 5/6/9：工作 Revision 可编辑但不自动生效——active 指针与整版 confirmed 不变
        assertThat(analysis.getActiveAnalysisRevision()).isNull();
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_AWAITING_CONFIRM);
        assertThat(analysis.getConfirmedRooms()).isNull();
        // 整体替换：FPR-1 更新、FPR-2 逻辑删除（BUG-1：deleteById 才落 deleted_at）、新增一行
        assertThat(removed.getDeletedAt()).isNotNull();
        verify(roomMapper).deleteById("FPR-2");
        verify(roomMapper).insert(any(FloorPlanRoom.class));
        // BUG-3 防回归：Revision 更新必须走复合主键双条件（禁用 updateById）
        verify(sceneRevisionMapper, atLeastOnce()).updateByRevision(any(FloorPlanSceneRevision.class));
        verify(sceneRevisionMapper, never()).updateById(any(FloorPlanSceneRevision.class));
        // Diff：DELETE(FPR-2) + UPDATE(FPR-1) + ADD(新增)
        ArgumentCaptor<CadAnalysisFeedback> feedbackCaptor =
            ArgumentCaptor.forClass(CadAnalysisFeedback.class);
        verify(feedbackMapper, atLeastOnce()).insert(feedbackCaptor.capture());
        assertThat(feedbackCaptor.getAllValues())
            .anyMatch(f -> CadAnalysisFeedback.TYPE_DELETE.equals(f.getFeedbackType())
                && "FPR-2".equals(f.getObjectId())
                && CadAnalysisFeedback.OBJECT_TYPE_ROOM.equals(f.getObjectType()))
            .anyMatch(f -> CadAnalysisFeedback.TYPE_UPDATE.equals(f.getFeedbackType())
                && "FPR-1".equals(f.getObjectId()))
            .anyMatch(f -> CadAnalysisFeedback.TYPE_ADD.equals(f.getFeedbackType()));
        assertThat(response.getAnalysisRevision()).isEqualTo(1);
        assertThat(response.getRoomRevision()).isEqualTo(2);
        // Revision-aware PUT /rooms 响应为保存后最新状态（READY_TO_ACTIVATE + ready）
        assertThat(response.getRevisionStatus())
            .isEqualTo(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
        assertThat(response.getActivationReadiness()).isNotNull();
        assertThat(response.getActivationReadiness().isReady()).isTrue();
    }

    @Test
    void editRooms_versionConflict_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 3, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(2);
        request.setRooms(List.of());

        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERSION_CONFLICT")
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        verify(roomMapper, never()).updateById(any(FloorPlanRoom.class));
    }

    @Test
    void editRooms_notWorkingRevision_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(2);
        request.setRooms(List.of());

        // Invariant 10：ACTIVE Revision 不可原地编辑（且目标不是 working）
        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("REVISION_NOT_EDITABLE");
    }

    @Test
    void editRooms_notEditableStatus_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_SUPERSEDED, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(2);
        request.setRooms(List.of());

        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("REVISION_NOT_EDITABLE");
    }

    @Test
    void editRooms_idempotentReplay_shouldReturnCurrentStateWithoutWrites() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(1);
        request.setRooms(List.of());
        // 预置幂等键：与本次提交相同 base + 相同内容哈希
        revision.setRoomSubmitBase(1);
        revision.setRoomSubmitHash(revisionService.submitHash(request.getRooms()));

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        assertThat(revision.getRoomRevision()).isEqualTo(2);
        verify(roomMapper, never()).updateById(any(FloorPlanRoom.class));
        verify(roomMapper, never()).insert(any(FloorPlanRoom.class));
        verify(feedbackMapper, never()).insert(any(CadAnalysisFeedback.class));
        assertThat(response.getRoomRevision()).isEqualTo(2);
    }

    // ==================== PUT furniture（Revision-aware 编辑） ====================

    @Test
    void editFurniture_happyPath_shouldReplaceSoftDeleteAndRecordDiff() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_READY, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        FloorPlanFurniture kept = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO);
        kept.setSourceHandles("[\"1A1\"]");
        FloorPlanFurniture removed = furniture("FPF-2", "CHAIR", FloorPlanFurniture.CONFIRM_AUTO);
        FloorPlanFurniture postKept = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        FloorPlanFurniture postNew = furniture("FPF-NEW", "TABLE", FloorPlanFurniture.CONFIRM_MANUAL_ADDED);
        when(furnitureMapper.selectList(any()))
            .thenReturn(List.of(kept, removed))        // 编辑前加载
            .thenReturn(List.of(postKept, postNew));   // readiness / 装配
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(1);
        FloorPlanFurnitureConfirmRequest.FurnitureItem keptItem =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        keptItem.setFurnitureId("FPF-1");
        keptItem.setCategory("SOFA");
        FloorPlanFurnitureConfirmRequest.FurnitureItem newItem =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        newItem.setCategory("TABLE");
        newItem.setSemanticLabel("餐桌");
        request.setFurniture(List.of(keptItem, newItem));

        FloorPlanFurnitureEditResponse response = floorPlanService.editRevisionFurniture("FPA-1", request);

        // 未变更的 AUTO 对象 → CONFIRMED；自动快照字段不可变
        assertThat(kept.getConfirmationStatus()).isEqualTo(FloorPlanFurniture.CONFIRM_CONFIRMED);
        assertThat(kept.getSourceHandles()).isEqualTo("[\"1A1\"]");
        // 缺席 → 逻辑删除（BUG-1：deleteById 才落 deleted_at）；新增 → MANUAL / MANUAL_ADDED
        assertThat(removed.getDeletedAt()).isNotNull();
        verify(furnitureMapper).deleteById("FPF-2");
        ArgumentCaptor<FloorPlanFurniture> insertCaptor = ArgumentCaptor.forClass(FloorPlanFurniture.class);
        verify(furnitureMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getSourceKind()).isEqualTo(FloorPlanFurniture.SOURCE_KIND_MANUAL);
        assertThat(insertCaptor.getValue().getConfirmationStatus())
            .isEqualTo(FloorPlanFurniture.CONFIRM_MANUAL_ADDED);
        assertThat(insertCaptor.getValue().getFurnitureId()).startsWith("FPF-");
        // Diff：DELETE(FPF-2) + ADD(新增)
        ArgumentCaptor<CadAnalysisFeedback> feedbackCaptor =
            ArgumentCaptor.forClass(CadAnalysisFeedback.class);
        verify(feedbackMapper, atLeastOnce()).insert(feedbackCaptor.capture());
        assertThat(feedbackCaptor.getAllValues())
            .anyMatch(f -> CadAnalysisFeedback.TYPE_DELETE.equals(f.getFeedbackType())
                && "FPF-2".equals(f.getObjectId()))
            .anyMatch(f -> CadAnalysisFeedback.TYPE_ADD.equals(f.getFeedbackType()));
        // 版本推进；active 指针与整版 confirmed 不变（Invariant 5/6/9）
        assertThat(response.getFurnitureRevision()).isEqualTo(2);
        // 保存后状态实时回传（前端免额外 GET 刷新激活入口）
        assertThat(response.getRevisionStatus())
            .isEqualTo(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
        assertThat(response.getActivationReadiness()).isNotNull();
        assertThat(response.getActivationReadiness().isReady()).isTrue();
        assertThat(analysis.getActiveAnalysisRevision()).isNull();
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_AWAITING_CONFIRM);
    }

    @Test
    void editFurniture_changedItem_shouldMarkManualEdited() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        FloorPlanFurniture existing = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(existing));
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(1);
        FloorPlanFurnitureConfirmRequest.FurnitureItem item =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        item.setFurnitureId("FPF-1");
        item.setCategory("BED");
        request.setFurniture(List.of(item));

        floorPlanService.editRevisionFurniture("FPA-1", request);

        assertThat(existing.getCategory()).isEqualTo("BED");
        assertThat(existing.getConfirmationStatus()).isEqualTo(FloorPlanFurniture.CONFIRM_MANUAL_EDITED);
        ArgumentCaptor<CadAnalysisFeedback> feedbackCaptor =
            ArgumentCaptor.forClass(CadAnalysisFeedback.class);
        verify(feedbackMapper, atLeastOnce()).insert(feedbackCaptor.capture());
        assertThat(feedbackCaptor.getAllValues())
            .anyMatch(f -> CadAnalysisFeedback.TYPE_UPDATE.equals(f.getFeedbackType())
                && "FPF-1".equals(f.getObjectId()));
    }

    @Test
    void editFurniture_versionConflict_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 5);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(3);
        request.setFurniture(List.of());

        assertThatThrownBy(() -> floorPlanService.editRevisionFurniture("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERSION_CONFLICT");
        verify(furnitureMapper, never()).updateById(any(FloorPlanFurniture.class));
    }

    @Test
    void editFurniture_splitMerge_shouldRecordSplitAndMergeDiff() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 2);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(2);
        // 拆分：两个新对象来源于同一 FPF-SRC
        FloorPlanFurnitureConfirmRequest.FurnitureItem splitA = furnitureItem(null, "SOFA");
        splitA.setDerivedFromIds(List.of("FPF-SRC"));
        FloorPlanFurnitureConfirmRequest.FurnitureItem splitB = furnitureItem(null, "SOFA");
        splitB.setDerivedFromIds(List.of("FPF-SRC"));
        // 合并：一个新对象来源于 FPF-A + FPF-B
        FloorPlanFurnitureConfirmRequest.FurnitureItem merged = furnitureItem(null, "BED");
        merged.setDerivedFromIds(List.of("FPF-A", "FPF-B"));
        request.setFurniture(List.of(splitA, splitB, merged));

        floorPlanService.editRevisionFurniture("FPA-1", request);

        ArgumentCaptor<CadAnalysisFeedback> feedbackCaptor =
            ArgumentCaptor.forClass(CadAnalysisFeedback.class);
        verify(feedbackMapper, atLeastOnce()).insert(feedbackCaptor.capture());
        assertThat(feedbackCaptor.getAllValues())
            .anyMatch(f -> CadAnalysisFeedback.TYPE_SPLIT.equals(f.getFeedbackType())
                && "FPF-SRC".equals(f.getObjectId()))
            .anyMatch(f -> CadAnalysisFeedback.TYPE_MERGE.equals(f.getFeedbackType()));
    }

    @Test
    void editFurniture_badCategory_shouldThrow400() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(1);
        request.setFurniture(List.of(furnitureItem(null, "ROCKET")));

        assertThatThrownBy(() -> floorPlanService.editRevisionFurniture("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("家具类别非法");
    }

    // ==================== Scene 原子激活 ====================

    @Test
    void activate_happyPath_shouldAtomicallySwitchActivePointer() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            1, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision oldActive = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        FloorPlanSceneRevision target = revision(2, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate(eq("FPA-1"), any())).thenAnswer(invocation -> {
            Integer no = invocation.getArgument(1);
            return no == 1 ? oldActive : target;
        });
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 2)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        AsyncTask doneTask = new AsyncTask();
        doneTask.setTaskId("TASK-1");
        doneTask.setStatus("done");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(doneTask);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(1);
        request.setExpectedRoomRevision(2);
        request.setExpectedFurnitureRevision(1);

        FloorPlanAnalysisResponse response = floorPlanService.activateRevision("FPA-1", 2, request);

        // 旧 ACTIVE → SUPERSEDED；目标 → ACTIVE（activated_at/by）
        assertThat(oldActive.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_SUPERSEDED);
        assertThat(target.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_ACTIVE);
        assertThat(target.getActivatedAt()).isNotNull();
        assertThat(target.getActivatedBy()).isNotNull();
        // 指针原子切换 + 整版 confirmed + confirmed_rooms 快照（Invariant 6/7/9）
        assertThat(analysis.getActiveAnalysisRevision()).isEqualTo(2);
        assertThat(analysis.getWorkingAnalysisRevision()).isNull();
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
        assertThat(analysis.getConfirmedRooms()).contains("FPR-1");
        verify(auditLogService).logAction(eq("floor_plan_analysis"), eq("FPA-1"),
            eq("ACTIVATE_SCENE_REVISION"), any(), any(), any());
        assertThat(response.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_ACTIVE);
        // BUG-3 防回归：旧版/目标 Revision 均按复合主键双条件更新
        verify(sceneRevisionMapper, times(2)).updateByRevision(any(FloorPlanSceneRevision.class));
        verify(sceneRevisionMapper, never()).updateById(any(FloorPlanSceneRevision.class));
        // BUG-2 防回归：working 指针清空必须经 Wrapper 显式 set null（updateById 跳过 null 字段）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FloorPlanAnalysis>>
            wrapperCaptor = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(analysisMapper).update(any(FloorPlanAnalysis.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("working_analysis_revision");
    }

    @Test
    void activate_baseActiveMismatch_shouldThrow409AndRollback() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            1, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision target = revision(2, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 2)).thenReturn(target);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(null);
        request.setExpectedRoomRevision(2);
        request.setExpectedFurnitureRevision(1);

        assertThatThrownBy(() -> floorPlanService.activateRevision("FPA-1", 2, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERSION_CONFLICT");
        // 任何失败整体回滚：无任何 revision/analysis 写入
        verify(sceneRevisionMapper, never()).updateByRevision(any(FloorPlanSceneRevision.class));
        verify(analysisMapper, never()).update(any(FloorPlanAnalysis.class), any());
    }

    @Test
    void activate_expectedRevisionMismatch_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision target = revision(1, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(target);
        AsyncTask doneTask = new AsyncTask();
        doneTask.setStatus("done");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(doneTask);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(null);
        request.setExpectedRoomRevision(3);
        request.setExpectedFurnitureRevision(1);

        assertThatThrownBy(() -> floorPlanService.activateRevision("FPA-1", 1, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERSION_CONFLICT")
            .hasMessageContaining("expectedRoomRevision");
        verify(sceneRevisionMapper, never()).updateByRevision(any(FloorPlanSceneRevision.class));
    }

    @Test
    void activate_notReadyToActivate_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision target = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(target);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(null);
        request.setExpectedRoomRevision(2);
        request.setExpectedFurnitureRevision(1);

        assertThatThrownBy(() -> floorPlanService.activateRevision("FPA-1", 1, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("REVISION_NOT_ACTIVATABLE");
    }

    @Test
    void activate_runningTask_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision target = revision(1, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(target);
        AsyncTask running = new AsyncTask();
        running.setStatus("processing");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(running);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(null);
        request.setExpectedRoomRevision(2);
        request.setExpectedFurnitureRevision(1);

        assertThatThrownBy(() -> floorPlanService.activateRevision("FPA-1", 1, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ANALYSIS_TASK_RUNNING");
    }

    @Test
    void activate_validatorFailure_shouldThrow409WithIssues() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision target = revision(1, FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(target);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        // 存在未确认家具（AUTO）→ Validator 阻断
        when(furnitureMapper.selectList(any()))
            .thenReturn(List.of(furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO)));
        AsyncTask doneTask = new AsyncTask();
        doneTask.setStatus("done");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(doneTask);

        FloorPlanActivateRequest request = new FloorPlanActivateRequest();
        request.setBaseActiveAnalysisRevision(null);
        request.setExpectedRoomRevision(2);
        request.setExpectedFurnitureRevision(1);

        assertThatThrownBy(() -> floorPlanService.activateRevision("FPA-1", 1, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ACTIVATION_VALIDATION_FAILED")
            .hasMessageContaining("未确认家具");
        verify(sceneRevisionMapper, never()).updateByRevision(any(FloorPlanSceneRevision.class));
    }

    // ==================== Scene Activation Validator ====================

    @Test
    void readiness_failedParseStatus_shouldBlock() {
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        revision.setParseStatus("FAILED");
        FloorPlanActivationReadiness readiness = revisionService.evaluateReadiness(revision);
        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.getIssues()).anyMatch(i -> i.contains("FAILED"));
    }

    @Test
    void readiness_cadRevisionWithoutTargetScene_shouldBlock() {
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        revision.setTargetSceneId(null);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        FloorPlanActivationReadiness readiness = revisionService.evaluateReadiness(revision);
        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.getIssues()).anyMatch(i -> i.contains("Target Scene"));
    }

    @Test
    void readiness_visionRevisionWithoutTargetScene_shouldNotBlockOnTargetScene() {
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        revision.setParseStatus(null);
        revision.setTargetSceneId(null);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        FloorPlanActivationReadiness readiness = revisionService.evaluateReadiness(revision);
        assertThat(readiness.isReady()).isTrue();
    }

    @Test
    void readiness_invalidUnitResolution_shouldBlockButAssumedAllowed() {
        FloorPlanSceneRevision invalid = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        invalid.setUnitResolution("{\"canonicalUnit\":\"INVALID\"}");
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        assertThat(revisionService.evaluateReadiness(invalid).isReady()).isFalse();

        FloorPlanSceneRevision assumed = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        assumed.setUnitResolution("{\"canonicalUnit\":\"mm\",\"scaleSource\":\"ASSUMED\"}");
        assertThat(revisionService.evaluateReadiness(assumed).isReady()).isTrue();
    }

    @Test
    void readiness_roomNotConfirmed_shouldBlock() {
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_READY, 1, 1);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        FloorPlanActivationReadiness readiness = revisionService.evaluateReadiness(revision);
        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.getIssues()).anyMatch(i -> i.contains("Room"));
    }

    // ==================== 工作 Revision 派生 ====================

    @Test
    void createWorkingRevision_shouldCopyObjectsWithNewIdsAndMatchedFrom() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision source = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        source.setRawResult("{\"rooms\":[]}");
        source.setPreviewImageId("IMG-PRE-1");
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(source);
        FloorPlanRoom sourceRoom = room("FPR-1", 1);
        FloorPlanFurniture sourceFurniture =
            furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        sourceFurniture.setRoomId("FPR-1");
        // 悬空引用：源房间已软删（不在复制集合）→ room_id 必须置 null（NEW-2）
        FloorPlanFurniture danglingFurniture =
            furniture("FPF-2", "CHAIR", FloorPlanFurniture.CONFIRM_CONFIRMED);
        danglingFurniture.setRoomId("FPR-DELETED");
        when(roomMapper.selectList(any())).thenReturn(List.of(sourceRoom));
        // 第 1 次 = 派生复制源列表；后续（readiness 复评）= 复制后状态（room_id 已映射/置 null）
        FloorPlanFurniture copiedConfirmed1 = furniture("FPF-C1", "SOFA",
            FloorPlanFurniture.CONFIRM_CONFIRMED);
        FloorPlanFurniture copiedConfirmed2 = furniture("FPF-C2", "CHAIR",
            FloorPlanFurniture.CONFIRM_CONFIRMED);
        when(furnitureMapper.selectList(any()))
            .thenReturn(List.of(sourceFurniture, danglingFurniture))
            .thenReturn(List.of(copiedConfirmed1, copiedConfirmed2));

        Map<String, Object> result = floorPlanService.createWorkingRevision("FPA-1");

        assertThat(result.get("analysisRevision")).isEqualTo(2);
        // 新 Revision：trigger=MANUAL_EDIT、parent=1、快照/预览引用复制
        ArgumentCaptor<FloorPlanSceneRevision> revisionCaptor =
            ArgumentCaptor.forClass(FloorPlanSceneRevision.class);
        verify(sceneRevisionMapper).insert(revisionCaptor.capture());
        FloorPlanSceneRevision derived = revisionCaptor.getValue();
        assertThat(derived.getRevisionTrigger()).isEqualTo(FloorPlanSceneRevision.TRIGGER_MANUAL_EDIT);
        assertThat(derived.getParentAnalysisRevision()).isEqualTo(1);
        assertThat(derived.getRawResult()).isEqualTo("{\"rooms\":[]}");
        assertThat(derived.getPreviewImageId()).isEqualTo("IMG-PRE-1");
        assertThat(derived.getRevisionStatus())
            .isEqualTo(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
        // 对象复制生成新 ID；家具 matched_from_object_id 记录来源
        ArgumentCaptor<FloorPlanRoom> roomCaptor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(roomCaptor.capture());
        assertThat(roomCaptor.getValue().getRoomId()).isNotEqualTo("FPR-1");
        assertThat(roomCaptor.getValue().getAnalysisRevision()).isEqualTo(2);
        String newRoomId = roomCaptor.getValue().getRoomId();
        ArgumentCaptor<FloorPlanFurniture> furnitureCaptor =
            ArgumentCaptor.forClass(FloorPlanFurniture.class);
        verify(furnitureMapper, times(2)).insert(furnitureCaptor.capture());
        FloorPlanFurniture copiedSofa = furnitureCaptor.getAllValues().stream()
            .filter(f -> "SOFA".equals(f.getCategory())).findFirst().orElseThrow();
        assertThat(copiedSofa.getFurnitureId()).isNotEqualTo("FPF-1");
        assertThat(copiedSofa.getMatchedFromObjectId()).isEqualTo("FPF-1");
        assertThat(copiedSofa.getConfirmationStatus())
            .isEqualTo(FloorPlanFurniture.CONFIRM_CONFIRMED);
        // NEW-2 防回归：家具 room_id 按 源→新 roomId 映射重写；悬空引用置 null（无跨 Revision 引用）
        assertThat(copiedSofa.getRoomId()).isEqualTo(newRoomId);
        FloorPlanFurniture copiedChair = furnitureCaptor.getAllValues().stream()
            .filter(f -> "CHAIR".equals(f.getCategory())).findFirst().orElseThrow();
        assertThat(copiedChair.getRoomId()).isNull();
        // 指针推进：working=2、latest=2；active 不变
        assertThat(analysis.getWorkingAnalysisRevision()).isEqualTo(2);
        assertThat(analysis.getLatestAnalysisRevision()).isEqualTo(2);
        assertThat(analysis.getActiveAnalysisRevision()).isEqualTo(1);
    }

    @Test
    void readiness_danglingFurnitureRoomRef_shouldBlock() {
        // NEW-2：家具 room_id 指向不存在/已软删房间 → Validator 阻断激活
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        FloorPlanFurniture dangling = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        dangling.setRoomId("FPR-NOT-EXIST");
        when(furnitureMapper.selectList(any())).thenReturn(List.of(dangling));

        FloorPlanActivationReadiness readiness = revisionService.evaluateReadiness(revision);

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.getIssues()).anyMatch(i -> i.contains("归属房间引用失效"));
    }

    @Test
    void deriveThenEditFurnitureWithMappedRoomId_shouldPass() {
        // D 场景：派生 rev2（room_id 已映射到新房间）→ 携带映射 roomId PUT furniture 走通
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED,
            1, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision working = revision(2, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 2)).thenReturn(working);
        // 派生产物：rev2 新房间 FPR-NEW-2 与指向它的家具
        FloorPlanRoom derivedRoom = room("FPR-NEW-2", 2);
        FloorPlanFurniture copied = furniture("FPF-NEW-1", "SOFA",
            FloorPlanFurniture.CONFIRM_CONFIRMED);
        copied.setAnalysisRevision(2);
        copied.setRoomId("FPR-NEW-2");
        when(furnitureMapper.selectList(any())).thenReturn(List.of(copied));
        when(roomMapper.selectList(any())).thenReturn(List.of(derivedRoom));
        when(roomMapper.selectById("FPR-NEW-2")).thenReturn(derivedRoom);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(working);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(2);
        request.setBaseFurnitureRevision(1);
        FloorPlanFurnitureConfirmRequest.FurnitureItem item =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        item.setFurnitureId("FPF-NEW-1");
        item.setCategory("SOFA");
        item.setRoomId("FPR-NEW-2");
        request.setFurniture(List.of(item));

        FloorPlanFurnitureEditResponse response = floorPlanService.editRevisionFurniture("FPA-1", request);

        // 归属校验通过（房间属于本 Revision），无实质变更保持 CONFIRMED
        assertThat(copied.getConfirmationStatus()).isEqualTo(FloorPlanFurniture.CONFIRM_CONFIRMED);
        assertThat(response.getFurnitureRevision()).isEqualTo(2);
    }

    @Test
    void createWorkingRevision_workingExists_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.createWorkingRevision("FPA-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("WORKING_REVISION_EXISTS");
    }

    // ==================== Target Scene 切换 ====================

    @Test
    void switchTargetScene_happyPath_shouldCreateProcessingRevisionAndTask() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 1);
        analysis.setCadObjectKey("cad/fp.dwg");
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision base = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        base.setTargetSceneCandidates("[{\"targetId\":\"SCENE-0002\",\"recommended\":false}]");
        when(sceneRevisionMapper.selectOne(any())).thenReturn(base);
        com.rsdp.entity.ImageAssets imageAsset = new com.rsdp.entity.ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setStoragePath("images/ref.png");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        FloorPlanTargetSceneRequest request = new FloorPlanTargetSceneRequest();
        request.setBaseAnalysisRevision(1);
        request.setTargetId("SCENE-0002");

        Map<String, Object> result = floorPlanService.switchTargetScene("FPA-1", request);

        assertThat(result.get("analysisRevision")).isEqualTo(2);
        assertThat(result.get("status")).isEqualTo("PROCESSING");
        assertThat((String) result.get("taskId")).startsWith("TASK-");
        // 新 Revision：trigger=TARGET_SCENE_SWITCH、parent=1、target_scene_id 确定、PROCESSING
        ArgumentCaptor<FloorPlanSceneRevision> revisionCaptor =
            ArgumentCaptor.forClass(FloorPlanSceneRevision.class);
        verify(sceneRevisionMapper).insert(revisionCaptor.capture());
        FloorPlanSceneRevision created = revisionCaptor.getValue();
        assertThat(created.getRevisionTrigger())
            .isEqualTo(FloorPlanSceneRevision.TRIGGER_TARGET_SCENE_SWITCH);
        assertThat(created.getParentAnalysisRevision()).isEqualTo(1);
        assertThat(created.getTargetSceneId()).isEqualTo("SCENE-0002");
        assertThat(created.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_PROCESSING);
        // 指针推进 + 分析转 analyzing；旧 Revision 不删不改
        assertThat(analysis.getLatestAnalysisRevision()).isEqualTo(2);
        assertThat(analysis.getWorkingAnalysisRevision()).isEqualTo(2);
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_ANALYZING);
        assertThat(base.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_ACTIVE);
        // 异步任务携带 targetId 与 cadObjectKey，并触发 6 参处理器
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getInputData())
            .contains("SCENE-0002").contains("cad/fp.dwg").contains("\"analysisRevision\":2");
        // BUG-4 防回归：target-scene 切换必须留审计（动作名超 varchar(16)，V20 已扩列）
        verify(auditLogService).logAction(eq("floor_plan_analysis"), eq("FPA-1"),
            eq("TARGET_SCENE_SWITCH"), any(), any(), any());
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            eq((String) result.get("taskId")), eq("FPA-1"), eq("images/ref.png"), any(),
            eq("cad/fp.dwg"), eq(false));
    }

    @Test
    void switchTargetScene_baseMismatch_shouldThrow409() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 2);
        analysis.setCadObjectKey("cad/fp.dwg");
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);

        FloorPlanTargetSceneRequest request = new FloorPlanTargetSceneRequest();
        request.setBaseAnalysisRevision(1);
        request.setTargetId("SCENE-0002");

        assertThatThrownBy(() -> floorPlanService.switchTargetScene("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERSION_CONFLICT");
        verify(sceneRevisionMapper, never()).insert(any(FloorPlanSceneRevision.class));
    }

    @Test
    void switchTargetScene_invalidCandidate_shouldThrow400() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 1);
        analysis.setCadObjectKey("cad/fp.dwg");
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision base = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        base.setTargetSceneCandidates("[{\"targetId\":\"SCENE-0001\"}]");
        when(sceneRevisionMapper.selectOne(any())).thenReturn(base);

        FloorPlanTargetSceneRequest request = new FloorPlanTargetSceneRequest();
        request.setBaseAnalysisRevision(1);
        request.setTargetId("SCENE-NOT-EXIST");

        assertThatThrownBy(() -> floorPlanService.switchTargetScene("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("TARGET_SCENE_INVALID");
    }

    @Test
    void switchTargetScene_visionChannel_shouldThrow400() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 1);
        analysis.setCadObjectKey(null);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);

        FloorPlanTargetSceneRequest request = new FloorPlanTargetSceneRequest();
        request.setBaseAnalysisRevision(1);
        request.setTargetId("SCENE-0001");

        assertThatThrownBy(() -> floorPlanService.switchTargetScene("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("TARGET_SCENE_UNSUPPORTED");
    }

    // ==================== legacy wrapper 回归 ====================

    @Test
    void legacyConfirmRooms_shouldKeepOldSemanticsAndImplicitlyActivate() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_READY, 1, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-1");
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        // 旧可观察语义：status=confirmed + confirmed_rooms 快照 + 审计
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
        assertThat(analysis.getConfirmedRooms()).contains("LIVING_ROOM");
        verify(auditLogService).logUpdate(eq("floor_plan_analysis"), eq("FPA-1"), any(),
            any(FloorPlanAnalysis.class), any());
        // 同事务隐式激活：Revision 1 → ACTIVE、active=1、working=NULL
        assertThat(revision.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_ACTIVE);
        assertThat(revision.getActivatedAt()).isNotNull();
        assertThat(analysis.getActiveAnalysisRevision()).isEqualTo(1);
        assertThat(analysis.getWorkingAnalysisRevision()).isNull();
        assertThat(response.getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
        // BUG-2 防回归：legacy 隐式激活同样显式清 working
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FloorPlanAnalysis>>
            wrapperCaptor = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(analysisMapper).update(any(FloorPlanAnalysis.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("working_analysis_revision");
    }

    @Test
    void legacyConfirmRooms_multipleRevisions_shouldThrow400RevisionRequired() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 2, 2);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setRooms(List.of());

        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("REVISION_REQUIRED");
        verify(roomMapper, never()).updateById(any(FloorPlanRoom.class));
    }

    // ==================== GET 详情装配（跨 Revision 不混读） ====================

    @Test
    void getAnalysis_defaultRevision_shouldPreferWorkingThenActiveThenLatest() {
        // working=2 优先
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            1, 2, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision workingRevision = revision(2, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(workingRevision);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 2)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getAnalysisRevision()).isEqualTo(2);
        assertThat(response.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_EDITING);
        assertThat(response.getActiveAnalysisRevision()).isEqualTo(1);
        assertThat(response.getWorkingAnalysisRevision()).isEqualTo(2);
        assertThat(response.getLatestAnalysisRevision()).isEqualTo(2);
        assertThat(response.getActivationReadiness()).isNotNull();
        // Invariant 1/2：房间读取必须带 analysis_id + analysis_revision 双条件
        ArgumentCaptor<QueryWrapper<FloorPlanRoom>> wrapperCaptor =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(roomMapper, atLeastOnce()).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("analysis_id").contains("analysis_revision");
    }

    @Test
    void getAnalysis_specifiedRevision_shouldAssembleThatRevision() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revisionOne = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revisionOne);
        FloorPlanRoom confirmedRoom = room("FPR-1", 1);
        confirmedRoom.setConfirmationStatus("CONFIRMED");
        when(roomMapper.selectList(any())).thenReturn(List.of(confirmedRoom));
        FloorPlanFurniture sofa = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(sofa));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1", 1);

        assertThat(response.getAnalysisRevision()).isEqualTo(1);
        assertThat(response.getRooms()).hasSize(1);
        assertThat(response.getRooms().get(0).getConfirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getFurniture()).hasSize(1);
        assertThat(response.getFurniture().get(0).getCategory()).isEqualTo("SOFA");
        assertThat(response.getRoomRevision()).isEqualTo(2);
        assertThat(response.getFurnitureRevision()).isEqualTo(1);
    }

    @Test
    void getAnalysis_unknownRevision_shouldThrow404() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, 1, null, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> floorPlanService.getAnalysis("FPA-1", 9))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void editFurniture_unchangedItemWithFormatDrift_shouldMarkConfirmed() {
        // P2-9 防回归：rotationDeg 90.0 vs 90.00、position/polygon 整数小数写法差异不算变更
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM,
            null, 1, 1);
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revision = revision(1, FloorPlanSceneRevision.STATUS_EDITING, 2, 1);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(revision);
        FloorPlanFurniture existing = furniture("FPF-1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO);
        existing.setRotationDeg(new java.math.BigDecimal("90.00"));
        existing.setPosition("{\"x\":1200.0,\"y\":1200.0}");
        existing.setFootprintPolygon("[[975.0,975.0],[1425.0,975.0],[1425.0,1425.0],"
            + "[975.0,1425.0],[975.0,975.0]]");
        when(furnitureMapper.selectList(any())).thenReturn(List.of(existing));
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revision);

        FloorPlanFurnitureConfirmRequest request = new FloorPlanFurnitureConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseFurnitureRevision(1);
        FloorPlanFurnitureConfirmRequest.FurnitureItem item =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        item.setFurnitureId("FPF-1");
        item.setCategory("SOFA");
        item.setRotationDeg(new java.math.BigDecimal("90.0"));
        FloorPlanFurnitureConfirmRequest.PointItem position =
            new FloorPlanFurnitureConfirmRequest.PointItem();
        position.setX(1200.0);
        position.setY(1200.0);
        item.setPosition(position);
        item.setFootprintPolygon(List.of(
            List.of(975.0, 975.0), List.of(1425.0, 975.0), List.of(1425.0, 1425.0),
            List.of(975.0, 1425.0), List.of(975.0, 975.0)));
        request.setFurniture(List.of(item));

        floorPlanService.editRevisionFurniture("FPA-1", request);

        // 无实质变更 → CONFIRMED（非 MANUAL_EDITED），且不产生 UPDATE Diff
        assertThat(existing.getConfirmationStatus()).isEqualTo(FloorPlanFurniture.CONFIRM_CONFIRMED);
        assertThat(existing.getRotationDeg()).isEqualByComparingTo("90.00");
        ArgumentCaptor<CadAnalysisFeedback> feedbackCaptor =
            ArgumentCaptor.forClass(CadAnalysisFeedback.class);
        verify(feedbackMapper, never()).insert(feedbackCaptor.capture());
    }

    @Test
    void finalizeParseFailure_sqlException_shouldSanitizeErrorMessage() {
        // BUG-5 防回归：SQL 异常细节（表名/约束名/SQL 文本）不透传前端
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, null, 1);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revisionOne = revision(1, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revisionOne);

        floorPlanService.finalizeParseFailure("FPA-1", 1,
            "PSQLException: ERROR: duplicate key value violates unique constraint "
                + "\"floor_plan_scene_revision_pkey\" SQL [UPDATE floor_plan_scene_revision SET ...]");

        assertThat(analysis.getErrorMessage())
            .doesNotContain("floor_plan_scene_revision")
            .doesNotContain("duplicate key")
            .contains("落库失败");
    }

    // ==================== 跨 Revision 家具匹配（NEW-1，insert_path 为 sourceIdentityKey） ====================

    @Test
    void completeCadRevision_reparse_shouldBackfillMatchedFromByInsertPath() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, 2, 2);
        FloorPlanSceneRevision reparse = revision(2, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        reparse.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_TARGET_SCENE_SWITCH);
        reparse.setParentAnalysisRevision(1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(reparse);
        // 本版（rev2）：两件 INSERT（一件与父版同 insertPath，一件新路径 NO_MATCH）+ 一件 MANUAL 不参与
        FloorPlanFurniture newHit = furniture("FPF-N1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO);
        newHit.setAnalysisRevision(2);
        newHit.setInsertPath("[\"1A1\"]");
        FloorPlanFurniture newMiss = furniture("FPF-N2", "CHAIR", FloorPlanFurniture.CONFIRM_AUTO);
        newMiss.setAnalysisRevision(2);
        newMiss.setInsertPath("[\"9Z9\"]");
        FloorPlanFurniture newManual = furniture("FPF-N3", "TABLE", FloorPlanFurniture.CONFIRM_MANUAL_ADDED);
        newManual.setAnalysisRevision(2);
        newManual.setSourceKind(FloorPlanFurniture.SOURCE_KIND_MANUAL);
        // 父版（rev1）：同 insertPath 的 INSERT 家具
        FloorPlanFurniture parent = furniture("FPF-OLD-1", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        parent.setInsertPath("[\"1A1\"]");
        when(furnitureMapper.selectList(any()))
            .thenReturn(List.of(newHit, newMiss, newManual))   // 本版 listFurniture
            .thenReturn(List.of(parent));                       // 父版 listFurniture

        com.rsdp.floorplan.parser.dto.CadParseResult cadResult =
            new com.rsdp.floorplan.parser.dto.CadParseResult();
        cadResult.setSuccess(true);
        cadResult.setParseStatus("SUCCESS");
        revisionService.completeCadRevision(analysis, 2, cadResult, "IMG-PRE-2");

        // 命中 → matched_from_object_id=父版 ID；未命中/MANUAL → null（NO_MATCH）
        assertThat(newHit.getMatchedFromObjectId()).isEqualTo("FPF-OLD-1");
        verify(furnitureMapper).updateById(newHit);
        assertThat(newMiss.getMatchedFromObjectId()).isNull();
        assertThat(newManual.getMatchedFromObjectId()).isNull();
        assertThat(reparse.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_READY);
        assertThat(analysis.getWorkingAnalysisRevision()).isEqualTo(2);
    }

    @Test
    void completeCadRevision_duplicateInsertPath_shouldSkipAndKeepNull() {
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, 2, 2);
        FloorPlanSceneRevision reparse = revision(2, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        reparse.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_REPARSE);
        reparse.setParentAnalysisRevision(1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(reparse);
        FloorPlanFurniture newOne = furniture("FPF-N1", "SOFA", FloorPlanFurniture.CONFIRM_AUTO);
        newOne.setAnalysisRevision(2);
        newOne.setInsertPath("[\"1A1\"]");
        // 父版同 insertPath 出现两件（不应出现）→ 跳过并记日志
        FloorPlanFurniture parentA = furniture("FPF-OLD-A", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        parentA.setInsertPath("[\"1A1\"]");
        FloorPlanFurniture parentB = furniture("FPF-OLD-B", "SOFA", FloorPlanFurniture.CONFIRM_CONFIRMED);
        parentB.setInsertPath("[\"1A1\"]");
        when(furnitureMapper.selectList(any()))
            .thenReturn(List.of(newOne))
            .thenReturn(List.of(parentA, parentB));

        com.rsdp.floorplan.parser.dto.CadParseResult cadResult =
            new com.rsdp.floorplan.parser.dto.CadParseResult();
        cadResult.setSuccess(true);
        revisionService.completeCadRevision(analysis, 2, cadResult, null);

        assertThat(newOne.getMatchedFromObjectId()).isNull();
        verify(furnitureMapper, never()).updateById(newOne);
    }

    @Test
    void completeCadRevision_initialParse_shouldNotBackfillMatchedFrom() {
        // 首解析（INITIAL_PARSE 无父版）不参与跨 Revision 匹配
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, null, 1);
        FloorPlanSceneRevision initial = revision(1, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(initial);

        com.rsdp.floorplan.parser.dto.CadParseResult cadResult =
            new com.rsdp.floorplan.parser.dto.CadParseResult();
        cadResult.setSuccess(true);
        revisionService.completeCadRevision(analysis, 1, cadResult, null);

        verify(furnitureMapper, never()).selectList(any());
        assertThat(initial.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_READY);
    }

    // ==================== 解析失败：FAILED Revision 不占工作指针（裁决 #11） ====================

    @Test
    void finalizeParseFailure_targetSceneFailure_shouldRollbackWorkingToEditableParent() {
        // target-scene 重解析中：working=2（PROCESSING，parent=1），父 Revision 1 为 READY
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, 2, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision failedRevision = revision(2, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        failedRevision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_TARGET_SCENE_SWITCH);
        failedRevision.setParentAnalysisRevision(1);
        FloorPlanSceneRevision parent = revision(1, FloorPlanSceneRevision.STATUS_READY, 1, 1);
        // selectOne 调用序：markRevisionFailed(rev2) → fallback 读 rev2 → fallback 读 rev1
        when(sceneRevisionMapper.selectOne(any()))
            .thenReturn(failedRevision)
            .thenReturn(failedRevision)
            .thenReturn(parent);

        floorPlanService.finalizeParseFailure("FPA-1", 2, "CAD 解析失败");

        // 失败 Revision → FAILED 且不产生对象
        assertThat(failedRevision.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_FAILED);
        // working 回退到仍可编辑的父 Revision；FAILED Revision 不被 working 引用
        assertThat(analysis.getWorkingAnalysisRevision()).isEqualTo(1);
        // 分析状态回退（无 active → awaiting_confirm），旧工作版可继续编辑
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_AWAITING_CONFIRM);
        assertThat(analysis.getErrorMessage()).contains("CAD 解析失败");
    }

    @Test
    void finalizeParseFailure_thenParentRevisionRemainsEditable() {
        // target-scene 失败后，旧工作版（父 Revision）可继续编辑（完整闭环用例）
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, 2, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision failedRevision = revision(2, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        failedRevision.setParentAnalysisRevision(1);
        FloorPlanSceneRevision parent = revision(1, FloorPlanSceneRevision.STATUS_READY, 1, 1);
        when(sceneRevisionMapper.selectOne(any()))
            .thenReturn(failedRevision)
            .thenReturn(failedRevision)
            .thenReturn(parent)
            .thenReturn(parent);   // editRooms 收尾 getRevision
        floorPlanService.finalizeParseFailure("FPA-1", 2, "CAD 解析失败");

        // 继续编辑父 Revision 1（working 已回退）
        when(analysisMapper.selectByIdForUpdate("FPA-1")).thenReturn(analysis);
        when(sceneRevisionMapper.selectForUpdate("FPA-1", 1)).thenReturn(parent);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("FPR-1", 1)));
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setAnalysisRevision(1);
        request.setBaseRoomRevision(1);
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-1");
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        assertThat(parent.getRoomRevision()).isEqualTo(2);
        assertThat(response.getRevisionStatus())
            .isEqualTo(FloorPlanSceneRevision.STATUS_READY_TO_ACTIVATE);
    }

    @Test
    void finalizeParseFailure_initialParseFailure_shouldKeepFailedAndWorkingNull() {
        // 首解析失败：Revision 1 FAILED、无父版，working 未指向失败 Revision → 维持 failed
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            null, null, 1);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision revisionOne = revision(1, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        when(sceneRevisionMapper.selectOne(any())).thenReturn(revisionOne);

        floorPlanService.finalizeParseFailure("FPA-1", 1, "CAD 解析失败");

        assertThat(revisionOne.getRevisionStatus()).isEqualTo(FloorPlanSceneRevision.STATUS_FAILED);
        assertThat(analysis.getWorkingAnalysisRevision()).isNull();
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_FAILED);
    }

    @Test
    void finalizeParseFailure_parentActive_shouldFallbackToNullAndKeepConfirmed() {
        // 父 Revision 为 ACTIVE（不可编辑态）→ working 置 null；分析回退 confirmed（Invariant 8）
        FloorPlanAnalysis analysis = analysis("FPA-1", FloorPlanService.STATUS_ANALYZING,
            1, 2, 2);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanSceneRevision failedRevision = revision(2, FloorPlanSceneRevision.STATUS_PROCESSING, 1, 1);
        failedRevision.setParentAnalysisRevision(1);
        FloorPlanSceneRevision parent = revision(1, FloorPlanSceneRevision.STATUS_ACTIVE, 2, 1);
        when(sceneRevisionMapper.selectOne(any()))
            .thenReturn(failedRevision)
            .thenReturn(failedRevision)
            .thenReturn(parent);

        floorPlanService.finalizeParseFailure("FPA-1", 2, "CAD 解析失败");

        assertThat(analysis.getWorkingAnalysisRevision()).isNull();
        assertThat(analysis.getActiveAnalysisRevision()).isEqualTo(1);
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
    }

    // ==================== 辅助 ====================

    private FloorPlanAnalysis analysis(String analysisId, String status,
                                       Integer active, Integer working, Integer latest) {
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId("IMG-1");
        analysis.setStatus(status);
        analysis.setTaskId("TASK-1");
        analysis.setSource("admin");
        analysis.setCreatedBy("anonymous");
        analysis.setActiveAnalysisRevision(active);
        analysis.setWorkingAnalysisRevision(working);
        analysis.setLatestAnalysisRevision(latest);
        return analysis;
    }

    private FloorPlanSceneRevision revision(int no, String status, int roomRevision, int furnitureRevision) {
        FloorPlanSceneRevision revision = new FloorPlanSceneRevision();
        revision.setAnalysisId("FPA-1");
        revision.setAnalysisRevision(no);
        revision.setRevisionStatus(status);
        revision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_INITIAL_PARSE);
        revision.setRoomRevision(roomRevision);
        revision.setFurnitureRevision(furnitureRevision);
        revision.setParseStatus("SUCCESS");
        revision.setTargetSceneId("SCENE-0001");
        return revision;
    }

    private FloorPlanRoom room(String roomId, Integer analysisRevision) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(roomId);
        room.setAnalysisId("FPA-1");
        room.setAnalysisRevision(analysisRevision);
        room.setRoomType("LIVING_ROOM");
        room.setGeometrySource(FloorPlanService.GEOMETRY_SOURCE_CAD);
        room.setConfirmationStatus("AUTO");
        return room;
    }

    private FloorPlanFurniture furniture(String furnitureId, String category, String confirmationStatus) {
        FloorPlanFurniture furniture = new FloorPlanFurniture();
        furniture.setFurnitureId(furnitureId);
        furniture.setAnalysisId("FPA-1");
        furniture.setAnalysisRevision(1);
        furniture.setSourceKind(FloorPlanFurniture.SOURCE_KIND_INSERT);
        furniture.setCategory(category);
        furniture.setConfirmationStatus(confirmationStatus);
        return furniture;
    }

    private FloorPlanFurnitureConfirmRequest.FurnitureItem furnitureItem(String furnitureId,
                                                                         String category) {
        FloorPlanFurnitureConfirmRequest.FurnitureItem item =
            new FloorPlanFurnitureConfirmRequest.FurnitureItem();
        item.setFurnitureId(furnitureId);
        item.setCategory(category);
        return item;
    }
}
