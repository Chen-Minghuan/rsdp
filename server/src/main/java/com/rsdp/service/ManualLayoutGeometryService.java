package com.rsdp.service;

import com.rsdp.dto.request.ManualFloorPlanLayoutRequest;
import com.rsdp.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无图纸手画布局的确定性几何引擎。
 *
 * <p>V1 只接收整数毫米的轴对齐矩形组成块，通过坐标压缩计算精确并集边界；
 * 合并结果必须是单一连通、无孔洞的正交多边形。前端可做同算法即时预览，
 * 但确认入库时始终由本服务权威重算。</p>
 */
@Service
public class ManualLayoutGeometryService {

    private static final Set<String> FURNITURE_TEMPLATES = Set.of(
        "desk-straight", "desk-l", "desk-l-rounded", "desk-u", "desk-manager",
        "desk-manager-double", "desk-manager-l", "desk-manager-l-plain",
        "desk-executive", "desk-executive-l", "desk-executive-l-double",
        "desk-training", "desk-standing",
        "counter-straight", "counter-l", "chair-office", "chair-meeting", "chair-visitor",
        "chair-lounge", "stool-high", "table-conference-rect", "table-conference-oval",
        "table-meeting-round", "table-meeting-square", "cabinet-low", "cabinet-short", "cabinet-high",
        "cabinet-mobile", "shelf-open", "locker-staff", "sofa-single", "sofa-double",
        "sofa-triple", "coffee-rect", "coffee-round", "printer-copier", "water-dispenser",
        "plant", "whiteboard", "display-screen");
    private static final Set<String> ROUND_TEMPLATES = Set.of(
        "stool-high", "table-meeting-round", "coffee-round", "plant");
    private static final Set<String> WING_TEMPLATES = Set.of(
        "desk-l", "desk-l-rounded", "desk-u", "desk-manager-l", "desk-manager-l-plain",
        "desk-executive-l", "desk-executive-l-double", "counter-l");

    /**
     * 校验完整布局并计算每个空间的派生几何。
     *
     * @param layout 手画布局快照
     * @return 计算完成的空间集合
     * @throws BusinessException 布局越界、空间不连通、存在孔洞或跨空间重叠时抛出
     */
    public ComputedLayout compute(ManualFloorPlanLayoutRequest.Layout layout) {
        if (layout == null || layout.canvas() == null || layout.rooms() == null) {
            throw new BusinessException("手画布局数据不完整");
        }
        if (layout.canvas().widthMm() == null || layout.canvas().heightMm() == null
            || layout.canvas().widthMm() <= 0 || layout.canvas().heightMm() <= 0) {
            throw new BusinessException("起始视野尺寸无效");
        }
        if (layout.canvas().mode() != null && !"unbounded".equals(layout.canvas().mode())) {
            throw new BusinessException("不支持的手画画布模式");
        }
        if (layout.version() != null && layout.version() >= 3
            && !"unbounded".equals(layout.canvas().mode())) {
            throw new BusinessException("新版手画布局缺少无界画布模式");
        }
        if ("unbounded".equals(layout.canvas().mode())
            && (layout.version() == null || layout.version() < 3)) {
            throw new BusinessException("无界画布需要布局结构版本 3");
        }
        boolean unbounded = layout.version() != null && layout.version() >= 3
            && "unbounded".equals(layout.canvas().mode());
        int canvasWidth = layout.canvas().widthMm();
        int canvasHeight = layout.canvas().heightMm();
        Set<String> roomIds = new HashSet<>();
        List<ComputedRoom> rooms = new ArrayList<>();
        for (ManualFloorPlanLayoutRequest.Room room : layout.rooms()) {
            if (!roomIds.add(room.clientId())) {
                throw new BusinessException("空间临时 ID 重复: " + room.clientId());
            }
            rooms.add(computeRoom(room, canvasWidth, canvasHeight, unbounded));
        }
        assertRoomsDoNotOverlap(rooms);
        validateFurniture(layout, canvasWidth, canvasHeight, unbounded);
        return new ComputedLayout(List.copyOf(rooms));
    }

