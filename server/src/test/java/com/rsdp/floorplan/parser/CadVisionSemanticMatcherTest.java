package com.rsdp.floorplan.parser;

import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CadVisionSemanticMatcherTest {

    @Test
    void fuse_shouldUseVisionSemanticsAndPreserveCadGeometry() {
        CadParseResult cad = new CadParseResult();
        cad.setSuccess(true);
        cad.setUnnamedRegions(List.of(
            region(20, 80, 18.25),
            region(80, 80, 14.50),
            region(20, 20, 7.30),
            region(80, 20, 4.60)));
        CadParseResult.QualityIssue noLabels = new CadParseResult.QualityIssue();
        noLabels.setLevel("warn");
        noLabels.setCode("NO_ROOM_LABELS");
        noLabels.setMessage("全图未命中房间名标签");
        cad.setQualityIssues(List.of(noLabels));

        FloorPlanDetectResult vision = new FloorPlanDetectResult();
        vision.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "5000×3600",
                0.15, 0.15, 0.20, 0.20),
            new FloorPlanDetectResult.Room("bedroom", "主卧", "4000×3600",
                0.65, 0.15, 0.20, 0.20),
            new FloorPlanDetectResult.Room("kitchen", "厨房", "3000×2400",
                0.15, 0.65, 0.20, 0.20),
            new FloorPlanDetectResult.Room("bathroom", "公卫", "2400×1900",
                0.65, 0.65, 0.20, 0.20)));

        CadVisionSemanticMatcher.FusionSummary summary =
            CadVisionSemanticMatcher.fuse(cad, vision);

        assertThat(summary.matchedCount()).isEqualTo(4);
        assertThat(cad.getUnnamedRegions()).isEmpty();
        assertThat(cad.getRooms()).extracting(CadParseResult.Room::getLabel)
            .containsExactlyInAnyOrder("客厅", "主卧", "厨房", "公卫");
        assertThat(cad.getRooms()).extracting(CadParseResult.Room::getRoomType)
            .containsExactlyInAnyOrder("living_room", "bedroom", "kitchen", "bathroom");
        assertThat(cad.getRooms()).extracting(CadParseResult.Room::getAreaM2)
            .containsExactlyInAnyOrder(18.25, 14.50, 7.30, 4.60);
        assertThat(cad.getRooms()).allSatisfy(room -> {
            assertThat(room.getPolygon()).isNotEmpty();
            assertThat(room.getDimensionSource()).isEqualTo("cad_geometry");
            assertThat(room.getConfidence()).isEqualTo("mid");
        });
        assertThat(cad.getQualityIssues()).isEmpty();
    }

    @Test
    void fuse_shouldIgnoreGenericVisionLabels() {
        CadParseResult cad = new CadParseResult();
        cad.setUnnamedRegions(List.of(
            region(20, 80, 10), region(80, 80, 9),
            region(20, 20, 8), region(80, 20, 7)));
        FloorPlanDetectResult vision = new FloorPlanDetectResult();
        vision.setRooms(List.of(
            new FloorPlanDetectResult.Room("other", "空间 1", null, 0.1, 0.1, 0.2, 0.2),
            new FloorPlanDetectResult.Room("other", "未命名空间 2", null, 0.7, 0.7, 0.2, 0.2)));

        CadVisionSemanticMatcher.FusionSummary summary =
            CadVisionSemanticMatcher.fuse(cad, vision);

        assertThat(summary.matchedCount()).isZero();
        assertThat(cad.getRooms()).isEmpty();
        assertThat(cad.getUnnamedRegions()).hasSize(4);
    }

    @Test
    void fuse_shouldRegisterSheetCropAndKeepOrdinaryRoomsOneToOne() throws Exception {
        CadParseResult cad = new CadParseResult();
        cad.setSuccess(true);
        CadParseResult.Bounds drawing = new CadParseResult.Bounds();
        drawing.setMinX(107065.0);
        drawing.setMinY(-28735.0);
        drawing.setMaxX(120695.0);
        drawing.setMaxY(-14715.0);
        cad.setDrawingBounds(drawing);
        cad.setUnnamedRegions(List.of(
            region(37.0, 107305, -26575, 116075, -22315, 111690, -23730),
            region(22.74, 114415, -27175, 119895, -19705, 118105, -24395),
            region(10.53, 109775, -28535, 116075, -26815, 112925, -27575),
            region(10.31, 116315, -18275, 119395, -14915, 117855, -15955),
            region(10.06, 111445, -18515, 114375, -14915, 112910, -16215),
            region(7.35, 117415, -21275, 120495, -18515, 119289, -20190),
            region(6.80, 112515, -22315, 114360, -18515, 113405, -20925),
            region(5.09, 117530, -23295, 120495, -21515, 119013, -22155),
            region(3.93, 107305, -28535, 109655, -26815, 108480, -27745),
            region(3.17, 114422, -19585, 117415, -18515, 115933, -19055),
            region(2.65, 114495, -18275, 116075, -16535, 115285, -17465),
            region(2.46, 114495, -16535, 116075, -14915, 115285, -15855),
            region(1.56, 107265, -22075, 109645, -20005, 107365, -20815)));

        FloorPlanDetectResult vision = new FloorPlanDetectResult();
        vision.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "2640×1800", 0.4476, 0.5426, 0.18816, 0.1837),
            new FloorPlanDetectResult.Room("dining_room", "餐厅", "1350×1350", 0.356376, 0.567817, 0.081648, 0.119238),
            new FloorPlanDetectResult.Room("kitchen", "厨房", "600×1770", 0.28968, 0.496675, 0.07056, 0.22545),
            new FloorPlanDetectResult.Room("bedroom", "卧室", "1600×3360", 0.432682, 0.160003, 0.064109, 0.119238),
            new FloorPlanDetectResult.Room("bedroom", "卧室", "1500×2660", 0.63576, 0.20025, 0.08064, 0.1169),
            new FloorPlanDetectResult.Room("bedroom", "卧室", "2100×1800", 0.64295, 0.548779, 0.095962, 0.171342),
            new FloorPlanDetectResult.Room("bathroom", "卫生间", "1030×900", 0.53711, 0.206513, 0.054835, 0.112725),
            new FloorPlanDetectResult.Room("bathroom", "卫生间", "1100×1000", 0.632534, 0.451126, 0.102816, 0.092434),
            new FloorPlanDetectResult.Room("balcony", "阳台", "600×1720", 0.288605, 0.701584, 0.105235, 0.083166),
            new FloorPlanDetectResult.Room("balcony", "阳台", "1500×1200", 0.56856, 0.70125, 0.08064, 0.0501),
            new FloorPlanDetectResult.Room("hallway", "入户过道", null, 0.381744, 0.351594, 0.082656, 0.203531),
            new FloorPlanDetectResult.Room("other", "储物间", "1300×1000", 0.530122, 0.356395, 0.073382, 0.117234)));

        BufferedImage reference = new BufferedImage(1782, 1197, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(reference, "png", imageBytes);
        CadVisionSemanticMatcher.FusionSummary summary =
            CadVisionSemanticMatcher.fuse(cad, vision, imageBytes.toByteArray());

        assertThat(summary.matchedCount()).isGreaterThanOrEqualTo(10);
        assertThat(cad.getRooms()).anySatisfy(room -> {
            assertThat(room.getAreaM2()).isEqualTo(37.0);
            assertThat(room.getLabel()).contains("客厅", "餐厅", "厨房");
            assertThat(room.getRoomType()).isEqualTo("living_room");
        });
        assertThat(cad.getRooms().stream()
            .filter(room -> "bedroom".equals(room.getRoomType()))
            .map(CadParseResult.Room::getAreaM2))
            .containsExactlyInAnyOrder(10.06, 10.31, 22.74);
        assertThat(cad.getRooms()).anySatisfy(room -> {
            assertThat(room.getAreaM2()).isEqualTo(10.53);
            assertThat(room.getRoomType()).isEqualTo("balcony");
        });
    }

    private CadParseResult.UnnamedRegion region(double x, double y, double area) {
        CadParseResult.UnnamedRegion region = new CadParseResult.UnnamedRegion();
        CadParseResult.Point point = new CadParseResult.Point();
        point.setX(x);
        point.setY(y);
        region.setLabelPoint(point);
        region.setAreaM2(area);
        region.setWidthMm(3000.0);
        region.setDepthMm(3000.0);
        region.setPolygon(List.of(
            List.of(x - 5, y - 5), List.of(x + 5, y - 5),
            List.of(x + 5, y + 5), List.of(x - 5, y + 5)));
        return region;
    }

    private CadParseResult.UnnamedRegion region(double area, double minX, double minY,
                                                 double maxX, double maxY,
                                                 double centerX, double centerY) {
        CadParseResult.UnnamedRegion region = new CadParseResult.UnnamedRegion();
        CadParseResult.Point point = new CadParseResult.Point();
        point.setX(centerX);
        point.setY(centerY);
        region.setLabelPoint(point);
        region.setAreaM2(area);
        region.setWidthMm(Math.max(maxX - minX, maxY - minY));
        region.setDepthMm(Math.min(maxX - minX, maxY - minY));
        region.setPolygon(List.of(
            List.of(minX, minY), List.of(maxX, minY),
            List.of(maxX, maxY), List.of(minX, maxY)));
        return region;
    }
}
