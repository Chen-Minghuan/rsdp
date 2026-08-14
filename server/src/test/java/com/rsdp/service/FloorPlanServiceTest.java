package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.FloorPlanBBox;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FloorPlanService} 单元测试。
 *
 * <p>尺寸标注解析（mm/米/无标注/畸形）用例见 {@link com.rsdp.util.DimensionsTest}；
 * 本类覆盖：上传建单、尺寸三级提取落库（{@link FloorPlanService#buildRooms}）、
 * 人工校正状态机与整体替换语义、归属校验、task 状态同步校正。</p>
 */
@ExtendWith(MockitoExtension.class)
class FloorPlanServiceTest {

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

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FloorPlanService floorPlanService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(floorPlanService, "maxFileSizeMb", 10);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 接口 1：上传建单 ----------

    @Test
    void analyze_shouldCreateImageAnalysisAndTask() throws Exception {
        when(storageService.store(any(), anyString())).thenReturn("images/stored.jpg");

        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        Map<String, String> response = floorPlanService.analyze(file, "三室两厅");

        assertThat(response.get("analysisId")).startsWith("FPA-");
        assertThat(response.get("taskId")).startsWith("TASK-");

        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getImageType()).isEqualTo("floor_plan");
        assertThat(imageCaptor.getValue().getStoragePath()).isEqualTo("images/stored.jpg");

        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        FloorPlanAnalysis inserted = analysisCaptor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(FloorPlanService.STATUS_PENDING);
        assertThat(inserted.getSource()).isEqualTo("admin");
        assertThat(inserted.getTaskId()).isEqualTo(response.get("taskId"));
        assertThat(inserted.getImageId()).isEqualTo(imageCaptor.getValue().getImageId());

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        AsyncTask task = taskCaptor.getValue();
        assertThat(task.getTaskType()).isEqualTo(FloorPlanService.TASK_TYPE);
        assertThat(task.getStatus()).isEqualTo("pending");
        assertThat(task.getInputData()).contains(response.get("analysisId"));

        // 非事务环境直接触发异步处理
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            response.get("taskId"), response.get("analysisId"), "images/stored.jpg", "三室两厅");
        verify(imageUploadValidator).validate(file, 10L * 1024 * 1024);
    }

    // ---------- 尺寸三级提取（buildRooms，由异步任务调用） ----------

    @Test
    void buildRooms_ocrDimensionText_shouldMarkOcrHighWithoutEstimate() {
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "4200×3800", 0.1, 0.2, 0.4, 0.3)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getRoomType()).isEqualTo("LIVING_ROOM");
        assertThat(room.getWidthMm()).isEqualTo(4200);
        assertThat(room.getDepthMm()).isEqualTo(3800);
        assertThat(room.getAreaM2()).isEqualByComparingTo(new BigDecimal("15.96"));
        assertThat(room.getDimensionSource()).isEqualTo("ocr_text");
        assertThat(room.getDimensionConfidence()).isEqualTo("high");
        assertThat(room.getBbox()).contains("\"x\":0.1");
        // OCR 命中时不再走 AI 估算
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void buildRooms_meterNotation_shouldConvertToMm() {
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("bedroom", "主卧", "4.2m*3.8m", null, null, null, null)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getRoomType()).isEqualTo("BEDROOM");
        assertThat(room.getWidthMm()).isEqualTo(4200);
        assertThat(room.getDepthMm()).isEqualTo(3800);
        assertThat(room.getDimensionSource()).isEqualTo("ocr_text");
        assertThat(room.getBbox()).isNull();
    }

    @Test
    void buildRooms_noDimension_shouldFallbackToAiEstimateLow() {
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"widthMm\": 3600, \"depthMm\": 3300}");
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", null, 0.1, 0.2, 0.4, 0.3)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getWidthMm()).isEqualTo(3600);
        assertThat(room.getDepthMm()).isEqualTo(3300);
        assertThat(room.getDimensionSource()).isEqualTo("ai_estimate");
        assertThat(room.getDimensionConfidence()).isEqualTo("low");
    }

    @Test
    void buildRooms_noDimensionAndEstimateFails_shouldLeaveDimsEmpty() {
        when(visionService.chatText(anyString(), anyString()))
            .thenThrow(new RuntimeException("AI 服务不可用"));
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("balcony", "阳台", null, null, null, null, null)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getRoomType()).isEqualTo("BALCONY");
        assertThat(room.getWidthMm()).isNull();
        assertThat(room.getDepthMm()).isNull();
        assertThat(room.getAreaM2()).isNull();
        assertThat(room.getDimensionConfidence()).isEqualTo("low");
    }

    // ---------- 接口 2：查询 + task 状态同步校正 ----------

    @Test
    void getAnalysis_taskFailed_shouldSyncAnalysisToFailed() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        AsyncTask task = new AsyncTask();
        task.setTaskId("TASK-1");
        task.setStatus("failed");
        task.setErrorMessage("AI 服务超时");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(task);
        when(roomMapper.selectList(any())).thenReturn(List.of());

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getStatus()).isEqualTo(FloorPlanService.STATUS_FAILED);
        assertThat(response.getErrorMessage()).isEqualTo("AI 服务超时");
        verify(analysisMapper).updateById(any(FloorPlanAnalysis.class));
    }

    @Test
    void getAnalysis_taskProcessing_shouldKeepStatus() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        AsyncTask task = new AsyncTask();
        task.setTaskId("TASK-1");
        task.setStatus("processing");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(task);
        when(roomMapper.selectList(any())).thenReturn(List.of());

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getStatus()).isEqualTo(FloorPlanService.STATUS_ANALYZING);
        verify(analysisMapper, never()).updateById(any(FloorPlanAnalysis.class));
    }

    // ---------- 接口 3：人工校正 ----------

    @Test
    void confirmRooms_shouldUpdateInsertSoftDeleteAndConfirm() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        FloorPlanRoom existingKept = buildRoom("FPR-1", "LIVING_ROOM");
        FloorPlanRoom existingRemoved = buildRoom("FPR-2", "BEDROOM");
        when(roomMapper.selectList(any()))
            .thenReturn(List.of(existingKept, existingRemoved))   // confirm 时加载
            .thenReturn(List.of(existingKept));                    // 返回响应时加载

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem kept = new FloorPlanConfirmRequest.RoomItem();
        kept.setRoomId("FPR-1");
        kept.setRoomType("LIVING_ROOM");
        kept.setWidthMm(4200);
        kept.setDepthMm(3800);
        kept.setBbox(new FloorPlanBBox(0.1, 0.2, 0.4, 0.3));
        FloorPlanConfirmRequest.RoomItem added = new FloorPlanConfirmRequest.RoomItem();
        added.setRoomType("BALCONY");
        request.setRooms(List.of(kept, added));

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        // 已有空间就地更新：人工确认 → manual/high，面积自动换算；
        // 未提交的已识别空间就地软删（@TableLogic 语义：updateById 置 deleted_at）
        ArgumentCaptor<FloorPlanRoom> updateCaptor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper, org.mockito.Mockito.atLeastOnce()).updateById(updateCaptor.capture());
        FloorPlanRoom updated = updateCaptor.getAllValues().stream()
            .filter(r -> "FPR-1".equals(r.getRoomId())).findFirst().orElseThrow();
        assertThat(updated.getWidthMm()).isEqualTo(4200);
        assertThat(updated.getDepthMm()).isEqualTo(3800);
        assertThat(updated.getAreaM2()).isEqualByComparingTo(new BigDecimal("15.96"));
        assertThat(updated.getDimensionSource()).isEqualTo("manual");
        assertThat(updated.getDimensionConfidence()).isEqualTo("high");
        assertThat(updated.getBbox()).contains("\"x\":0.1");

        FloorPlanRoom softDeleted = updateCaptor.getAllValues().stream()
            .filter(r -> "FPR-2".equals(r.getRoomId())).findFirst().orElseThrow();
        assertThat(softDeleted.getDeletedAt()).isNotNull();

        // 新增空间插入
        ArgumentCaptor<FloorPlanRoom> insertCaptor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getRoomType()).isEqualTo("BALCONY");
        assertThat(insertCaptor.getValue().getRoomId()).startsWith("FPR-");

        // 状态流转 confirmed
        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        assertThat(analysisCaptor.getValue().getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
        assertThat(analysisCaptor.getValue().getConfirmedRooms()).contains("LIVING_ROOM");
        assertThat(response.getStatus()).isEqualTo(FloorPlanService.STATUS_CONFIRMED);
    }

    @Test
    void confirmRooms_unknownRoomId_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(roomMapper.selectList(any())).thenReturn(List.of(buildRoom("FPR-1", "LIVING_ROOM")));

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-NOT-EXIST");
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));

        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class);
        verify(roomMapper, never()).insert(any(FloorPlanRoom.class));
    }

    @Test
    void confirmRooms_wrongStatus_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_PENDING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));

        assertThatThrownBy(() -> floorPlanService.confirmRooms("FPA-1", request))
            .isInstanceOf(BusinessException.class);
        verify(roomMapper, never()).insert(any(FloorPlanRoom.class));
    }

    // ---------- 接口 5：软删 ----------

    @Test
    void deleteAnalysis_owner_shouldSoftDeleteWithRooms() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        floorPlanService.deleteAnalysis("FPA-1");

        verify(roomMapper).delete(any());
        verify(analysisMapper).deleteById("FPA-1");
    }

    // ---------- 归属校验 ----------

    @Test
    void getAnalysis_nonOwner_shouldThrowNotFound() {
        loginAs("alice");
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        analysis.setCreatedBy("bob");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.getAnalysis("FPA-1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteAnalysis_nonOwner_shouldThrowNotFound() {
        loginAs("alice");
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        analysis.setCreatedBy("bob");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.deleteAnalysis("FPA-1"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(analysisMapper, never()).deleteById(anyString());
    }

    // ---------- 辅助 ----------

    private FloorPlanAnalysis buildAnalysis(String analysisId, String status) {
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setImageId("IMG-1");
        analysis.setStatus(status);
        analysis.setTaskId("TASK-1");
        analysis.setSource("admin");
        analysis.setCreatedBy("anonymous");
        return analysis;
    }

    private FloorPlanRoom buildRoom(String roomId, String roomType) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(roomId);
        room.setAnalysisId("FPA-1");
        room.setRoomType(roomType);
        return room;
    }

    private void loginAs(String username) {
        User principal = new User(username, "password", List.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