    private static void validateFurniture(ManualFloorPlanLayoutRequest.Layout layout,
                                          int canvasWidth, int canvasHeight, boolean unbounded) {
        if (layout.furniture() == null) return;
        if (layout.furniture().size() > 300) throw new BusinessException("单个布局最多支持 300 件规划家具");
        Map<String, ManualFloorPlanLayoutRequest.Room> roomById = new HashMap<>();
        layout.rooms().forEach(room -> roomById.put(room.clientId(), room));
        Set<String> ids = new HashSet<>();
        for (var item : layout.furniture()) {
            if (item == null || !StringUtils.hasText(item.id()) || !ids.add(item.id())) {
                throw new BusinessException("规划家具 ID 为空或重复");
            }
            if (item.templateId() == null || !FURNITURE_TEMPLATES.contains(item.templateId())) {
                throw new BusinessException("不支持的规划家具图例: " + item.templateId());
            }
            var room = roomById.get(item.roomClientId());
            if (room == null) throw new BusinessException("规划家具缺少归属空间: " + item.id());
            if (item.xMm() == null || item.yMm() == null || item.widthMm() == null
                || item.depthMm() == null || item.rotationDeg() == null
                || item.widthMm() < 100 || item.widthMm() > 10000
                || item.depthMm() < 100 || item.depthMm() > 10000
                || !Set.of(0, 90, 180, 270).contains(item.rotationDeg())) {
                throw new BusinessException("规划家具尺寸、位置或朝向无效: " + item.id());
            }
            if (ROUND_TEMPLATES.contains(item.templateId()) && !item.widthMm().equals(item.depthMm())) {
                throw new BusinessException("圆形规划家具的长宽必须相同: " + item.id());
            }
            if (WING_TEMPLATES.contains(item.templateId())
                && (item.wingWidthMm() == null || item.wingWidthMm() < 100
                    || item.wingWidthMm() >= Math.min(item.widthMm(), item.depthMm()) / 2)) {
                throw new BusinessException("L/U 形规划家具的侧翼桌面宽无效: " + item.id());
            }
            if (item.heightMm() != null) {
                boolean validCabinetHeight = switch (item.templateId()) {
                    case "cabinet-low" -> item.heightMm() >= 100 && item.heightMm() <= 400;
                    case "cabinet-short" -> item.heightMm() > 400 && item.heightMm() <= 1500;
                    case "cabinet-high" -> item.heightMm() >= 1500 && item.heightMm() <= 3000;
                    default -> false;
                };
                if (!validCabinetHeight) {
                    throw new BusinessException("规划家具高度与图例类型不匹配: " + item.id());
                }
            }
            boolean turned = item.rotationDeg() == 90 || item.rotationDeg() == 270;
            int width = turned ? item.depthMm() : item.widthMm();
            int depth = turned ? item.widthMm() : item.depthMm();
            int left = (int) Math.floorDiv(2L * item.xMm() - width, 2);
            int top = (int) Math.floorDiv(2L * item.yMm() - depth, 2);
            int right = (int) Math.floorDiv(2L * item.xMm() + width + 1, 2);
            int bottom = (int) Math.floorDiv(2L * item.yMm() + depth + 1, 2);
            var bounds = new ManualFloorPlanLayoutRequest.Rect(null, left, top, right - left, bottom - top);
            validateRect(bounds, canvasWidth, canvasHeight, unbounded, "规划家具 " + item.id());
            if (coveredArea(bounds, room.parts()) != rectArea(bounds)) {
                throw new BusinessException("规划家具超出所属空间: " + item.id());
            }
        }
    }

