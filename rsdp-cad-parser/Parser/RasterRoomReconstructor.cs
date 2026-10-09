using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Operation.Union;
using NetTopologySuite.Simplify;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 栅格空间重建（P1-E）：深化图墙体为断口众多的双线碎片时，矢量线网补缝不可靠，
/// 改用位图形态学重建——
///   ① 墙/门线渲染为细线位图（分辨率 RasterMmPerPx）；
///   ② 方形膨胀 RasterWallDilateMm（封闭 ≤2×半径 的门/窗洞断口）；
///   ③ 边框泛洪得室外区，剩余自由区做 4-连通分量 = 房间候选；
///   ④ 贴图边的分量（被区域边界切断，无法确认闭合）丢弃；
///   ⑤ 室内核心与室外自由区竞争回填封口墙带，止于原始墙线；
///   ⑥ 扫描线矩形并集 → Douglas-Peucker 简化 → 毫米多边形。
/// 封洞和标签补种都会改变房间面积，输出统一记 ROOMS_FROM_RASTER_REBUILD
/// 质量门，轮廓与面积需人工复核。
/// </summary>
public static class RasterRoomReconstructor
{
    public class RebuildResult
    {
        public List<AlignedSpace> Spaces { get; } = new();
        public int ComponentCount { get; set; }
        public int DroppedByArea { get; set; }
        public int DroppedByEdge { get; set; }
        public int SeededByLabel { get; set; }
        public double MmPerPx { get; set; }
    }

    public static RebuildResult Rebuild(
        List<List<Coordinate>> wallLines, List<List<Coordinate>> doorLines,
        Envelope region, ParserOptions opt, List<string>? diagnostics = null,
        List<List<Coordinate>>? wallFillRings = null,
        IReadOnlyList<FlatEntity>? roomLabels = null)
    {
        var result = new RebuildResult();
        var allLines = wallLines.Concat(doorLines).ToList();
        if (allLines.Count < 4 && (wallFillRings == null || wallFillRings.Count == 0)) return result;

        // ① 画布：区域外扩 margin，分辨率 RasterMmPerPx（上限 RasterMaxPx）
        double margin = opt.RasterRegionMarginMm;
        var bounds = region.Copy();
        bounds.ExpandBy(margin);
        double spanX = bounds.MaxX - bounds.MinX, spanY = bounds.MaxY - bounds.MinY;
        if (spanX <= 0 || spanY <= 0) return result;
        double mmPerPx = Math.Max(opt.RasterMmPerPx, Math.Max(spanX, spanY) / opt.RasterMaxPx);
        int w = Math.Max(8, (int)Math.Ceiling(spanX / mmPerPx));
        int h = Math.Max(8, (int)Math.Ceiling(spanY / mmPerPx));
        result.MmPerPx = mmPerPx;

        var wall = new byte[w * h]; // 1=细墙线
        int Px(double x) => (int)Math.Round((x - bounds.MinX) / mmPerPx);
        int Py(double y) => (int)Math.Round((bounds.MaxY - y) / mmPerPx); // y 翻转
        void DrawLine(Coordinate a, Coordinate b)
        {
            int x0 = Px(a.X), y0 = Py(a.Y), x1 = Px(b.X), y1 = Py(b.Y);
            int dx = Math.Abs(x1 - x0), dy = Math.Abs(y1 - y0);
            int steps = Math.Max(dx, dy) + 1;
            for (int i = 0; i <= steps; i++)
            {
                int x = x0 + (x1 - x0) * i / steps, y = y0 + (y1 - y0) * i / steps;
                if (x >= 0 && x < w && y >= 0 && y < h) wall[y * w + x] = 1;
            }
        }
        foreach (var pts in allLines)
            for (int i = 1; i < pts.Count; i++) DrawLine(pts[i - 1], pts[i]);

        // 墙身实心环（WIPEOUT/SOLID/HATCH 边界）：扫描线填充进墙掩码（深化图墙身常是 WIPEOUT 填充）
        if (wallFillRings != null)
            foreach (var ring in wallFillRings) FillRing(wall, w, h, ring, bounds, mmPerPx);

        // ② 方形膨胀（可分离两遍滑动窗口），封闭门/窗洞断口
        int radius = Math.Max(1, (int)Math.Round(opt.RasterWallDilateMm / mmPerPx));
        var sealedWalls = Dilate(wall, w, h, radius);

        // 调试：RASTER_DEBUG_PNG=path 时导出位图（黑底，细墙线=深灰，膨胀封口=白，室外=浅灰）
        var dbgPath = Environment.GetEnvironmentVariable("RASTER_DEBUG_PNG");
        if (!string.IsNullOrEmpty(dbgPath))
        {
            var exterior0 = FloodFromBorder(sealedWalls, w, h);
            var img = new byte[w * h];
            for (int i = 0; i < w * h; i++)
                img[i] = sealedWalls[i] == 1 ? (byte)255 : exterior0[i] == 1 ? (byte)60 : (byte)0;
            // 细墙线原位置标深灰区分膨胀体
            for (int i = 0; i < w * h; i++) if (wall[i] == 1) img[i] = 160;
            File.WriteAllBytes(dbgPath, CadPreviewRenderer.PngEncode(img, w, h));
        }

        // ③ 泛洪室外 + 自由区分量
        var exterior = FloodFromBorder(sealedWalls, w, h);
        var labels = new int[w * h];
        var components = new List<List<int>>();
        int nextLabel = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                int idx = y * w + x;
                if (sealedWalls[idx] == 1 || exterior[idx] == 1 || labels[idx] != 0) continue;
                var comp = FloodComponent(sealedWalls, exterior, labels, w, h, x, y, ++nextLabel);
                components.Add(comp);
            }
        result.ComponentCount = components.Count;

