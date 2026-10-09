package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.FloorPlanFurniture;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.FloorPlanFurnitureMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link FloorPlanFurnitureSemanticService} 单元测试（Mockito）：
 * 分组共享视觉识别、置信度分级回填、REJECTED/已分类跳过、上限截断、隔离图缺失跳过。
 */
class FloorPlanFurnitureSemanticServiceTest {

    private FloorPlanFurnitureMapper furnitureMapper;
    private VisionService visionService;
    private com.rsdp.service.storage.StorageService storageService;
    private FloorPlanFurnitureSemanticService service;

    @BeforeEach
    void setUp() throws Exception {
        furnitureMapper = mock(FloorPlanFurnitureMapper.class);
        visionService = mock(VisionService.class);
        storageService = mock(com.rsdp.service.storage.StorageService.class);
        service = new FloorPlanFurnitureSemanticService(furnitureMapper, visionService,
            mockRoomMapper(), new ObjectMapper(), storageService);
        setField(service, "enabled", true);
        setField(service, "maxGroups", 30);
        setField(service, "contextMarginMm", 600.0);
    }

    private com.rsdp.mapper.FloorPlanRoomMapper mockRoomMapper() {
        var mapper = mock(com.rsdp.mapper.FloorPlanRoomMapper.class);
        var room = new com.rsdp.entity.FloorPlanRoom();
        room.setRoomId("R-LIVING");
        room.setRoomType("LIVING");
        when(mapper.selectList(any())).thenReturn(List.of(room));
        return mapper;
    }

    @Test
    void enrich_skipsBedroomAndUnassignedEvenWhenSharingVisionGroup() {
        var living = furniture("F-L", "VG-1", "UNKNOWN", 2000, 900);
        var bedroom = furniture("F-B", "VG-1", "UNKNOWN", 2000, 900);
        bedroom.setRoomId("R-BED");
        var unassigned = furniture("F-U", "VG-1", "UNKNOWN", 2000, 900);
        unassigned.setRoomId(null);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(living, bedroom, unassigned));
        when(visionService.classifyFurnitureObject(any(), any()))
            .thenReturn(new VisionService.FurnitureVisionVerdict("SOFA", "沙发", 0.92));
        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1"), null);
        verify(furnitureMapper).updateById(living);
        verify(furnitureMapper, never()).updateById(bedroom);
        verify(furnitureMapper, never()).updateById(unassigned);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private FloorPlanFurniture furniture(String id, String vg, String category, int w, int d) {
        FloorPlanFurniture f = new FloorPlanFurniture();
        f.setFurnitureId(id);
        f.setRoomId("R-LIVING");
        f.setAnalysisId("A1");
        f.setAnalysisRevision(1);
        f.setVisionGroupId(vg);
        f.setCategory(category);
        f.setCandidateStatus("VALID");
        f.setConfirmationStatus(FloorPlanFurniture.CONFIRM_AUTO);
        f.setGeometryWidthMm(w);
        f.setGeometryDepthMm(d);
        f.setFootprintPolygon("[[0,0],[1000,0],[1000,800],[0,800],[0,0]]");
        return f;
    }

    private CadParseResult cadResultWithIsolation(String... vgIds) {
        CadParseResult result = new CadParseResult();
        Map<String, String> images = new java.util.HashMap<>();
        for (String vg : vgIds) {
            images.put(vg, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        }
        result.setIsolationImages(images);
        return result;
    }

    @Test
    void enrich_backfillsAllInstancesOfSharedVisionGroup() {
        FloorPlanFurniture a = furniture("F-1", "VG-1", "UNKNOWN", 2000, 900);
        FloorPlanFurniture b = furniture("F-2", "VG-1", "UNKNOWN", 2000, 900);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(a, b));
        when(visionService.classifyFurnitureObject(any(), any()))
            .thenReturn(new VisionService.FurnitureVisionVerdict("SOFA", "三人沙发", 0.92));

        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1"), null);

        // 同组只调一次视觉
        verify(visionService, times(1)).classifyFurnitureObject(any(), any());
        for (FloorPlanFurniture f : List.of(a, b)) {
            assertEquals("SOFA", f.getCategory());
            assertEquals("三人沙发", f.getSemanticLabel());
            assertEquals("vision_qwen3vl", f.getSemanticSource());
            assertEquals("high", f.getCategoryConfidence());
            assertEquals(0.92, f.getVisionConfidence().doubleValue(), 1e-6);
            verify(furnitureMapper).updateById(f);
        }
    }