    private ComputedRoom computeRoom(ManualFloorPlanLayoutRequest.Room room,
                                     int canvasWidth, int canvasHeight, boolean unbounded) {
        if (room.parts() == null || room.parts().isEmpty()) {
            throw new BusinessException(roomName(room) + "至少需要一个形状块");
        }
        Set<String> partIds = new HashSet<>();
        for (ManualFloorPlanLayoutRequest.Rect part : room.parts()) {
            validateRect(part, canvasWidth, canvasHeight, unbounded, roomName(room));
            if (!StringUtils.hasText(part.id())) {
                throw new BusinessException(roomName(room) + "存在缺少 ID 的形状块");
            }
            if (!partIds.add(part.id().trim())) {
                throw new BusinessException(roomName(room) + "的形状块 ID 重复: " + part.id());
            }
        }

        UnionGrid grid = buildUnionGrid(room.parts());
        assertConnected(grid, roomName(room));
        List<List<Point>> loops = traceBoundaryLoops(grid, roomName(room));
        if (loops.size() != 1) {
            throw new BusinessException(roomName(room) + "形成了内部孔洞，V1 暂不支持带孔空间");
        }
        List<Point> polygon = simplifyOrthogonalRing(loops.get(0));
        if (polygon.size() < 4) {
            throw new BusinessException(roomName(room) + "无法形成有效空间轮廓");
        }

        long areaMm2 = occupiedArea(grid);
        Bounds bounds = boundsOf(room.parts());
        List<ManualFloorPlanLayoutRequest.Rect> zones = room.placementZones() != null
            && !room.placementZones().isEmpty()
            ? room.placementZones() : room.placementZone() != null
                ? List.of(room.placementZone()) : List.of();
        if (zones.isEmpty()) {
            ManualFloorPlanLayoutRequest.Rect placement;
            placement = room.parts().stream()
                .max(Comparator.comparingLong(ManualLayoutGeometryService::rectArea))
                .orElseThrow();
            placement = new ManualFloorPlanLayoutRequest.Rect(
                null, placement.x(), placement.y(), placement.w(), placement.h());
            zones = List.of(placement);
        }
        for (int index = 0; index < zones.size(); index++) {
            var zone = zones.get(index);
            validateRect(zone, canvasWidth, canvasHeight, unbounded, roomName(room) + "布置区 " + (index + 1));
            if (coveredArea(zone, room.parts()) != rectArea(zone)) {
                throw new BusinessException(roomName(room) + "的布置区 " + (index + 1)
                    + " 必须完全位于空间轮廓内");
            }
        }
        ManualFloorPlanLayoutRequest.Rect placement = zones.getFirst();
        Point labelPoint = new Point(
            placement.x() + placement.w() / 2,
            placement.y() + placement.h() / 2);
        return new ComputedRoom(room, List.copyOf(polygon), placement, List.copyOf(zones), areaMm2, bounds, labelPoint);
    }

    private static void validateRect(ManualFloorPlanLayoutRequest.Rect rect,
                                     int canvasWidth, int canvasHeight, boolean unbounded, String owner) {
        if (rect == null || rect.x() == null || rect.y() == null
            || rect.w() == null || rect.h() == null) {
            throw new BusinessException(owner + "存在不完整的矩形数据");
        }
        if (rect.w() <= 0 || rect.h() <= 0) {
            throw new BusinessException(owner + "的矩形坐标或尺寸无效");
        }
        long right = (long) rect.x() + rect.w();
        long bottom = (long) rect.y() + rect.h();
        // 仅保护当前 Integer 毫米坐标和 long 面积计算；不使用起始视野作为绘图边界。
        if (unbounded && (Math.abs((long) rect.x()) > 1_000_000_000L
            || Math.abs((long) rect.y()) > 1_000_000_000L
            || right > 1_000_000_000L || bottom > 1_000_000_000L)) {
            throw new BusinessException(owner + "超出毫米坐标可安全计算的范围");
        }
        if (!unbounded && (rect.x() < 0 || rect.y() < 0 || right > canvasWidth || bottom > canvasHeight)) {
            throw new BusinessException(owner + "超出画布范围");
        }
    }

    private static String roomName(ManualFloorPlanLayoutRequest.Room room) {
        return StringUtils.hasText(room.label()) ? "空间「" + room.label().trim() + "」" : "空间 " + room.clientId();
    }