        // ④ 过滤：贴边（区域切断）/面积越界
        // 膨胀后的自由区是房间「核心」，小房间会先被墙带吃掉大量面积；
        // MinRoomAreaM2 必须在回胀得到最终轮廓后判定，此处只剔除极小噪点。
        double minPx = Math.Min(0.1, opt.MinRoomAreaM2) * 1e6 / (mmPerPx * mmPerPx);
        double maxPx = opt.MaxRoomAreaM2 * 1e6 / (mmPerPx * mmPerPx);
        var kept = components.Where(comp =>
        {
            if (comp.Count < minPx || comp.Count > maxPx) { result.DroppedByArea++; return false; }
            bool touchEdge = comp.Any(idx => { int x = idx % w, y = idx / w; return x == 0 || y == 0 || x == w - 1 || y == h - 1; });
            if (touchEdge) { result.DroppedByEdge++; return false; }
            return true;
        }).ToList();

        // ⑤ 多源 BFS：室内核心与室外自由区同时向封口带生长。
        // 原始墙线不可穿越；门洞处由两侧距离竞争分界。固定半径回胀在大门洞、
        // 小房间和墙线密集处会留下大片无人认领的膨胀带，导致房间明显缩水。
        var owner = new int[w * h];
        var depth = new int[w * h];
        var queue = new Queue<int>();
        for (int c = 0; c < kept.Count; c++)
            foreach (var idx in kept[c])
            {
                owner[idx] = c + 1;
                queue.Enqueue(idx);
            }
        // 被膨胀完全吞掉的小房间没有自由区核心。只有可见房间名落在封口带、
        // 与已有核心保持距离时才补一个低置信度种子，避免凭空生成无名房间。
        var seededNames = new List<string>();
        if (roomLabels != null)
            foreach (var label in roomLabels.Where(t => t.Kind == FlatKind.Text && t.TextPos != null && t.Text != null)
                         .GroupBy(t => t.Text).Select(g => g.First()))
            {
                int sx = Px(label.TextPos!.X), sy = Py(label.TextPos.Y);
                if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue;
                int selected = -1, bestDist = int.MaxValue;
                for (int dy = -3; dy <= 3; dy++)
                    for (int dx = -3; dx <= 3; dx++)
                    {
                        int x = sx + dx, y = sy + dy;
                        if (x < 0 || x >= w || y < 0 || y >= h) continue;
                        int idx = y * w + x, dist = dx * dx + dy * dy;
                        if (dist >= bestDist || sealedWalls[idx] != 1 || wall[idx] == 1 || owner[idx] != 0) continue;
                        selected = idx;
                        bestDist = dist;
                    }
                if (selected < 0) continue;
                int px = selected % w, py = selected / w;
                bool nearCore = false;
                for (int dy = -6; dy <= 6 && !nearCore; dy++)
                    for (int dx = -6; dx <= 6; dx++)
                    {
                        int x = px + dx, y = py + dy;
                        if (x >= 0 && x < w && y >= 0 && y < h && owner[y * w + x] > 0)
                        { nearCore = true; break; }
                    }
                if (nearCore) continue;
                owner[selected] = kept.Count + seededNames.Count + 1;
                seededNames.Add(label.Text!);
                queue.Enqueue(selected);
            }
        result.SeededByLabel = seededNames.Count;
        for (int i = 0; i < exterior.Length; i++)
            if (exterior[i] == 1) { owner[i] = -1; queue.Enqueue(i); }
        while (queue.Count > 0)
        {
            int idx = queue.Dequeue();
            int x = idx % w, y = idx / w;
            int lbl = owner[idx];
            if (depth[idx] >= radius * 2) continue;
            void Grow(int nidx, bool ok)
            {
                if (!ok || sealedWalls[nidx] != 1 || wall[nidx] == 1 || owner[nidx] != 0) return;
                owner[nidx] = lbl;
                depth[nidx] = depth[idx] + 1;
                queue.Enqueue(nidx);
            }
            Grow(idx - 1, x > 0);
            Grow(idx + 1, x < w - 1);
            Grow(idx - w, y > 0);
            Grow(idx + w, y < h - 1);
        }

