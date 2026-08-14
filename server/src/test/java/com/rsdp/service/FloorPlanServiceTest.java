package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.FloorPlanBBox;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.response.FloorPlanAnalysisListItemResponse;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FloorPlanService} 单元测试。
 *
 * <p>尺寸标注解析（mm/米/无标注/畸形）用例见 {@link com.rsdp.util.DimensionsTest}；
 * 本类覆盖：上传建单、尺寸三级提取落库（{@link FloorPlanService#buildRooms}）、
 * 人工校正状态机与整体替换语义、归属校验、task 状态同步校正、分析历史列表（P1）、
 * 失败重试状态机（P1）、官网匿名落库（v3.0 §4.6 策略 B）。</p>
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
        ReflectionTestUtils.setField(floorPlanService, "pdfRenderDpi", 200f);
        // 上传校验器默认判定为图片（PDF 用例单独 stub）
        lenient().when(imageUploadValidator.validateImageOrPdf(any(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.IMAGE);
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
        verify(imageUploadValidator).validateImageOrPdf(file, 10L * 1024 * 1024);
    }

    // ---------- 接口 1：PDF 户型图支持（v3.0 §8 P2） ----------

    @Test
    void analyze_pdf_shouldRenderFirstPageAndStorePng() throws Exception {
        when(imageUploadValidator.validateImageOrPdf(any(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.PDF);
        when(storageService.store(any(InputStream.class), anyString(), anyLong(), any()))
            .thenReturn("images/stored.png");

        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.pdf", "application/pdf", createPdfBytes(2));

        Map<String, String> response = floorPlanService.analyze(file, null);

        assertThat(response.get("analysisId")).startsWith("FPA-");

        // 落库的是渲染后的 PNG（首页），PDF 原文件不留存
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        ImageAssets imageAsset = imageCaptor.getValue();
        assertThat(imageAsset.getFormat()).isEqualTo("png");
        assertThat(imageAsset.getStoragePath()).isEqualTo("images/stored.png");
        assertThat(imageAsset.getFileSize()).isPositive();
        // 渲染图像素宽/高落库（scale_calc 换算参照）
        assertThat(imageAsset.getWidth()).isPositive();
        assertThat(imageAsset.getHeight()).isPositive();

        // 存储走字节流重载（渲染后的 PNG 字节）
        verify(storageService).store(any(InputStream.class),
            org.mockito.ArgumentMatchers.matches("images/.*\\.png"), anyLong(), any());
        verify(storageService, never()).store(
            any(org.springframework.web.multipart.MultipartFile.class), anyString());
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            response.get("taskId"), response.get("analysisId"), "images/stored.png", null);
    }

    @Test
    void analyze_invalidPdf_shouldThrowBadRequest() throws Exception {
        when(imageUploadValidator.validateImageOrPdf(any(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.PDF);

        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.pdf", "application/pdf", "not-a-pdf".getBytes());

        assertThatThrownBy(() -> floorPlanService.analyze(file, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PDF");
        verify(storageService, never()).store(any(InputStream.class), anyString(), anyLong(), any());
        verify(analysisMapper, never()).insert(any(FloorPlanAnalysis.class));
    }

    @Test
    void analyze_encryptedPdf_shouldThrowBadRequest() throws Exception {
        when(imageUploadValidator.validateImageOrPdf(any(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.PDF);

        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.pdf", "application/pdf", createEncryptedPdfBytes());

        assertThatThrownBy(() -> floorPlanService.analyze(file, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("加密");
        verify(analysisMapper, never()).insert(any(FloorPlanAnalysis.class));
    }

    private byte[] createPdfBytes(int pages) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createEncryptedPdfBytes() throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                "owner", "user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
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

    // ---------- 尺寸三级提取第②级：比例尺换算 scale_calc（P1） ----------

    @Test
    void buildRooms_scaleTextResolvable_shouldApplyScaleCalcMid() {
        // 原图 2000×1500 像素 + "1px=5mm"：空间 bbox 0.4×0.3 → 800×450 像素 → 4000×2250mm
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setScaleText("1px=5mm");
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", null, 0.1, 0.2, 0.4, 0.3)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getWidthMm()).isEqualTo(4000);
        assertThat(room.getDepthMm()).isEqualTo(2250);
        assertThat(room.getAreaM2()).isEqualByComparingTo(new BigDecimal("9.00"));
        assertThat(room.getDimensionSource()).isEqualTo("scale_calc");
        assertThat(room.getDimensionConfidence()).isEqualTo("mid");
        // scale_calc 命中时不再走 AI 估算
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void buildRooms_sheetPhysicalSizeScale_shouldApplyScaleCalcMid() {
        // 整图物理尺寸写法："图幅 10000mm×7500mm" + 原图 2000×1500 像素 → 5mm/px，同上换算结果
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setScaleText("图幅 10000mm×7500mm");
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", null, 0.1, 0.2, 0.4, 0.3)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getWidthMm()).isEqualTo(4000);
        assertThat(room.getDepthMm()).isEqualTo(2250);
        assertThat(room.getDimensionSource()).isEqualTo("scale_calc");
        assertThat(room.getDimensionConfidence()).isEqualTo("mid");
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void buildRooms_scaleTextUnresolvable_shouldFallbackToAiEstimate() {
        // 仅有图纸比例 "1:100"：无法确定图上 1 单位对应多少像素，跳过 scale_calc 落到第③级
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"widthMm\": 3600, \"depthMm\": 3300}");

        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setScaleText("1:100");
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
    void buildRooms_scaleResolvableButNoPixelSize_shouldFallbackToAiEstimate() {
        // 比例尺可解析但原图缺像素宽/高（历史数据），无法把 bbox 换算成像素 → 落第③级
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        when(visionService.chatText(anyString(), anyString()))
            .thenReturn("{\"widthMm\": 3600, \"depthMm\": 3300}");

        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setScaleText("1px=5mm");
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", null, 0.1, 0.2, 0.4, 0.3)));

        floorPlanService.buildRooms("FPA-1", detected);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        FloorPlanRoom room = captor.getValue();
        assertThat(room.getDimensionSource()).isEqualTo("ai_estimate");
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

    // ---------- 分析历史列表（P1） ----------

    @Test
    void listAnalyses_shouldReturnPageWithBatchRoomCounts() {
        FloorPlanAnalysis a1 = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        FloorPlanAnalysis a2 = buildAnalysis("FPA-2", FloorPlanService.STATUS_FAILED);
        a2.setErrorMessage("AI 服务超时");
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of(a1, a2));
        page.setTotal(5);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);
        when(roomMapper.selectList(any())).thenReturn(List.of(
            buildRoom("FPR-1", "LIVING_ROOM"),
            buildRoom("FPR-2", "BEDROOM"),
            roomOf("FPR-3", "FPA-2", "BALCONY")));

        PageResult<FloorPlanAnalysisListItemResponse> result =
            floorPlanService.listAnalyses(1, 20, null);

        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getRows()).hasSize(2);
        // roomCount 按 analysis 批量统计（FPA-1 两个空间，FPA-2 一个）
        assertThat(result.getRows().get(0).getAnalysisId()).isEqualTo("FPA-1");
        assertThat(result.getRows().get(0).getRoomCount()).isEqualTo(2);
        assertThat(result.getRows().get(1).getRoomCount()).isEqualTo(1);
        assertThat(result.getRows().get(1).getErrorMessage()).isEqualTo("AI 服务超时");
    }

    @Test
    void listAnalyses_withStatus_shouldApplyStatusFilter() {
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);

        floorPlanService.listAnalyses(1, 20, "failed");

        ArgumentCaptor<QueryWrapper<FloorPlanAnalysis>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("status =");
    }

    @Test
    void listAnalyses_nonStaff_shouldFilterByCreator() {
        loginAs("alice");
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);

        floorPlanService.listAnalyses(1, 20, null);

        ArgumentCaptor<QueryWrapper<FloorPlanAnalysis>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectPage(any(), captor.capture());
        // 非平台运营仅见本人创建（与详情归属校验同口径）
        assertThat(captor.getValue().getSqlSegment()).contains("created_by =");
    }

    @Test
    void listAnalyses_platformStaff_shouldSeeAll() {
        loginAsAdmin("admin");
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);

        floorPlanService.listAnalyses(1, 20, null);

        ArgumentCaptor<QueryWrapper<FloorPlanAnalysis>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("created_by");
    }

    // ---------- 失败重试（P1） ----------

    @Test
    void retry_failedAnalysis_shouldResetAndCreateNewTask() throws Exception {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_FAILED);
        analysis.setErrorMessage("AI 服务超时");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        AsyncTask oldTask = new AsyncTask();
        oldTask.setTaskId("TASK-1");
        oldTask.setStatus("failed");
        oldTask.setInputData("{\"analysisId\":\"FPA-1\",\"hint\":\"三室两厅\"}");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(oldTask);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setStoragePath("images/fp.jpg");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        Map<String, String> result = floorPlanService.retry("FPA-1");

        String newTaskId = result.get("taskId");
        assertThat(newTaskId).startsWith("TASK-");

        // 状态复位 pending + 清 errorMessage + 换挂新任务
        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        FloorPlanAnalysis updated = analysisCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(FloorPlanService.STATUS_PENDING);
        assertThat(updated.getErrorMessage()).isNull();
        assertThat(updated.getTaskId()).isEqualTo(newTaskId);

        // 上一轮残留空间明细软删，避免重试后重复
        verify(roomMapper).delete(any());

        // 新建异步任务并沿用原 hint
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        AsyncTask newTask = taskCaptor.getValue();
        assertThat(newTask.getTaskId()).isEqualTo(newTaskId);
        assertThat(newTask.getTaskType()).isEqualTo(FloorPlanService.TASK_TYPE);
        assertThat(newTask.getStatus()).isEqualTo("pending");
        assertThat(newTask.getInputData()).contains("三室两厅");

        // 非事务环境直接触发异步处理
        verify(asyncTaskProcessor).processFloorPlanAnalysis(newTaskId, "FPA-1", "images/fp.jpg", "三室两厅");
    }

    @Test
    void retry_crashedAnalyzingWithFailedTask_shouldAllowRetry() throws Exception {
        // JVM 崩溃场景：analysis 停在 analyzing，task 已被收割为 failed —— 先同步校正再重试
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        AsyncTask oldTask = new AsyncTask();
        oldTask.setTaskId("TASK-1");
        oldTask.setStatus("failed");
        oldTask.setErrorMessage("任务超时收割");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(oldTask);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setStoragePath("images/fp.jpg");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        Map<String, String> result = floorPlanService.retry("FPA-1");

        assertThat(result.get("taskId")).startsWith("TASK-");
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_PENDING);
        verify(asyncTaskMapper).insert(any(AsyncTask.class));
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            org.mockito.ArgumentMatchers.eq(result.get("taskId")),
            org.mockito.ArgumentMatchers.eq("FPA-1"),
            org.mockito.ArgumentMatchers.eq("images/fp.jpg"),
            org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void retry_nonFailedStatus_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.retry("FPA-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅识别失败的分析可重试");
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
        verify(analysisMapper, never()).updateById(any(FloorPlanAnalysis.class));
    }

    @Test
    void retry_nonOwner_shouldThrowNotFound() {
        loginAs("alice");
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_FAILED);
        analysis.setCreatedBy("bob");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.retry("FPA-1"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
    }

    // ---------- 官网匿名分析落库（v3.0 §4.6 策略 B） ----------

    @Test
    void savePublicAnalysis_shouldPersistPublicAnalysisWithRooms() throws Exception {
        when(storageService.store(any(InputStream.class), anyString(),
            org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn("images/pub-fp.jpg");
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "4200×3800", 0.1, 0.2, 0.4, 0.3),
            new FloorPlanDetectResult.Room("bedroom", "主卧", null, 0.5, 0.2, 0.3, 0.3)));

        String analysisId = floorPlanService.savePublicAnalysis(
            "fake-plan".getBytes(), "plan.jpg", detected);

        assertThat(analysisId).startsWith("FPA-");

        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getImageType()).isEqualTo("floor_plan");
        assertThat(imageCaptor.getValue().getUploadedBy()).isNull();
        assertThat(imageCaptor.getValue().getStoragePath()).isEqualTo("images/pub-fp.jpg");

        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        FloorPlanAnalysis analysis = analysisCaptor.getValue();
        assertThat(analysis.getAnalysisId()).isEqualTo(analysisId);
        assertThat(analysis.getSource()).isEqualTo("public");
        assertThat(analysis.getCreatedBy()).isNull();
        assertThat(analysis.getStatus()).isEqualTo(FloorPlanService.STATUS_AWAITING_CONFIRM);
        assertThat(analysis.getRawResult()).contains("living_room");
        assertThat(analysis.getConfirmedRooms()).isNull();

        // 空间明细：有尺寸标注 → ocr_text/high；无标注 → source 留空、low（官网同步链路不做 AI 估算）
        ArgumentCaptor<FloorPlanRoom> roomCaptor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper, org.mockito.Mockito.times(2)).insert(roomCaptor.capture());
        FloorPlanRoom living = roomCaptor.getAllValues().get(0);
        assertThat(living.getAnalysisId()).isEqualTo(analysisId);
        assertThat(living.getRoomType()).isEqualTo("LIVING_ROOM");
        assertThat(living.getWidthMm()).isEqualTo(4200);
        assertThat(living.getDepthMm()).isEqualTo(3800);
        assertThat(living.getDimensionSource()).isEqualTo("ocr_text");
        assertThat(living.getDimensionConfidence()).isEqualTo("high");
        FloorPlanRoom bedroom = roomCaptor.getAllValues().get(1);
        assertThat(bedroom.getRoomType()).isEqualTo("BEDROOM");
        assertThat(bedroom.getWidthMm()).isNull();
        assertThat(bedroom.getDimensionSource()).isNull();
        assertThat(bedroom.getDimensionConfidence()).isEqualTo("low");
        // 同步链路不触发 AI 估算
        verify(visionService, never()).chatText(anyString(), anyString());
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
        return roomOf(roomId, "FPA-1", roomType);
    }

    private FloorPlanRoom roomOf(String roomId, String analysisId, String roomType) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(roomId);
        room.setAnalysisId(analysisId);
        room.setRoomType(roomType);
        return room;
    }

    private void loginAs(String username) {
        User principal = new User(username, "password", List.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void loginAsAdmin(String username) {
        User principal = new User(username, "password",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