    private static void assertRoomsDoNotOverlap(List<ComputedRoom> rooms) {
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                if (partsOverlap(rooms.get(i).source().parts(), rooms.get(j).source().parts())) {
                    throw new BusinessException(roomName(rooms.get(i).source()) + "与"
                        + roomName(rooms.get(j).source()) + "存在面积重叠，请调整或执行合并");
                }
            }
        }
    }

    private static boolean partsOverlap(List<ManualFloorPlanLayoutRequest.Rect> first,
                                        List<ManualFloorPlanLayoutRequest.Rect> second) {
        for (ManualFloorPlanLayoutRequest.Rect a : first) {
            for (ManualFloorPlanLayoutRequest.Rect b : second) {
                if (Math.max(a.x(), b.x()) < Math.min(a.x() + a.w(), b.x() + b.w())
                    && Math.max(a.y(), b.y()) < Math.min(a.y() + a.h(), b.y() + b.h())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static UnionGrid buildUnionGrid(List<ManualFloorPlanLayoutRequest.Rect> rects) {
        List<Integer> xs = rects.stream()
            .flatMap(rect -> java.util.stream.Stream.of(rect.x(), rect.x() + rect.w()))
            .distinct().sorted().toList();
        List<Integer> ys = rects.stream()
            .flatMap(rect -> java.util.stream.Stream.of(rect.y(), rect.y() + rect.h()))
            .distinct().sorted().toList();
        Map<Integer, Integer> xi = indexMap(xs);
        Map<Integer, Integer> yi = indexMap(ys);
        boolean[][] occupied = new boolean[xs.size() - 1][ys.size() - 1];
        for (ManualFloorPlanLayoutRequest.Rect rect : rects) {
            for (int x = xi.get(rect.x()); x < xi.get(rect.x() + rect.w()); x++) {
                for (int y = yi.get(rect.y()); y < yi.get(rect.y() + rect.h()); y++) {
                    occupied[x][y] = true;
                }
            }
        }
        return new UnionGrid(xs, ys, occupied);
    }

    private static Map<Integer, Integer> indexMap(List<Integer> values) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            result.put(values.get(i), i);
        }
        return result;
    }

    private static void assertConnected(UnionGrid grid, String roomName) {
        boolean[][] seen = new boolean[grid.occupied().length][grid.occupied()[0].length];
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        int occupiedCount = 0;
        for (int x = 0; x < grid.occupied().length; x++) {
            for (int y = 0; y < grid.occupied()[x].length; y++) {
                if (grid.occupied()[x][y]) {
                    occupiedCount++;
                    if (queue.isEmpty()) {
                        queue.add(new Cell(x, y));
                        seen[x][y] = true;
                    }
                }
            }
        }
        int visited = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Cell cell = queue.removeFirst();
            visited++;
            for (int[] direction : directions) {
                int nx = cell.x() + direction[0];
                int ny = cell.y() + direction[1];
                if (nx >= 0 && nx < grid.occupied().length
                    && ny >= 0 && ny < grid.occupied()[nx].length
                    && grid.occupied()[nx][ny] && !seen[nx][ny]) {
                    seen[nx][ny] = true;
                    queue.addLast(new Cell(nx, ny));
                }
            }
        }
        if (visited != occupiedCount) {
            throw new BusinessException(roomName + "包含不连通区域；形状块必须相交或共享一段边");
        }
    }

    private static List<List<Point>> traceBoundaryLoops(UnionGrid grid, String roomName) {
        List<Edge> edges = new ArrayList<>();
        boolean[][] cells = grid.occupied();
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                if (!cells[x][y]) {
                    continue;
                }
                int x1 = grid.xs().get(x);
                int x2 = grid.xs().get(x + 1);
                int y1 = grid.ys().get(y);
                int y2 = grid.ys().get(y + 1);
                if (y == 0 || !cells[x][y - 1]) {
                    edges.add(new Edge(new Point(x1, y1), new Point(x2, y1)));
                }
                if (x == cells.length - 1 || !cells[x + 1][y]) {
                    edges.add(new Edge(new Point(x2, y1), new Point(x2, y2)));
                }
                if (y == cells[x].length - 1 || !cells[x][y + 1]) {
                    edges.add(new Edge(new Point(x2, y2), new Point(x1, y2)));
                }
                if (x == 0 || !cells[x - 1][y]) {
                    edges.add(new Edge(new Point(x1, y2), new Point(x1, y1)));
                }
            }
        }

        Map<Point, List<Edge>> outgoing = new LinkedHashMap<>();
        for (Edge edge : edges) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        Set<Edge> visited = new LinkedHashSet<>();
        List<List<Point>> loops = new ArrayList<>();
        for (Edge first : edges) {
            if (visited.contains(first)) {
                continue;
            }
            List<Point> loop = new ArrayList<>();
            Edge current = first;
            loop.add(first.from());
            while (true) {
                if (!visited.add(current)) {
                    throw new BusinessException(roomName + "的边界存在自交或分叉");
                }
                Point nextPoint = current.to();
                if (nextPoint.equals(first.from())) {
                    break;
                }
                loop.add(nextPoint);
                List<Edge> candidates = outgoing.getOrDefault(nextPoint, List.of()).stream()
                    .filter(edge -> !visited.contains(edge))
                    .toList();
                if (candidates.size() != 1) {
                    throw new BusinessException(roomName + "的边界无法形成唯一闭合轮廓");
                }
                current = candidates.get(0);
            }
            loops.add(loop);
        }
        return loops;
    }

    private static List<Point> simplifyOrthogonalRing(List<Point> ring) {
        List<Point> result = new ArrayList<>(ring);
        boolean changed;
        do {
            changed = false;
            if (result.size() <= 4) {
                break;
            }
            for (int i = 0; i < result.size(); i++) {
                Point previous = result.get((i - 1 + result.size()) % result.size());
                Point current = result.get(i);
                Point next = result.get((i + 1) % result.size());
                if ((previous.x() == current.x() && current.x() == next.x())
                    || (previous.y() == current.y() && current.y() == next.y())) {
                    result.remove(i);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private static long occupiedArea(UnionGrid grid) {
        long area = 0;
        for (int x = 0; x < grid.occupied().length; x++) {
            for (int y = 0; y < grid.occupied()[x].length; y++) {
                if (grid.occupied()[x][y]) {
                    area += (long) (grid.xs().get(x + 1) - grid.xs().get(x))
                        * (grid.ys().get(y + 1) - grid.ys().get(y));
                }
            }
        }
        return area;
    }

    private static long coveredArea(ManualFloorPlanLayoutRequest.Rect zone,
                                    List<ManualFloorPlanLayoutRequest.Rect> parts) {
        List<ManualFloorPlanLayoutRequest.Rect> intersections = new ArrayList<>();
        for (ManualFloorPlanLayoutRequest.Rect part : parts) {
            int left = Math.max(zone.x(), part.x());
            int top = Math.max(zone.y(), part.y());
            int right = Math.min(zone.x() + zone.w(), part.x() + part.w());
            int bottom = Math.min(zone.y() + zone.h(), part.y() + part.h());
            if (left < right && top < bottom) {
                intersections.add(new ManualFloorPlanLayoutRequest.Rect(
                    null, left, top, right - left, bottom - top));
            }
        }
        return intersections.isEmpty() ? 0 : occupiedArea(buildUnionGrid(intersections));
    }

    private static long rectArea(ManualFloorPlanLayoutRequest.Rect rect) {
        return (long) rect.w() * rect.h();
    }

    private static Bounds boundsOf(List<ManualFloorPlanLayoutRequest.Rect> parts) {
        int minX = parts.stream().mapToInt(ManualFloorPlanLayoutRequest.Rect::x).min().orElseThrow();
        int minY = parts.stream().mapToInt(ManualFloorPlanLayoutRequest.Rect::y).min().orElseThrow();
        int maxX = parts.stream().mapToInt(rect -> rect.x() + rect.w()).max().orElseThrow();
        int maxY = parts.stream().mapToInt(rect -> rect.y() + rect.h()).max().orElseThrow();
        return new Bounds(minX, minY, maxX, maxY);
    }

    /** 完整布局的派生几何。 */
    public record ComputedLayout(List<ComputedRoom> rooms) {
    }

    /** 单个空间的派生几何。 */
    public record ComputedRoom(ManualFloorPlanLayoutRequest.Room source,
                               List<Point> polygon,
                               ManualFloorPlanLayoutRequest.Rect placementZone,
                               List<ManualFloorPlanLayoutRequest.Rect> placementZones,
                               long areaMm2,
                               Bounds bounds,
                               Point labelPoint) {
    }

    /** 整数毫米点。 */
    public record Point(int x, int y) {
    }

    /** 空间外包络。 */
    public record Bounds(int minX, int minY, int maxX, int maxY) {
    }

    private record Cell(int x, int y) {
    }

    private record Edge(Point from, Point to) {
    }

    private record UnionGrid(List<Integer> xs, List<Integer> ys, boolean[][] occupied) {
    }
}