        // ⑥ 扫描线矩形并集 → 简化多边形 → 毫米坐标
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        for (int c = 0; c < kept.Count + seededNames.Count; c++)
        {
            var compMask = new byte[w * h];
            if (c < kept.Count)
                foreach (var idx in kept[c]) compMask[idx] = 1;
            for (int i = 0; i < w * h; i++) if (owner[i] == c + 1) compMask[i] = 1;
            var poly = MaskToPolygon(compMask, w, h, bounds, mmPerPx, factory);
            if (poly == null || poly.IsEmpty) { result.DroppedByArea++; continue; }
            double areaM2 = poly.Area / 1e6;
            if (areaM2 < opt.MinRoomAreaM2 || areaM2 > opt.MaxRoomAreaM2) { result.DroppedByArea++; continue; }
            // 填充率守卫：环形/细条状残渣（墙带泄漏环）面积远小于其包围盒，丢弃
            var pbb = poly.EnvelopeInternal;
            double bbArea = (pbb.MaxX - pbb.MinX) * (pbb.MaxY - pbb.MinY) / 1e6;
            if (bbArea > 0 && areaM2 / bbArea < opt.RasterMinFillRatio) { result.DroppedByArea++; continue; }
            result.Spaces.Add(new AlignedSpace
            {
                Polygon = poly,
                Label = c >= kept.Count ? seededNames[c - kept.Count] : null
            });
        }

