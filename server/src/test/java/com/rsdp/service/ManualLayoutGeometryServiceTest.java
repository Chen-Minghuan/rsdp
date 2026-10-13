package com.rsdp.service;

import com.rsdp.dto.request.ManualFloorPlanLayoutRequest;
import com.rsdp.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ManualLayoutGeometryService} 的正交矩形并集回归测试。 */
class ManualLayoutGeometryServiceTest {

    private final ManualLayoutGeometryService service = new ManualLayoutGeometryService();

    @Test
    void compute_singleRectangle_shouldReturnExactGeometry() {
        var result = service.compute(layout(room("客厅", List.of(rect("p1", 100, 200, 4000, 3000)), null)));

        var computed = result.rooms().getFirst();
        assertThat(computed.areaMm2()).isEqualTo(12_000_000L);
        assertThat(computed.polygon()).hasSize(4);
        assertThat(computed.bounds()).isEqualTo(new ManualLayoutGeometryService.Bounds(100, 200, 4100, 3200));
        assertThat(computed.labelPoint()).isEqualTo(new ManualLayoutGeometryService.Point(2100, 1700));
    }

    @Test
    void compute_unboundedLayoutShouldAcceptNegativeAndBeyondInitialViewport() {
        var room = room("扩展办公室", List.of(rect("p1", -3000, -2000, 25_000, 8000)),
            rect(null, -2500, -1500, 2000, 1000));
        var layout = new ManualFloorPlanLayoutRequest.Layout(3,
            new ManualFloorPlanLayoutRequest.Canvas(18_000, 12_000, 50, "unbounded"), List.of(room));

        var computed = service.compute(layout).rooms().getFirst();

        assertThat(computed.bounds()).isEqualTo(new ManualLayoutGeometryService.Bounds(-3000, -2000, 22_000, 6000));
        assertThat(computed.placementZone().x()).isEqualTo(-2500);
    }

    @Test
    void compute_unboundedLayoutShouldNotStopAtFormerTenKilometerGuard() {
        var distant = room("远处空间", List.of(rect("p1", 12_000_000, -12_000_000, 4000, 3000)), null);
        var layout = new ManualFloorPlanLayoutRequest.Layout(3,
            new ManualFloorPlanLayoutRequest.Canvas(18_000, 12_000, 50, "unbounded"), List.of(distant));

        assertThat(service.compute(layout).rooms().getFirst().bounds())
            .isEqualTo(new ManualLayoutGeometryService.Bounds(12_000_000, -12_000_000, 12_004_000, -11_997_000));
    }

    @Test
    void compute_legacyLayoutShouldStillRejectCanvasOverflow() {
        var legacy = layout(room("旧版", List.of(rect("p1", -1, 0, 2000, 1000)), null));
        assertThatThrownBy(() -> service.compute(legacy))
            .isInstanceOf(BusinessException.class).hasMessageContaining("超出画布范围");
    }

    @Test
    void compute_overlappingRectangles_shouldBuildLShapeAndDeduplicateArea() {
        var result = service.compute(layout(room("L 型客厅", List.of(
            rect("p1", 0, 0, 4000, 2000),
            rect("p2", 0, 1000, 2000, 3000)), null)));

        var computed = result.rooms().getFirst();
        assertThat(computed.areaMm2()).isEqualTo(12_000_000L);
        assertThat(computed.polygon()).hasSize(6);
    }

    @Test
    void compute_sharedEdgeTShape_shouldBeValid() {
        var result = service.compute(layout(room("T 型空间", List.of(
            rect("top", 0, 0, 3000, 1000),
            rect("stem", 1000, 1000, 1000, 2000)), null)));

        assertThat(result.rooms().getFirst().areaMm2()).isEqualTo(5_000_000L);
        assertThat(result.rooms().getFirst().polygon()).hasSize(8);
    }

    @Test
    void compute_uAndStairShapes_shouldBeValid() {
        var uShape = room("U 型空间", List.of(
            rect("left", 0, 0, 1000, 3000),
            rect("bottom", 1000, 2000, 1000, 1000),
            rect("right", 2000, 0, 1000, 3000)), null);
        var stair = room("台阶空间", List.of(
            rect("s1", 4000, 0, 3000, 1000),
            rect("s2", 4000, 1000, 2000, 1000),
            rect("s3", 4000, 2000, 1000, 1000)), null);

        var result = service.compute(layout(uShape, stair));

        assertThat(result.rooms()).hasSize(2);
        assertThat(result.rooms().get(0).areaMm2()).isEqualTo(7_000_000L);
        assertThat(result.rooms().get(1).areaMm2()).isEqualTo(6_000_000L);
    }