    @Test
    void enrich_midConfidenceMarkedReview_lowConfidenceNotAdopted() {
        FloorPlanFurniture mid = furniture("F-1", "VG-1", "UNKNOWN", 2000, 900);
        FloorPlanFurniture low = furniture("F-2", "VG-2", "UNKNOWN", 1800, 800);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(mid, low));
        when(visionService.classifyFurnitureObject(any(), any()))
            .thenReturn(new VisionService.FurnitureVisionVerdict("TABLE", "餐桌", 0.72))
            .thenReturn(new VisionService.FurnitureVisionVerdict("BED", "床", 0.40));

        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1", "VG-2"), null);

        assertEquals("TABLE", mid.getCategory());
        assertEquals("mid", mid.getCategoryConfidence());
        // 低置信：类别不采信，但置信度已记录
        assertEquals("UNKNOWN", low.getCategory());
        assertEquals(0.40, low.getVisionConfidence().doubleValue(), 1e-6);
    }

    @Test
    void enrich_skipsGroupWithoutIsolationImage() {
        FloorPlanFurniture a = furniture("F-1", "VG-1", "UNKNOWN", 2000, 900);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(a));

        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-OTHER"), null);

        verifyNoInteractions(visionService);
        assertEquals("UNKNOWN", a.getCategory());
    }

    @Test
    void enrich_respectsMaxGroupsCap() throws Exception {
        setField(service, "maxGroups", 2);
        List<FloorPlanFurniture> items = List.of(
            furniture("F-1", "VG-1", "UNKNOWN", 3000, 2000),
            furniture("F-2", "VG-2", "UNKNOWN", 2000, 900),
            furniture("F-3", "VG-3", "OTHER", 500, 400));
        when(furnitureMapper.selectList(any())).thenReturn(items);
        when(visionService.classifyFurnitureObject(any(), any()))
            .thenReturn(new VisionService.FurnitureVisionVerdict("SOFA", "沙发", 0.9));

        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1", "VG-2", "VG-3"), null);

        // 只识别前 2 组
        verify(visionService, times(2)).classifyFurnitureObject(any(), any());
    }

    @Test
    void enrichFromStorage_readsIsolationFromStorageAndBackfills() throws Exception {
        FloorPlanFurniture a = furniture("F-1", "VG-1", "UNKNOWN", 2000, 900);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(a));
        when(storageService.get("floor-plan-isolation/A1/VG-1.png"))
            .thenReturn(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(visionService.classifyFurnitureObject(any(), isNull()))
            .thenReturn(new VisionService.FurnitureVisionVerdict("SOFA", "三人沙发", 0.9));

        var summary = service.enrichFromStorage("A1", 1);

        assertEquals(1, summary.processedGroups());
        assertEquals(1, summary.adopted());
        assertEquals(0, summary.skippedNoImage());
        assertEquals("SOFA", a.getCategory());
    }

    @Test
    void enrichFromStorage_skipsGroupWhenImageMissing() throws Exception {
        FloorPlanFurniture a = furniture("F-1", "VG-1", "UNKNOWN", 2000, 900);
        when(furnitureMapper.selectList(any())).thenReturn(List.of(a));
        when(storageService.get(any())).thenThrow(new java.io.IOException("not found"));

        var summary = service.enrichFromStorage("A1", 1);

        assertEquals(0, summary.processedGroups());
        assertEquals(1, summary.skippedNoImage());
        verifyNoInteractions(visionService);
        assertEquals("UNKNOWN", a.getCategory());
    }

    @Test
    void persistIsolationImages_storesEachGroupWithDeterministicKey() throws Exception {
        service.persistIsolationImages("A1", cadResultWithIsolation("VG-1", "VG-2"));

        verify(storageService).store(any(), eq("floor-plan-isolation/A1/VG-1.png"), anyLong(), eq("image/png"));
        verify(storageService).store(any(), eq("floor-plan-isolation/A1/VG-2.png"), anyLong(), eq("image/png"));
    }

    @Test
    void enrich_disabledOrNoCandidatesDoesNothing() throws Exception {
        setField(service, "enabled", false);
        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1"), null);
        verifyNoInteractions(furnitureMapper);

        setField(service, "enabled", true);
        when(furnitureMapper.selectList(any())).thenReturn(List.of());
        service.enrichCadFurniture("A1", 1, cadResultWithIsolation("VG-1"), null);
        verifyNoInteractions(visionService);
    }
}