        diagnostics?.Add($"栅格空间重建（P1-E）：{w}×{h}px（{mmPerPx:F0}mm/px），膨胀 {opt.RasterWallDilateMm:F0}mm，"
                         + $"分量 {result.ComponentCount}、标签补种 {result.SeededByLabel} → 产出 {result.Spaces.Count}（面积过滤 {result.DroppedByArea}，贴边丢弃 {result.DroppedByEdge}）");
        return result;
    }

    /// <summary>扫描线填充闭合环到掩码（奇偶规则）。</summary>
    private static void FillRing(byte[] mask, int w, int h, List<Coordinate> ring, Envelope bounds, double mmPerPx)
    {
        if (ring.Count < 4) return;
        // 像素坐标
        var px = ring.Select(c => (X: (c.X - bounds.MinX) / mmPerPx, Y: (bounds.MaxY - c.Y) / mmPerPx)).ToList();
        double minY = px.Min(p => p.Y), maxY = px.Max(p => p.Y);
        for (int y = Math.Max(0, (int)Math.Floor(minY)); y <= Math.Min(h - 1, (int)Math.Ceiling(maxY)); y++)
        {
            double cy = y + 0.5;
            var xs = new List<double>();
            for (int i = 0, j = px.Count - 1; i < px.Count; j = i++)
            {
                var (xi, yi) = px[i]; var (xj, yj) = px[j];
                if (yi == yj) continue;
                if ((yi <= cy) == (yj <= cy)) continue;
                xs.Add(xi + (cy - yi) / (yj - yi) * (xj - xi));
            }
            xs.Sort();
            for (int k = 0; k + 1 < xs.Count; k += 2)
            {
                int x0 = Math.Max(0, (int)Math.Floor(xs[k])), x1 = Math.Min(w - 1, (int)Math.Ceiling(xs[k + 1]));
                for (int x = x0; x <= x1; x++) mask[y * w + x] = 1;
            }
        }
    }

    /// <summary>方形膨胀：水平/垂直两遍滑动窗口 OR。</summary>
    private static byte[] Dilate(byte[] src, int w, int h, int radius)
    {
        var tmp = new byte[w * h];
        for (int y = 0; y < h; y++)
        {
            int rowBase = y * w;
            // 前缀计数滑动窗口
            int count = 0;
            for (int x = -radius; x < w + radius; x++)
            {
                if (x + radius < w && x + radius >= 0) count += src[rowBase + x + radius];
                if (x - radius - 1 >= 0) count -= src[rowBase + x - radius - 1];
                if (x >= 0 && x < w && count > 0) tmp[rowBase + x] = 1;
            }
        }
        var dst = new byte[w * h];
        for (int x = 0; x < w; x++)
        {
            int count = 0;
            for (int y = -radius; y < h + radius; y++)
            {
                if (y + radius < h && y + radius >= 0) count += tmp[(y + radius) * w + x];
                if (y - radius - 1 >= 0) count -= tmp[(y - radius - 1) * w + x];
                if (y >= 0 && y < h && count > 0) dst[y * w + x] = 1;
            }
        }
        return dst;
    }

    /// <summary>边框泛洪：非墙区域中与外界连通的部分 = 室外。</summary>
    private static byte[] FloodFromBorder(byte[] sealedWalls, int w, int h)
    {
        var exterior = new byte[w * h];
        var stack = new Stack<int>();
        for (int x = 0; x < w; x++) { PushIfFree(0 * w + x); PushIfFree((h - 1) * w + x); }
        for (int y = 0; y < h; y++) { PushIfFree(y * w); PushIfFree(y * w + w - 1); }
        void PushIfFree(int idx)
        {
            if (sealedWalls[idx] == 0 && exterior[idx] == 0) { exterior[idx] = 1; stack.Push(idx); }
        }
        while (stack.Count > 0)
        {
            int idx = stack.Pop();
            int x = idx % w, y = idx / w;
            if (x > 0) PushIfFree(idx - 1);
            if (x < w - 1) PushIfFree(idx + 1);
            if (y > 0) PushIfFree(idx - w);
            if (y < h - 1) PushIfFree(idx + w);
        }
        return exterior;
    }

    /// <summary>4-连通分量提取（自由区、非室外）。</summary>
    private static List<int> FloodComponent(byte[] sealedWalls, byte[] exterior, int[] labels,
        int w, int h, int sx, int sy, int label)
    {
        var comp = new List<int>();
        var stack = new Stack<int>();
        labels[sy * w + sx] = label;
        stack.Push(sy * w + sx);
        while (stack.Count > 0)
        {
            int idx = stack.Pop();
            comp.Add(idx);
            int x = idx % w, y = idx / w;
            TryExpand(idx - 1, x > 0);
            TryExpand(idx + 1, x < w - 1);
            TryExpand(idx - w, y > 0);
            TryExpand(idx + w, y < h - 1);
        }
        void TryExpand(int nidx, bool ok)
        {
            if (!ok || sealedWalls[nidx] == 1 || exterior[nidx] == 1 || labels[nidx] != 0) return;
            labels[nidx] = label;
            stack.Push(nidx);
        }
        return comp;
    }

    /// <summary>掩码 → 毫米多边形：逐行取连续段矩形，NTS 并集后 Douglas-Peucker 简化。</summary>
    private static Polygon? MaskToPolygon(byte[] mask, int w, int h, Envelope bounds, double mmPerPx, GeometryFactory factory)
    {
        var rects = new List<Geometry>();
        for (int y = 0; y < h; y++)
        {
            int x = 0;
            while (x < w)
            {
                if (mask[y * w + x] == 0) { x++; continue; }
                int x0 = x;
                while (x < w && mask[y * w + x] == 1) x++;
                double wx0 = bounds.MinX + x0 * mmPerPx, wx1 = bounds.MinX + x * mmPerPx;
                double wy1 = bounds.MaxY - y * mmPerPx, wy0 = bounds.MaxY - (y + 1) * mmPerPx;
                rects.Add(factory.ToGeometry(new Envelope(wx0, wx1, wy0, wy1)));
            }
        }
        if (rects.Count == 0) return null;
        Geometry union;
        try { union = UnaryUnionOp.Union(rects); }
        catch { return null; }
        var polys = FlattenToPolygons(union);
        if (polys.Count == 0) return null;
        var biggest = polys.OrderByDescending(p => p.Area).First();
        // 简化掉锯齿：容差 1.2px
        var simplified = DouglasPeuckerSimplifier.Simplify(biggest, mmPerPx * 1.2);
        if (simplified is Polygon p && !p.IsEmpty && p.IsValid) return p;
        return biggest.IsValid ? biggest : null;
    }

    private static List<Polygon> FlattenToPolygons(Geometry g)
    {
        var list = new List<Polygon>();
        if (g is Polygon p) list.Add(p);
        else if (g is GeometryCollection gc)
            foreach (var child in gc.Geometries) list.AddRange(FlattenToPolygons(child));
        return list;
    }
}