    @Test
    void compute_pointOnlyContact_shouldRejectDisconnectedShape() {
        var invalid = room("点接触", List.of(
            rect("p1", 0, 0, 1000, 1000),
            rect("p2", 1000, 1000, 1000, 1000)), null);

        assertThatThrownBy(() -> service.compute(layout(invalid)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不连通");
    }

    @Test
    void compute_separateParts_shouldRejectDisconnectedShape() {
        var invalid = room("断开的空间", List.of(
            rect("p1", 0, 0, 1000, 1000),
            rect("p2", 2000, 0, 1000, 1000)), null);

        assertThatThrownBy(() -> service.compute(layout(invalid)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不连通");
    }

    @Test
    void compute_ring_shouldRejectHole() {
        var invalid = room("回字空间", List.of(
            rect("top", 0, 0, 3000, 1000),
            rect("right", 2000, 1000, 1000, 1000),
            rect("bottom", 0, 2000, 3000, 1000),
            rect("left", 0, 1000, 1000, 1000)), null);

        assertThatThrownBy(() -> service.compute(layout(invalid)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("孔洞");
    }

    @Test
    void compute_placementZoneOutsideUnion_shouldReject() {
        var zone = rect(null, 2500, 0, 1000, 1000);
        var invalid = room("L 型空间", List.of(
            rect("p1", 0, 0, 2000, 3000),
            rect("p2", 2000, 2000, 2000, 1000)), zone);

        assertThatThrownBy(() -> service.compute(layout(invalid)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须完全位于空间轮廓内");
    }

    @Test
    void compute_multiplePlacementZones_shouldKeepAllAndUseFirstForDimensions() {
        var first = rect(null, 100, 100, 1000, 800);
        var second = rect(null, 1500, 1000, 1200, 900);
        var room = new ManualFloorPlanLayoutRequest.Room("room-1", "客厅", "LIVING_ROOM",
            List.of(rect("p1", 0, 0, 4000, 3000)), first, List.of(first, second));

        var computed = service.compute(layout(room)).rooms().getFirst();

        assertThat(computed.placementZone()).isEqualTo(first);
        assertThat(computed.placementZones()).containsExactly(first, second);
    }

    @Test
    void compute_secondPlacementZoneOutsideUnion_shouldReject() {
        var room = new ManualFloorPlanLayoutRequest.Room("room-1", "客厅", "LIVING_ROOM",
            List.of(rect("p1", 0, 0, 2000, 2000)), null,
            List.of(rect(null, 100, 100, 500, 500), rect(null, 1800, 1800, 500, 500)));

        assertThatThrownBy(() -> service.compute(layout(room)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("布置区 2 必须完全位于空间轮廓内");
    }

    @Test
    void compute_overlappingRooms_shouldReject() {
        var first = room("客厅", List.of(rect("p1", 0, 0, 3000, 3000)), null);
        var second = room("餐厅", List.of(rect("p2", 2000, 1000, 3000, 2000)), null);

        assertThatThrownBy(() -> service.compute(layout(first, second)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("存在面积重叠");
    }

    @Test
    void compute_missingPlacementZone_shouldUseLargestPart() {
        var result = service.compute(layout(room("客厅", List.of(
            rect("small", 0, 0, 1000, 1000),
            rect("large", 1000, 0, 3000, 2000)), null)));

        var zone = result.rooms().getFirst().placementZone();
        assertThat(zone.id()).isNull();
        assertThat(zone.x()).isEqualTo(1000);
        assertThat(zone.w()).isEqualTo(3000);
        assertThat(zone.h()).isEqualTo(2000);
    }

    @Test
    void compute_furnitureShouldRespectRotationAndRoomBoundary() {
        var room = room("办公室", List.of(rect("p1", 1000, 1000, 3000, 2000)), null);
        var inside = new ManualFloorPlanLayoutRequest.Furniture(
            "f1", "desk-straight", room.clientId(), 3000, 2000, 1600, 700, 90, null);
        service.compute(new ManualFloorPlanLayoutRequest.Layout(2,
            new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100), List.of(room), List.of(inside)));
        var outside = new ManualFloorPlanLayoutRequest.Furniture(
            "f1", "desk-straight", room.clientId(), 3900, 2000, 1600, 700, 90, null);
        assertThatThrownBy(() -> service.compute(new ManualFloorPlanLayoutRequest.Layout(2,
            new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100), List.of(room), List.of(outside))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("超出所属空间");
    }

    @Test
    void compute_furnitureShouldRejectInvalidTemplateAndShapeParameters() {
        var room = room("办公室", List.of(rect("p1", 0, 0, 5000, 5000)), null);
        var invalidTemplate = new ManualFloorPlanLayoutRequest.Furniture(
            "f1", null, room.clientId(), 2000, 2000, 1000, 1000, 0, null);
        var invalidWing = new ManualFloorPlanLayoutRequest.Furniture(
            "f2", "desk-l", room.clientId(), 2000, 2000, 1600, 1400, 0, 700);
        var invalidRound = new ManualFloorPlanLayoutRequest.Furniture(
            "f3", "table-meeting-round", room.clientId(), 2000, 2000, 1000, 900, 0, null);
        for (var item : List.of(invalidTemplate, invalidWing, invalidRound)) {
            assertThatThrownBy(() -> service.compute(new ManualFloorPlanLayoutRequest.Layout(2,
                new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100), List.of(room), List.of(item))))
                .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void compute_referenceFurnitureShouldAcceptNewTemplatesAndCabinetHeightRanges() {
        var room = room("办公室", List.of(rect("p1", 0, 0, 8000, 6000)), null);
        var roundedDesk = new ManualFloorPlanLayoutRequest.Furniture(
            "desk", "desk-l-rounded", room.clientId(), 2500, 2500, 1400, 1400, 0, 550, null);
        var shortCabinet = new ManualFloorPlanLayoutRequest.Furniture(
            "cabinet", "cabinet-short", room.clientId(), 5000, 2000, 1200, 450, 0, null, 900);
        var oldHighCabinet = new ManualFloorPlanLayoutRequest.Furniture(
            "old", "cabinet-high", room.clientId(), 6000, 4000, 1200, 450, 0, null);

        var result = service.compute(new ManualFloorPlanLayoutRequest.Layout(2,
            new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100),
            List.of(room), List.of(roundedDesk, shortCabinet, oldHighCabinet)));

        assertThat(result.rooms()).hasSize(1);
    }

    @Test
    void compute_referenceCabinetShouldRejectHeightOutsideSelectedType() {
        var room = room("办公室", List.of(rect("p1", 0, 0, 5000, 5000)), null);
        for (var invalid : List.of(
            new ManualFloorPlanLayoutRequest.Furniture("low", "cabinet-low", room.clientId(),
                2000, 2000, 1200, 450, 0, null, 401),
            new ManualFloorPlanLayoutRequest.Furniture("short", "cabinet-short", room.clientId(),
                2000, 2000, 1200, 450, 0, null, 400),
            new ManualFloorPlanLayoutRequest.Furniture("high", "cabinet-high", room.clientId(),
                2000, 2000, 1200, 450, 0, null, 1499))) {
            assertThatThrownBy(() -> service.compute(new ManualFloorPlanLayoutRequest.Layout(2,
                new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100),
                List.of(room), List.of(invalid))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("高度与图例类型不匹配");
        }
    }

    private static ManualFloorPlanLayoutRequest.Layout layout(ManualFloorPlanLayoutRequest.Room... rooms) {
        return new ManualFloorPlanLayoutRequest.Layout(
            1,
            new ManualFloorPlanLayoutRequest.Canvas(20_000, 20_000, 100),
            List.of(rooms));
    }

    private static ManualFloorPlanLayoutRequest.Room room(
        String label, List<ManualFloorPlanLayoutRequest.Rect> parts,
        ManualFloorPlanLayoutRequest.Rect placementZone) {
        return new ManualFloorPlanLayoutRequest.Room(
            "room-" + label, label, "LIVING_ROOM", parts, placementZone, null);
    }

    private static ManualFloorPlanLayoutRequest.Rect rect(
        String id, int x, int y, int width, int height) {
        return new ManualFloorPlanLayoutRequest.Rect(id, x, y, width, height);
    }
}
