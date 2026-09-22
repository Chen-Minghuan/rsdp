using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 语义对齐（P2 核心）：把线网多边形 cells 对齐到图纸的铺贴语义标注。
///
/// ① 标注驱动的凹位归并：无标签、无自身标注的小 cell（&lt;MaxNicheAreaM2），
///    仅当并入某邻居能**改善该邻居的标注一致性**（|并集面积−标注| 减小）时才并入；
///    邻居无标注时退回 P1 规则（贴门线 >300mm 按最长共享边归并）。
///    → 阳台门槛带并入阳台（11.49 ✓）、门洞楔并入卫生间（2.55 ✓），
///    而衣柜凹位/生活阳台凹位因会恶化误差被保留。
/// ② 门槛石归属（标注引导减除）：铺贴层 HATCH 实心（门槛石/挡水条）与房间重叠时，
///    若减除后更贴近标注面积则减除（铺贴面积量到门槛内侧，如生活阳台 3.03−0.19≈2.84 ✓）。
/// ③ 合并空间（composite annotation，如"客餐厅、厨房及过道 47.43㎡"）：
///    成员 = 标签命中合并名各分词的空间，并集面积与标注比对，偏差记 DIM_MISMATCH（几何值为准）。
/// </summary>
public static class SpaceAligner
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    public static List<AlignedSpace> Align(
        List<RoomPolygonizer.RoomCandidate> cells,
        List<FlatEntity> texts,
        WallExtractor.WallExtractionResult walls,
        List<SemanticAnnotation> annotations,
        ParserOptions opt,
        List<QualityIssue> issues,
        List<string>? diagnostics = null)
    {
        var labelRe = new System.Text.RegularExpressions.Regex(opt.RoomLabelPattern);
        var polys = cells.Select(c => c.Polygon).ToList();
        if (polys.Count == 0) return new List<AlignedSpace>();

        // cell → 落在其中的语义标注（同 cell 多处标注时优先合并空间标注，如大空间对应"客餐厅、厨房及过道"）
        var cellAnn = new Dictionary<int, SemanticAnnotation>();
        for (int i = 0; i < polys.Count; i++)
        {
            var hit = annotations.Where(a => polys[i].Covers(Factory.CreatePoint(a.Pos)))
                .OrderBy(a => a.IsComposite ? 0 : 1).FirstOrDefault();
            if (hit != null) cellAnn[i] = hit;
        }

        bool HasRoomLabel(int i) => texts.Any(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
            && labelRe.IsMatch(t.Text) && polys[i].Covers(Factory.CreatePoint(t.TextPos)));

        // 门线缓冲（楔子判定）
        Geometry? doorBuffer = null;
        if (walls.DoorLines.Count > 0)
        {
            try
            {
                var dls = walls.DoorLines
                    .Select(p => { try { return (Geometry)Factory.CreateLineString(p.ToArray()); } catch { return null; } })
                    .Where(g => g != null).ToList();
                if (dls.Count > 0)
                    doorBuffer = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(dls!).Buffer(opt.SnapToleranceMm * 2 + 10);
            }
            catch { doorBuffer = null; }
        }

        // ① 标注驱动的凹位归并
        var gone = new HashSet<int>();
        int merged = 0, rounds = 0;
        bool progress = true;
        while (progress && rounds++ < 30)
        {
            progress = false;
            var adj = BuildAdjacency(polys, gone);
            foreach (var i in Enumerable.Range(0, polys.Count).OrderBy(x => polys[x].Area))
            {
                if (gone.Contains(i) || !adj.ContainsKey(i)) continue;
                double areaM2 = polys[i].Area / 1e6;
                if (areaM2 >= opt.MaxNicheAreaM2) continue;
                if (cellAnn.ContainsKey(i) || HasRoomLabel(i)) continue;

                // 找能改善邻居标注一致性的并入选项（合并空间标注同样驱动：归并使并集逼近目标）
                int bestJ = -1;
                double bestImprove = 0.001;
                foreach (var (j, _) in adj[i])
                {
                    if (!cellAnn.TryGetValue(j, out var ann)) continue;
                    double curErr = Math.Abs(polys[j].Area / 1e6 - ann.AreaM2) / ann.AreaM2;
                    double newErr = Math.Abs((polys[j].Area + polys[i].Area) / 1e6 - ann.AreaM2) / ann.AreaM2;
                    double improve = curErr - newErr;
                    if (improve > bestImprove) { bestImprove = improve; bestJ = j; }
                }
                // 无标注邻居：贴门线楔子按最长共享边归并（仅并入未命名空间，避免污染已命名房间）
                if (bestJ < 0 && doorBuffer != null && adj[i].Count > 0)
                {
                    double doorOverlap;
                    try { doorOverlap = polys[i].Boundary.Intersection(doorBuffer).Length; }
                    catch { doorOverlap = 0; }
                    if (doorOverlap >= 300)
                    {
                        var (j, len) = adj[i].OrderByDescending(e => e.Len).First();
                        if (len >= 100 && !cellAnn.ContainsKey(j) && !HasRoomLabel(j)) bestJ = j;
                    }
                }
                if (bestJ < 0) continue;

                try
                {
                    var u = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(new Geometry[] { polys[bestJ], polys[i] });
                    var big = WallExtractor.Flatten(u).OrderByDescending(p => p.Area).FirstOrDefault();
                    if (big == null) continue;
                    polys[bestJ] = big;
                    gone.Add(i);
                    merged++;
                    progress = true;
                }
                catch { /* 并集失败跳过 */ }
            }
        }
        diagnostics?.Add($"语义归并：{merged} 个凹位/楔子并入邻居");

        var alive = Enumerable.Range(0, polys.Count).Where(i => !gone.Contains(i)).ToList();

        // ② 门槛石归属（标注引导减除）
        int sillCut = 0;
        if (walls.SillSolids.Count > 0)
        {
            Geometry? sillUnion = null;
            try { sillUnion = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(walls.SillSolids); } catch { }
            if (sillUnion != null)
            {
                foreach (var i in alive.ToList())
                {
                    if (!cellAnn.TryGetValue(i, out var ann) || ann.IsComposite) continue;
                    if (!polys[i].EnvelopeInternal.Intersects(sillUnion.EnvelopeInternal)) continue;
                    try
                    {
                        double overlap = polys[i].Intersection(sillUnion).Area / 1e6;
                        diagnostics?.Add($"  门槛石检查 cell#{i}（{ann.Name} {ann.AreaM2}㎡）：重叠 {overlap:F3}㎡");
                        if (overlap < 0.01) continue;
                        double curErr = Math.Abs(polys[i].Area / 1e6 - ann.AreaM2);
                        var d = polys[i].Difference(sillUnion);
                        var pieces = WallExtractor.Flatten(d).ToList();
                        if (pieces.Count == 0) continue;
                        double newArea = pieces.Sum(p => p.Area) / 1e6;
                        if (Math.Abs(newArea - ann.AreaM2) < curErr)
                        {
                            // 减除可能切块：最大块为主体，其余 ≥1㎡ 的块保留为独立空间
                            var main = pieces.OrderByDescending(p => p.Area).First();
                            polys[i] = main;
                            foreach (var extra in pieces.Where(p => p != main && p.Area / 1e6 >= 1))
                            {
                                polys.Add(extra);
                                alive.Add(polys.Count - 1);
                            }
                            sillCut++;
                        }
                    }
                    catch { /* 拓扑异常忽略 */ }
                }
            }
        }
        diagnostics?.Add($"门槛石减除：{sillCut} 处（标注引导）");

        // ③ 合并空间分组
        var spaces = alive.Select(i => new AlignedSpace
        {
            Polygon = polys[i],
            Annotation = cellAnn.GetValueOrDefault(i)
        }).ToList();
        foreach (var ann in annotations.Where(a => a.IsComposite && a.Name.Length > 0))
        {
            var tokens = labelRe.Matches(ann.Name).Select(m => m.Value).Distinct().ToList();
            var members = spaces.Where(s =>
            {
                var names = MemberNames(s, texts, labelRe);
                return tokens.Any(t => names.Any(nm => nm.Contains(t)));
            }).ToList();
            if (members.Count == 0) continue;
            double groupArea = members.Sum(m => m.Polygon.Area) / 1e6;
            foreach (var m in members) m.SpaceGroup = ann.Name;
            var primary = members.OrderBy(m => m.Polygon.Distance(Factory.CreatePoint(ann.Pos))).First();
            primary.Annotation = ann; // 合并标注挂到最近成员，group 面积参与校验
            primary.GroupAreaM2 = Math.Round(groupArea, 2);
            double err = Math.Abs(groupArea - ann.AreaM2) / ann.AreaM2;
            diagnostics?.Add($"合并空间「{ann.Name}」：成员 {members.Count}，并集 {groupArea:F2}㎡ vs 标注 {ann.AreaM2}㎡（{err:P1}）");
            if (err > 0.03)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "DIM_MISMATCH",
                    Message = $"{ann.Name}：几何并集 {groupArea:F2}㎡ 与铺贴标注 {ann.AreaM2}㎡ 偏差 {err:P1}（几何值为准，标注供人工复核）"
                });
        }
        return spaces;
    }

    private static List<string> MemberNames(AlignedSpace s, List<FlatEntity> texts, System.Text.RegularExpressions.Regex labelRe)
        => texts.Where(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
                            && labelRe.IsMatch(t.Text) && s.Polygon.Covers(Factory.CreatePoint(t.TextPos)))
                .Select(t => t.Text!).Distinct().ToList();

    private static Dictionary<int, List<(int Other, double Len)>> BuildAdjacency(List<Polygon> polys, HashSet<int> gone)
    {
        var adj = new Dictionary<int, List<(int, double)>>();
        var tree = new NetTopologySuite.Index.Strtree.STRtree<int>();
        for (int i = 0; i < polys.Count; i++) if (!gone.Contains(i)) tree.Insert(polys[i].EnvelopeInternal, i);
        tree.Build();
        for (int i = 0; i < polys.Count; i++)
        {
            if (gone.Contains(i)) continue;
            foreach (var j in tree.Query(polys[i].EnvelopeInternal))
            {
                if (j <= i || gone.Contains(j)) continue;
                double len;
                try { len = polys[i].Boundary.Intersection(polys[j].Boundary).Length; } catch { continue; }
                if (len < 100) continue;
                if (!adj.ContainsKey(i)) adj[i] = new();
                if (!adj.ContainsKey(j)) adj[j] = new();
                adj[i].Add((j, len));
                adj[j].Add((i, len));
            }
        }
        return adj;
    }
}

/// <summary>语义对齐后的空间。</summary>
public class AlignedSpace
{
    public required Polygon Polygon { get; set; }
    /// <summary>关联的语义标注（合并空间标注挂在主成员上）。</summary>
    public SemanticAnnotation? Annotation { get; set; }
    /// <summary>合并空间名（成员空间共享）。</summary>
    public string? SpaceGroup { get; set; }
    /// <summary>合并空间并集面积（仅主成员）。</summary>
    public double? GroupAreaM2 { get; set; }
    public string? Label { get; set; }
    public List<string> MemberLabels { get; set; } = new();
}
