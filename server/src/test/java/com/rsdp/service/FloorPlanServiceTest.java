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
import com.rsdp.entity.Project;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.floorplan.parser.FloorPlanFileType;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProjectMapper;
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
import static org.mockito.Mockito.times;
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

    @Mock
    private com.rsdp.floorplan.parser.FloorPlanParserRegistry parserRegistry;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FloorPlanService floorPlanService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(floorPlanService, "maxFileSizeMb", 10);
        ReflectionTestUtils.setField(floorPlanService, "maxCadFileSizeMb", 20);
        ReflectionTestUtils.setField(floorPlanService, "pdfRenderDpi", 200f);
        // 上传校验器默认判定为图片（PDF/CAD 用例单独 stub）
        lenient().when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
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
        verify(imageUploadValidator).validateImageOrPdfOrCad(file, 10L * 1024 * 1024, 20L * 1024 * 1024);
    }

    // ---------- 接口 1：PDF 户型图支持（v3.0 §8 P2） ----------

    @Test
    void analyze_pdf_shouldRenderFirstPageAndStorePng() throws Exception {
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
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
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
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
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
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

    // ---------- 接口 1：CAD 户型图支持（CAD 户型导入 P3） ----------

    @Test
    void analyze_cad_shouldStoreOriginalFileAndRouteToCadParser() throws Exception {
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.CAD);
        when(storageService.store(any(), anyString())).thenReturn("images/stored.dwg");

        MockMultipartFile file = new MockMultipartFile(
            "image", "户型图.dwg", "application/octet-stream", "fake-dwg".getBytes());

        Map<String, String> response = floorPlanService.analyze(file, null);

        assertThat(response.get("analysisId")).startsWith("FPA-");

        // CAD 原文件留存（format=dwg，供失败重试沿用；非图片字节，像素宽/高留空）
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        ImageAssets imageAsset = imageCaptor.getValue();
        assertThat(imageAsset.getFormat()).isEqualTo("dwg");
        assertThat(imageAsset.getStoragePath()).isEqualTo("images/stored.dwg");
        assertThat(imageAsset.getWidth()).isNull();
        // 不走 PDF 渲染（存储走 MultipartFile 重载，原样留存）
        verify(storageService, never()).store(any(InputStream.class), anyString(), anyLong(), any());

        // 按文件类型路由解析器（CAD → CadFloorPlanParser）
        verify(parserRegistry).resolve(FloorPlanFileType.CAD);
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            response.get("taskId"), response.get("analysisId"), "images/stored.dwg", null);
    }

    // ---------- 接口 1：图片+CAD 双文件通道（CAD 户型导入增强） ----------

    @Test
    void analyze_imageAndCad_shouldRouteCadChannelWithoutVision() throws Exception {
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.CAD);
        // 对象键原样返回作存储路径，便于区分底图（images/）与 CAD 原文件（cad/）
        when(storageService.store(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        MockMultipartFile image = new MockMultipartFile(
            "image", "plan.png", "image/png", createPngBytes(100, 80));
        MockMultipartFile cad = new MockMultipartFile(
            "cad", "户型图.dwg", "application/octet-stream", "fake-dwg".getBytes());

        Map<String, String> response = floorPlanService.analyze(image, cad, "三室两厅");

        assertThat(response.get("analysisId")).startsWith("FPA-");
        assertThat(response.get("taskId")).startsWith("TASK-");

        // 底图落 image_assets：format=png + 像素宽/高（供前端底图渲染）
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        ImageAssets imageAsset = imageCaptor.getValue();
        assertThat(imageAsset.getImageType()).isEqualTo("floor_plan");
        assertThat(imageAsset.getFormat()).isEqualTo("png");
        assertThat(imageAsset.getWidth()).isEqualTo(100);
        assertThat(imageAsset.getHeight()).isEqualTo(80);

        // analysis.imageId 指向底图（imageUrl 即预览图地址）
        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        assertThat(analysisCaptor.getValue().getImageId()).isEqualTo(imageAsset.getImageId());

        // 任务 input_data 携带 cadObjectKey + codeNameMode（失败重试沿用）
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getInputData()).contains("cadObjectKey").contains("codeNameMode");

        // 固定路由 CAD 解析通道（不跑视觉识别），底图对象键 + CAD 对象键 + 代号模式透传
        verify(parserRegistry).resolve(FloorPlanFileType.CAD);
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            org.mockito.ArgumentMatchers.eq(response.get("taskId")),
            org.mockito.ArgumentMatchers.eq(response.get("analysisId")),
            org.mockito.ArgumentMatchers.matches("images/.*\\.png"),
            org.mockito.ArgumentMatchers.eq("三室两厅"),
            org.mockito.ArgumentMatchers.matches("cad/.*\\.dwg"),
            org.mockito.ArgumentMatchers.eq(true));
        // 双文件各自校验：cad 走 CAD 规则，image 走图片规则
        verify(imageUploadValidator).validateImageOrPdfOrCad(cad, 10L * 1024 * 1024, 20L * 1024 * 1024);
        verify(imageUploadValidator).validate(image, 10L * 1024 * 1024);
        verify(visionService, never()).chatText(anyString(), anyString());
    }

    @Test
    void analyze_cadOnlyViaCadField_shouldKeepP3CadBehavior() throws Exception {
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.CAD);
        when(storageService.store(any(), anyString())).thenReturn("images/stored.dwg");

        MockMultipartFile cad = new MockMultipartFile(
            "cad", "户型图.dwg", "application/octet-stream", "fake-dwg".getBytes());

        Map<String, String> response = floorPlanService.analyze(null, cad, null);

        // 仅 cad（无底图）：等同 P3 单文件 CAD 通道（原文件落 image_assets、4 参异步形态、保留自动命名）
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getFormat()).isEqualTo("dwg");
        verify(parserRegistry).resolve(FloorPlanFileType.CAD);
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            response.get("taskId"), response.get("analysisId"), "images/stored.dwg", null);
    }

    @Test
    void analyzePublicCad_shouldMarkAnalysisAsPublicAndClearCreator() throws Exception {
        when(imageUploadValidator.validateImageOrPdfOrCad(any(), anyLong(), anyLong()))
            .thenReturn(ImageUploadValidator.UploadKind.CAD);
        when(storageService.store(any(), anyString())).thenReturn("cad/public-plan.dwg");
        when(analysisMapper.selectById(anyString())).thenAnswer(invocation -> {
            FloorPlanAnalysis analysis = new FloorPlanAnalysis();
            analysis.setAnalysisId(invocation.getArgument(0));
            analysis.setSource("admin");
            analysis.setCreatedBy("anonymous");
            return analysis;
        });

        MockMultipartFile cad = new MockMultipartFile(
            "cad", "游客户型.dwg", "application/octet-stream", "fake-dwg".getBytes());

        Map<String, String> response = floorPlanService.analyzePublicCad(null, cad, "游客户型");

        assertThat(response.get("analysisId")).startsWith("FPA-");
        ArgumentCaptor<FloorPlanAnalysis> captor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo("public");
        assertThat(captor.getValue().getCreatedBy()).isNull();
    }

    @Test
    void analyze_noFile_shouldThrowBadRequest() {
        assertThatThrownBy(() -> floorPlanService.analyze(null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请上传户型图片或 CAD 图纸文件");
        verify(analysisMapper, never()).insert(any(FloorPlanAnalysis.class));
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
    }

    private byte[] createPngBytes(int width, int height) throws IOException {
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ---------- CAD 解析结果落库（buildCadRooms，由异步任务调用） ----------

    @Test
    void buildCadRooms_shouldPersistRoomsWithGeometryAndUnnamedRegions() {
        CadParseResult cad = new CadParseResult();
        cad.setSuccess(true);
        CadParseResult.Bounds bounds = new CadParseResult.Bounds();
        bounds.setMinX(0.0);
        bounds.setMinY(0.0);
        bounds.setMaxX(10000.0);
        bounds.setMaxY(8000.0);
        cad.setDrawingBounds(bounds);

        CadParseResult.Room bedroom = new CadParseResult.Room();
        bedroom.setLabel("主卧");
        bedroom.setRoomType("BEDROOM");
        bedroom.setPolygon(List.of(
            List.of(1000.0, 1000.0), List.of(5200.0, 1000.0),
            List.of(5200.0, 4800.0), List.of(1000.0, 4800.0)));
        bedroom.setWidthMm(4200.0);
        bedroom.setDepthMm(3800.0);
        bedroom.setAreaM2(15.96);
        CadParseResult.DimensionCheck check = new CadParseResult.DimensionCheck();
        check.setAnnotated("4200×3800");
        check.setConsistent(true);
        bedroom.setDimensionCheck(check);
        bedroom.setConfidence("high");
        cad.setRooms(List.of(bedroom));

        CadParseResult.UnnamedRegion region = new CadParseResult.UnnamedRegion();
        region.setPolygon(List.of(
            List.of(6000.0, 1000.0), List.of(7000.0, 1000.0),
            List.of(7000.0, 2000.0), List.of(6000.0, 2000.0)));
        region.setAreaM2(1.0);
        region.setWidthMm(1000.0);
        region.setDepthMm(1000.0);
        region.setHint("距 主卧 1.2m");
        cad.setUnnamedRegions(List.of(region));

        floorPlanService.buildCadRooms("FPA-1", cad);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper, times(2)).insert(captor.capture());

        FloorPlanRoom room = captor.getAllValues().get(0);
        assertThat(room.getLabel()).isEqualTo("主卧");
        assertThat(room.getRoomType()).isEqualTo("BEDROOM");
        assertThat(room.getWidthMm()).isEqualTo(4200);
        assertThat(room.getDepthMm()).isEqualTo(3800);
        assertThat(room.getAreaM2()).isEqualByComparingTo(new BigDecimal("15.96"));
        assertThat(room.getDimensionSource()).isEqualTo("cad_geometry");
        assertThat(room.getDimensionConfidence()).isEqualTo("high");
        assertThat(room.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(room.getDimensionText()).isEqualTo("4200×3800");
        assertThat(room.getPolygon()).contains("5200.0");
        // 质心 = 顶点均值：x=(1000+5200+5200+1000)/4=3100，y=(1000+1000+4800+4800)/4=2900
        assertThat(room.getCentroid()).contains("3100.0").contains("2900.0");
        // bbox 由 polygon 外包络按 drawingBounds 归一化：x=0.1，y=0.125，w=0.42，h=0.475
        assertThat(room.getBbox()).contains("\"x\":0.1").contains("\"w\":0.42");

        FloorPlanRoom unnamed = captor.getAllValues().get(1);
        assertThat(unnamed.getRoomType()).isEqualTo("OTHER");
        assertThat(unnamed.getLabel()).isEqualTo("未命名空间 1");
        assertThat(unnamed.getDimensionConfidence()).isEqualTo("low");
        assertThat(unnamed.getDimensionSource()).isEqualTo("cad_geometry");
        assertThat(unnamed.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(unnamed.getWidthMm()).isEqualTo(1000);
        assertThat(unnamed.getDepthMm()).isEqualTo(1000);
        assertThat(unnamed.getSortOrder()).isEqualTo(1);
    }

    @Test
    void buildCadRooms_legacyCodeNameMode_shouldPreserveDetectedSemantics() {
        CadParseResult cad = new CadParseResult();
        cad.setSuccess(true);
        CadParseResult.Bounds bounds = new CadParseResult.Bounds();
        bounds.setMinX(0.0);
        bounds.setMinY(0.0);
        bounds.setMaxX(10000.0);
        bounds.setMaxY(8000.0);
        cad.setDrawingBounds(bounds);

        CadParseResult.Room bedroom = new CadParseResult.Room();
        bedroom.setLabel("主卧");
        bedroom.setRoomType("BEDROOM");
        bedroom.setPolygon(List.of(
            List.of(1000.0, 1000.0), List.of(5200.0, 1000.0),
            List.of(5200.0, 4800.0), List.of(1000.0, 4800.0)));
        bedroom.setWidthMm(4200.0);
        bedroom.setDepthMm(3800.0);
        bedroom.setAreaM2(15.96);
        bedroom.setConfidence("high");
        cad.setRooms(List.of(bedroom));

        CadParseResult.UnnamedRegion region = new CadParseResult.UnnamedRegion();
        region.setPolygon(List.of(
            List.of(6000.0, 1000.0), List.of(7000.0, 1000.0), List.of(7000.0, 2000.0)));
        region.setAreaM2(1.0);
        cad.setUnnamedRegions(List.of(region));

        floorPlanService.buildCadRooms("FPA-1", cad, true);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper, times(2)).insert(captor.capture());

        // 历史代号参数仅为兼容旧任务保留，不再丢弃 CAD 已识别出的语义。
        FloorPlanRoom room = captor.getAllValues().get(0);
        assertThat(room.getLabel()).isEqualTo("主卧");
        assertThat(room.getRoomType()).isEqualTo("BEDROOM");
        assertThat(room.getWidthMm()).isEqualTo(4200);
        assertThat(room.getDepthMm()).isEqualTo(3800);
        assertThat(room.getAreaM2()).isEqualByComparingTo(new BigDecimal("15.96"));
        assertThat(room.getDimensionSource()).isEqualTo("cad_geometry");
        assertThat(room.getDimensionConfidence()).isEqualTo("high");
        assertThat(room.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(room.getPolygon()).contains("5200.0");
        assertThat(room.getSortOrder()).isEqualTo(0);

        // 未命名区域仍清晰标记，交给用户确认。
        FloorPlanRoom unnamed = captor.getAllValues().get(1);
        assertThat(unnamed.getLabel()).isEqualTo("未命名空间 1");
        assertThat(unnamed.getRoomType()).isEqualTo("OTHER");
        assertThat(unnamed.getDimensionConfidence()).isEqualTo("low");
        assertThat(unnamed.getSortOrder()).isEqualTo(1);
    }

    @Test
    void storeCadPreview_shouldPersistDerivedImageAndLinkAnalysis() throws Exception {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_ANALYZING);
        analysis.setCreatedBy("tester");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(storageService.store(any(InputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/cad-preview/stored.png");
        CadParseResult.Preview preview = new CadParseResult.Preview();
        preview.setWidth(1361);
        preview.setHeight(1400);

        floorPlanService.storeCadPreview("FPA-1", new byte[] { 1, 2, 3 }, preview);

        ArgumentCaptor<ImageAssets> assetCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(assetCaptor.capture());
        ImageAssets asset = assetCaptor.getValue();
        assertThat(asset.getImageType()).isEqualTo("floor_plan_cad_preview");
        assertThat(asset.getStoragePath()).isEqualTo("images/cad-preview/stored.png");
        assertThat(asset.getWidth()).isEqualTo(1361);
        assertThat(asset.getHeight()).isEqualTo(1400);
        assertThat(asset.getFormat()).isEqualTo("png");
        assertThat(analysis.getPreviewImageId()).isEqualTo(asset.getImageId());
        verify(analysisMapper).updateById(analysis);
    }

    @Test
    void getAnalysis_cadPath_shouldExposeDrawingBounds() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setPreviewImageId("IMG-PREVIEW-1");
        analysis.setRawResult("{\"success\":true,\"units\":\"mm\","
            + "\"drawingBounds\":{\"minX\":0.0,\"minY\":0.0,\"maxX\":10000.0,\"maxY\":8000.0},"
            + "\"preview\":{\"width\":1000,\"height\":800,\"bounds\":"
            + "{\"minX\":10.0,\"minY\":20.0,\"maxX\":9990.0,\"maxY\":7980.0}},"
            + "\"qualityIssues\":[]}");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-1", "OTHER");
        room.setGeometrySource("cad_geometry");
        room.setLabel("空间 1");
        when(roomMapper.selectList(any())).thenReturn(List.of(room));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getDrawingBounds()).isNotNull();
        assertThat(response.getDrawingBounds().getMinX()).isEqualTo(0.0);
        assertThat(response.getDrawingBounds().getMinY()).isEqualTo(0.0);
        assertThat(response.getDrawingBounds().getMaxX()).isEqualTo(10000.0);
        assertThat(response.getDrawingBounds().getMaxY()).isEqualTo(8000.0);
        assertThat(response.getPreviewImageId()).isEqualTo("IMG-PREVIEW-1");
        assertThat(response.getPreviewUrl()).isEqualTo("/api/v1/images/IMG-PREVIEW-1");
        assertThat(response.getPreviewBounds().getMinX()).isEqualTo(10.0);
        assertThat(response.getPreviewBounds().getMaxY()).isEqualTo(7980.0);
        assertThat(response.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(response.getScaleSuggestion()).isNull();
    }

    @Test
    void buildCadRooms_unknownRoomType_shouldFallbackToOther() {
        CadParseResult cad = new CadParseResult();
        cad.setSuccess(true);
        CadParseResult.Room weird = new CadParseResult.Room();
        weird.setLabel("设备井");
        weird.setRoomType("ELEVATOR_SHAFT");
        weird.setPolygon(List.of(
            List.of(0.0, 0.0), List.of(1000.0, 0.0), List.of(1000.0, 1000.0)));
        weird.setConfidence("mid");
        cad.setRooms(List.of(weird));

        floorPlanService.buildCadRooms("FPA-1", cad);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoomType()).isEqualTo("OTHER");
        assertThat(captor.getValue().getLabel()).isEqualTo("设备井");
    }

    @Test
    void getAnalysis_cadPath_shouldReturnGeometrySourceAndQualityIssuesAndSkipScaleSuggestion() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setRawResult("{\"success\":true,\"qualityIssues\":["
            + "{\"level\":\"warn\",\"code\":\"UNIT_ASSUMED\",\"message\":\"图纸未声明单位，按毫米处理\"}]}");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-1", "BEDROOM");
        room.setGeometrySource("cad_geometry");
        room.setLabel("主卧");
        room.setPolygon("[[1000.0,1000.0],[5200.0,1000.0]]");
        when(roomMapper.selectList(any())).thenReturn(List.of(room));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(response.getQualityIssues()).hasSize(1);
        assertThat(response.getQualityIssues().get(0).getLevel()).isEqualTo("warn");
        assertThat(response.getQualityIssues().get(0).getCode()).isEqualTo("UNIT_ASSUMED");
        // CAD 通道跳过自动标定建议
        assertThat(response.getScaleSuggestion()).isNull();
        assertThat(response.getRooms().get(0).getLabel()).isEqualTo("主卧");
        assertThat(response.getRooms().get(0).getPolygon()).contains(List.of(1000.0, 1000.0));
    }

    @Test
    void getAnalysis_visionPath_shouldDefaultAiVisionAndEmptyQualityIssues() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setRawResult("{\"rooms\":[{\"roomType\":\"living_room\",\"label\":\"客厅\"}]}");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-1", "LIVING_ROOM");
        room.setGeometrySource("ai_vision");
        when(roomMapper.selectList(any())).thenReturn(List.of(room));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getGeometrySource()).isEqualTo("ai_vision");
        assertThat(response.getQualityIssues()).isEmpty();
        assertThat(response.getRooms().get(0).getPolygon()).isNull();
        // 视觉通道无 CAD 图纸外包络
        assertThat(response.getDrawingBounds()).isNull();
    }

    // ---------- 接口 1：项目归属与户型名称透传（V14） ----------

    @Test
    void analyze_withProjectIdAndSourceName_shouldValidateAndPersist() throws Exception {
        Project project = new Project();
        project.setProjectId("PRJ-1");
        project.setProjectName("滨江华府");
        when(projectService.getAccessibleProject("PRJ-1")).thenReturn(project);
        when(storageService.store(any(), anyString())).thenReturn("images/stored.jpg");

        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        Map<String, String> response = floorPlanService.analyze(file, null, " PRJ-1 ", " 3-2-1 东边套 ");

        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        assertThat(analysisCaptor.getValue().getProjectId()).isEqualTo("PRJ-1");
        assertThat(analysisCaptor.getValue().getSourceName()).isEqualTo("3-2-1 东边套");
        verify(projectService).getAccessibleProject("PRJ-1");
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            response.get("taskId"), response.get("analysisId"), "images/stored.jpg", null);
    }

    @Test
    void analyze_inaccessibleProject_shouldThrowAndNotPersist() {
        when(projectService.getAccessibleProject("PRJ-X"))
            .thenThrow(new ForbiddenException("无权访问该项目: PRJ-X"));
        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        assertThatThrownBy(() -> floorPlanService.analyze(file, null, "PRJ-X", null))
            .isInstanceOf(ForbiddenException.class);
        verify(analysisMapper, never()).insert(any(FloorPlanAnalysis.class));
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
    }

    @Test
    void analyze_sourceNameTooLong_shouldThrowBadRequest() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        assertThatThrownBy(() -> floorPlanService.analyze(file, null, null, "x".repeat(129)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("128");
        verify(analysisMapper, never()).insert(any(FloorPlanAnalysis.class));
    }

    // ---------- 接口 3：人工确认补挂/改挂项目（V14） ----------

    @Test
    void confirmRooms_withProjectId_shouldLinkProjectAndAudit() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(roomMapper.selectList(any())).thenReturn(List.of(buildRoom("FPR-1", "LIVING_ROOM")));
        Project project = new Project();
        project.setProjectId("PRJ-1");
        project.setProjectName("滨江华府");
        when(projectService.getAccessibleProject("PRJ-1")).thenReturn(project);
        when(projectMapper.selectById("PRJ-1")).thenReturn(project);

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-1");
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));
        request.setProjectId("PRJ-1");

        FloorPlanAnalysisResponse response = floorPlanService.confirmRooms("FPA-1", request);

        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        assertThat(analysisCaptor.getValue().getProjectId()).isEqualTo("PRJ-1");
        // 挂靠项目记审计日志（LINK_PROJECT）
        verify(auditLogService).logAction(org.mockito.ArgumentMatchers.eq("floor_plan_analysis"),
            org.mockito.ArgumentMatchers.eq("FPA-1"), org.mockito.ArgumentMatchers.eq("LINK_PROJECT"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(Map.of("projectId", "PRJ-1")), any());
        assertThat(response.getProjectId()).isEqualTo("PRJ-1");
        assertThat(response.getProjectName()).isEqualTo("滨江华府");
    }

    @Test
    void confirmRooms_withoutProjectId_shouldKeepProjectUntouched() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setProjectId("PRJ-OLD");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(roomMapper.selectList(any())).thenReturn(List.of(buildRoom("FPR-1", "LIVING_ROOM")));

        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-1");
        item.setRoomType("LIVING_ROOM");
        request.setRooms(List.of(item));

        floorPlanService.confirmRooms("FPA-1", request);

        ArgumentCaptor<FloorPlanAnalysis> analysisCaptor = ArgumentCaptor.forClass(FloorPlanAnalysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        // 不传 projectId 则不动项目归属，也不做项目可见性校验
        assertThat(analysisCaptor.getValue().getProjectId()).isEqualTo("PRJ-OLD");
        verify(projectService, never()).getAccessibleProject(anyString());
        verify(auditLogService, never()).logAction(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("LINK_PROJECT"),
            any(), any(), any());
    }

    // ---------- 分析历史列表增强（V14） ----------

    @Test
    void listAnalyses_shouldEnrichThumbnailProjectGeometryAndQualityCount() {
        FloorPlanAnalysis cad = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        cad.setPreviewImageId("IMG-PREVIEW-1");
        cad.setProjectId("PRJ-1");
        cad.setSourceName("滨江华府 3-2-1");
        cad.setQualityIssues("[{\"level\":\"warn\",\"code\":\"UNIT_ASSUMED\",\"message\":\"按毫米处理\"},"
            + "{\"level\":\"warn\",\"code\":\"DIM_MISMATCH\",\"message\":\"标注不一致\"}]");
        FloorPlanAnalysis vision = buildAnalysis("FPA-2", FloorPlanService.STATUS_CONFIRMED);
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of(cad, vision));
        page.setTotal(2);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);
        FloorPlanRoom cadRoom = buildRoom("FPR-1", "BEDROOM");
        cadRoom.setGeometrySource("cad_geometry");
        FloorPlanRoom visionRoom = roomOf("FPR-2", "FPA-2", "LIVING_ROOM");
        visionRoom.setGeometrySource("ai_vision");
        when(roomMapper.selectList(any())).thenReturn(List.of(cadRoom, visionRoom));
        Project project = new Project();
        project.setProjectId("PRJ-1");
        project.setProjectName("滨江华府");
        when(projectMapper.selectBatchIds(List.of("PRJ-1"))).thenReturn(List.of(project));

        PageResult<FloorPlanAnalysisListItemResponse> result = floorPlanService.listAnalyses(1, 20, null, null);

        FloorPlanAnalysisListItemResponse cadItem = result.getRows().get(0);
        assertThat(cadItem.getThumbnailUrl()).isEqualTo("/api/v1/images/IMG-PREVIEW-1");
        assertThat(cadItem.getProjectId()).isEqualTo("PRJ-1");
        assertThat(cadItem.getProjectName()).isEqualTo("滨江华府");
        assertThat(cadItem.getSourceName()).isEqualTo("滨江华府 3-2-1");
        assertThat(cadItem.getGeometrySource()).isEqualTo("cad_geometry");
        assertThat(cadItem.getQualityIssueCount()).isEqualTo(2);

        FloorPlanAnalysisListItemResponse visionItem = result.getRows().get(1);
        // 无预览图回退原图；无质量提示为 0
        assertThat(visionItem.getThumbnailUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(visionItem.getGeometrySource()).isEqualTo("ai_vision");
        assertThat(visionItem.getQualityIssueCount()).isEqualTo(0);
        assertThat(visionItem.getProjectId()).isNull();
        assertThat(visionItem.getProjectName()).isNull();
    }

    @Test
    void listAnalyses_withProjectId_shouldApplyProjectFilter() {
        Page<FloorPlanAnalysis> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any())).thenReturn(page);

        floorPlanService.listAnalyses(1, 20, null, "PRJ-1");

        ArgumentCaptor<QueryWrapper<FloorPlanAnalysis>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("project_id =");
    }

    @Test
    void listAnalyses_unassigned_shouldFilterBeforePagination() {
        Page<FloorPlanAnalysis> resultPage = new Page<>(1, 20);
        resultPage.setRecords(List.of());
        resultPage.setTotal(0);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any()))
            .thenReturn(resultPage);

        floorPlanService.listAnalyses(1, 20, null, "PRJ-IGNORED", true);

        ArgumentCaptor<QueryWrapper<FloorPlanAnalysis>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("project_id IS NULL")
            .doesNotContain("project_id =");
    }

    @Test
    void listAnalyses_historicalCadRecord_shouldReadQualityIssuesFromRawResult() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-OLD", FloorPlanService.STATUS_CONFIRMED);
        analysis.setRawResult("{\"qualityIssues\":[{\"code\":\"DIM_MISMATCH\"},{\"code\":\"UNIT_ASSUMED\"}]}");
        Page<FloorPlanAnalysis> resultPage = new Page<>(1, 20);
        resultPage.setRecords(List.of(analysis));
        resultPage.setTotal(1);
        when(analysisMapper.selectPage(org.mockito.ArgumentMatchers.<Page<FloorPlanAnalysis>>any(), any()))
            .thenReturn(resultPage);
        when(roomMapper.selectList(any())).thenReturn(List.of());

        PageResult<FloorPlanAnalysisListItemResponse> result =
            floorPlanService.listAnalyses(1, 20, null, null);

        assertThat(result.getRows().get(0).getQualityIssueCount()).isEqualTo(2);
    }

    // ---------- 项目下户型图批次列表（V14） ----------

    @Test
    void listByProject_shouldValidateVisibilityAndReturnItems() {
        Project project = new Project();
        project.setProjectId("PRJ-1");
        project.setProjectName("滨江华府");
        when(projectService.getAccessibleProject("PRJ-1")).thenReturn(project);
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        analysis.setProjectId("PRJ-1");
        when(analysisMapper.selectList(any())).thenReturn(List.of(analysis));
        when(roomMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(List.of("PRJ-1"))).thenReturn(List.of(project));

        List<FloorPlanAnalysisListItemResponse> rows = floorPlanService.listByProject("PRJ-1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAnalysisId()).isEqualTo("FPA-1");
        assertThat(rows.get(0).getProjectName()).isEqualTo("滨江华府");
        assertThat(rows.get(0).getThumbnailUrl()).isEqualTo("/api/v1/images/IMG-1");
        // 批次无空间明细时几何来源为 null
        assertThat(rows.get(0).getGeometrySource()).isNull();
        assertThat(rows.get(0).getQualityIssueCount()).isEqualTo(0);
        verify(projectService).getAccessibleProject("PRJ-1");
    }

    @Test
    void listByProject_inaccessibleProject_shouldThrow() {
        when(projectService.getAccessibleProject("PRJ-X"))
            .thenThrow(new ForbiddenException("无权访问该项目: PRJ-X"));

        assertThatThrownBy(() -> floorPlanService.listByProject("PRJ-X"))
            .isInstanceOf(ForbiddenException.class);
        verify(analysisMapper, never()).selectList(any());
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
        assertThat(room.getLabel()).isEqualTo("客厅");
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
    void confirmRooms_cadRoom_shouldPreserveExactGeometryAndArea() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        FloorPlanRoom cadRoom = buildRoom("FPR-CAD-1", "OTHER");
        cadRoom.setLabel("原始空间");
        cadRoom.setWidthMm(10110);
        cadRoom.setDepthMm(8360);
        cadRoom.setAreaM2(new BigDecimal("47.43"));
        cadRoom.setBbox("{\"x\":0.1,\"y\":0.2,\"w\":0.3,\"h\":0.4}");
        cadRoom.setPolygon("[[1,2],[3,4],[5,6]]");
        cadRoom.setCentroid("{\"x\":3,\"y\":4}");
        cadRoom.setGeometrySource(FloorPlanService.GEOMETRY_SOURCE_CAD);
        cadRoom.setDimensionSource("cad_geometry");
        cadRoom.setDimensionConfidence("mid");
        when(roomMapper.selectList(any())).thenReturn(List.of(cadRoom));

        FloorPlanConfirmRequest.RoomItem item = new FloorPlanConfirmRequest.RoomItem();
        item.setRoomId("FPR-CAD-1");
        item.setRoomType("LIVING_ROOM");
        item.setLabel("客厅");
        // CAD 尺寸在前端只读；即使旧客户端仍传空值或错误矩形，也不能破坏精确几何。
        item.setWidthMm(null);
        item.setDepthMm(null);
        item.setBbox(new FloorPlanBBox(0.8, 0.8, 0.1, 0.1));
        FloorPlanConfirmRequest request = new FloorPlanConfirmRequest();
        request.setRooms(List.of(item));

        floorPlanService.confirmRooms("FPA-1", request);

        ArgumentCaptor<FloorPlanRoom> captor = ArgumentCaptor.forClass(FloorPlanRoom.class);
        verify(roomMapper).updateById(captor.capture());
        FloorPlanRoom updated = captor.getValue();
        assertThat(updated.getRoomType()).isEqualTo("LIVING_ROOM");
        assertThat(updated.getLabel()).isEqualTo("客厅");
        assertThat(updated.getWidthMm()).isEqualTo(10110);
        assertThat(updated.getDepthMm()).isEqualTo(8360);
        assertThat(updated.getAreaM2()).isEqualByComparingTo("47.43");
        assertThat(updated.getBbox()).isEqualTo("{\"x\":0.1,\"y\":0.2,\"w\":0.3,\"h\":0.4}");
        assertThat(updated.getPolygon()).isEqualTo("[[1,2],[3,4],[5,6]]");
        assertThat(updated.getCentroid()).isEqualTo("{\"x\":3,\"y\":4}");
        assertThat(updated.getDimensionSource()).isEqualTo("cad_geometry");
        assertThat(updated.getDimensionConfidence()).isEqualTo("mid");
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
    void retry_dualFileAnalysis_shouldCarryCadContext() throws Exception {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_FAILED);
        analysis.setErrorMessage("CAD 解析服务连接失败");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        AsyncTask oldTask = new AsyncTask();
        oldTask.setTaskId("TASK-1");
        oldTask.setStatus("failed");
        oldTask.setInputData("{\"analysisId\":\"FPA-1\",\"objectKey\":\"images/fp.png\","
            + "\"cadObjectKey\":\"cad/fp.dwg\",\"codeNameMode\":true,\"hint\":\"三室两厅\"}");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(oldTask);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setStoragePath("images/fp.png");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);

        Map<String, String> result = floorPlanService.retry("FPA-1");

        // 新任务 input_data 沿用 CAD 对象键与代号模式标记
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getInputData())
            .contains("cad/fp.dwg").contains("codeNameMode").contains("三室两厅");

        // 双文件通道路由 6 参异步形态（CAD 通道 + 代号模式）
        verify(asyncTaskProcessor).processFloorPlanAnalysis(
            org.mockito.ArgumentMatchers.eq(result.get("taskId")),
            org.mockito.ArgumentMatchers.eq("FPA-1"),
            org.mockito.ArgumentMatchers.eq("images/fp.png"),
            org.mockito.ArgumentMatchers.eq("三室两厅"),
            org.mockito.ArgumentMatchers.eq("cad/fp.dwg"),
            org.mockito.ArgumentMatchers.eq(true));
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
        assertThat(living.getLabel()).isEqualTo("客厅");
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

    // ---------- 自动标定建议（二期，随 getAnalysis 返回） ----------

    @Test
    void getAnalysis_scaleSuggestion_consistentEstimates_shouldReturnAuto() {
        // 原图 2000×1500px；两房间 OCR 尺寸 + bbox 反推比例一致（均 5.0 mm/px）→ auto
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        // 客厅：4000/(0.4×2000)=5.0，3000/(0.4×1500)=5.0；主卧：3000/(0.3×2000)=5.0，3000/(0.4×1500)=5.0
        when(roomMapper.selectList(any())).thenReturn(List.of(
            roomWithDim("FPR-1", "LIVING_ROOM", "4000×3000", 0.4, 0.4),
            roomWithDim("FPR-2", "BEDROOM", "3000×3000", 0.3, 0.4)));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getScaleSuggestion()).isNotNull();
        assertThat(response.getScaleSuggestion().getStatus()).isEqualTo("auto");
        assertThat(response.getScaleSuggestion().getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.getScaleSuggestion().getBasisLabel()).isEqualTo("客厅");
    }

    @Test
    void getAnalysis_scaleSuggestion_stretchedRoomOutlier_shouldExcludeAndReport() {
        // 原图 2000×1500px；客厅/主卧反推 5.0 mm/px（主簇），书房图块被拉伸（15.0/6.2）→ 离群剔除并上报
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        when(roomMapper.selectList(any())).thenReturn(List.of(
            roomWithDim("FPR-1", "LIVING_ROOM", "4000×3000", 0.4, 0.4),
            roomWithDim("FPR-2", "BEDROOM", "3000×3000", 0.3, 0.4),
            roomWithDim("FPR-3", "STUDY", "2100×1860", 0.07, 0.2)));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getScaleSuggestion().getStatus()).isEqualTo("auto");
        assertThat(response.getScaleSuggestion().getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.getScaleSuggestion().getBasisLabel()).isEqualTo("客厅");
        assertThat(response.getScaleSuggestion().getOutliers()).hasSize(2);
        assertThat(response.getScaleSuggestion().getOutliers().get(0).getLabel()).isEqualTo("书房");
        assertThat(response.getScaleSuggestion().getOutliers().get(0).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("6.20"));
        assertThat(response.getScaleSuggestion().getOutliers().get(1).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("15.00"));
    }

    @Test
    void getAnalysis_scaleSuggestion_divergentEstimates_shouldReturnCandidates() {
        // 各房间估计互不一致（5.0/6.0/6.5/7.0，两两偏差 >5%，最大最小偏差 40% >8%）→ candidates
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        // 客厅：4000/800=5.0，3600/600=6.0（均值 5.5）；主卧：3900/600=6.5，4200/600=7.0（均值 6.75）
        when(roomMapper.selectList(any())).thenReturn(List.of(
            roomWithDim("FPR-1", "LIVING_ROOM", "4000×3600", 0.4, 0.4),
            roomWithDim("FPR-2", "BEDROOM", "3900×4200", 0.3, 0.4)));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getScaleSuggestion().getStatus()).isEqualTo("candidates");
        assertThat(response.getScaleSuggestion().getMmPerPx()).isNull();
        assertThat(response.getScaleSuggestion().getCandidates()).hasSize(2);
        assertThat(response.getScaleSuggestion().getCandidates().get(0).getLabel()).isEqualTo("客厅");
        assertThat(response.getScaleSuggestion().getCandidates().get(0).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.50"));
        assertThat(response.getScaleSuggestion().getCandidates().get(0).getDimensionText())
            .isEqualTo("4000×3600");
        assertThat(response.getScaleSuggestion().getCandidates().get(1).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("6.75"));
        // 两个候选相对偏差 22.7% >5% → 互不一致
        assertThat(response.getScaleSuggestion().getCandidates().get(0).getAgreed()).isFalse();
        assertThat(response.getScaleSuggestion().getCandidates().get(1).getAgreed()).isFalse();
        assertThat(response.getScaleSuggestion().getOutliers()).isNull();
    }

    @Test
    void getAnalysis_scaleSuggestion_noParseableRoom_shouldReturnNullStatus() {
        // 无可解析尺寸标注房间 → status=null（不下发 mmPerPx/candidates）
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId("IMG-1");
        imageAsset.setWidth(2000);
        imageAsset.setHeight(1500);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(imageAsset);
        when(roomMapper.selectList(any())).thenReturn(List.of(
            roomWithDim("FPR-1", "LIVING_ROOM", null, 0.4, 0.4)));

        FloorPlanAnalysisResponse response = floorPlanService.getAnalysis("FPA-1");

        assertThat(response.getScaleSuggestion()).isNotNull();
        assertThat(response.getScaleSuggestion().getStatus()).isNull();
        assertThat(response.getScaleSuggestion().getMmPerPx()).isNull();
        assertThat(response.getScaleSuggestion().getCandidates()).isNull();
    }

    /** 构造带尺寸标注与 bbox 的空间明细（标定建议测试用）。 */
    private FloorPlanRoom roomWithDim(String roomId, String roomType,
                                      String dimensionText, double bboxW, double bboxH) {
        FloorPlanRoom room = roomOf(roomId, "FPA-1", roomType);
        room.setDimensionText(dimensionText);
        room.setBbox("{\"x\":0.1,\"y\":0.2,\"w\":" + bboxW + ",\"h\":" + bboxH + "}");
        return room;
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

    @Test
    void batchDeleteAnalyses_shouldReturnPartialFailuresAndDeduplicateIds() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED);
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        when(analysisMapper.selectById("FPA-MISSING")).thenReturn(null);

        var result = floorPlanService.batchDeleteAnalyses(
            List.of(" FPA-1 ", "FPA-MISSING", "FPA-1"));

        assertThat(result.getDeletedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getAnalysisId()).isEqualTo("FPA-MISSING");
        verify(analysisMapper, times(1)).deleteById("FPA-1");
    }

    @Test
    void batchDeleteAnalyses_blankId_shouldReturnFailureWithoutDeleting() {
        var result = floorPlanService.batchDeleteAnalyses(List.of("  "));

        assertThat(result.getDeletedCount()).isZero();
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("不能为空");
        verify(analysisMapper, never()).deleteById(anyString());
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
    void getPublicAnalysis_publicSource_shouldReturnAnalysisWithoutLogin() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-PUBLIC", FloorPlanService.STATUS_AWAITING_CONFIRM);
        analysis.setSource("public");
        analysis.setCreatedBy(null);
        when(analysisMapper.selectById("FPA-PUBLIC")).thenReturn(analysis);
        when(roomMapper.selectList(any())).thenReturn(List.of());

        FloorPlanAnalysisResponse response = floorPlanService.getPublicAnalysis("FPA-PUBLIC");

        assertThat(response.getAnalysisId()).isEqualTo("FPA-PUBLIC");
        assertThat(response.getStatus()).isEqualTo(FloorPlanService.STATUS_AWAITING_CONFIRM);
    }

    @Test
    void getPublicAnalysis_nonPublicSource_shouldThrowNotFound() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-ADMIN", FloorPlanService.STATUS_AWAITING_CONFIRM);
        when(analysisMapper.selectById("FPA-ADMIN")).thenReturn(analysis);

        assertThatThrownBy(() -> floorPlanService.getPublicAnalysis("FPA-ADMIN"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(roomMapper, never()).selectList(any());
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
