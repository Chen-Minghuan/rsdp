using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 解析流水线编排：载入 → 实体抽取 → 子图聚类（混排计数）→ 标签定位目标副本 →
/// HATCH 墙体提取 → 空间多边形化 → 语义对齐（标注驱动归并/门槛归属/合并空间）→
/// 标签关联 → DIMENSION 交叉验证 → CadParseResult。
/// </summary>
public static class CadParsePipeline
{
    public class DetailedResult
    {
        public required CadParseResult Result { get; set; }
        public List<AreaAnnotation> AreaAnnotations { get; set; } = new();
        public List<string> Diagnostics { get; set; } = new();
    }

    public static CadParseResult Parse(byte[] bytes, string? fileName, ParserOptions? options = null)
        => ParseDetailed(bytes, fileName, options).Result;

    public static DetailedResult ParseDetailed(byte[] bytes, string? fileName, ParserOptions? options = null)
    {
        var opt = options ?? new ParserOptions();
        var issues = new List<QualityIssue>();
        var diag = new List<string>();
        var result = new CadParseResult { QualityIssues = issues };
        var annotations = new List<AreaAnnotation>();

        try
        {
            // ① 载入与单位规范化
            var doc = CadDocumentLoader.Load(bytes, fileName);
            double scale = CadDocumentLoader.ScaleToMm(doc);
            diag.Add($"DWG/DXF 版本={doc.Header.VersionString}，INSUNITS={doc.Header.InsUnits}，毫米系数={scale}");
            if (doc.Header.InsUnits == ACadSharp.Types.Units.UnitsType.Unitless)
                issues.Add(new QualityIssue { Level = "warn", Code = "UNIT_ASSUMED", Message = "文件无单位标记（INSUNITS=Unitless），按毫米处理" });

            // ② 实体抽取（P1 不展开 INSERT）
            var entities = EntityExtractor.Extract(doc, scale, diag);
            diag.Add($"模型空间实体：{entities.Count} 个");

            // ③ 子图聚类拆分（实体密度聚类用于混排计数与兜底；目标选择走标签定位）
            var clusters = SubDrawingClusterer.Split(entities, opt);
            foreach (var c in clusters)
                diag.Add($"子图 #{c.Index}：实体 {c.Entities.Count}，跨度 {c.SpanXm / 1000:F1}m×{c.SpanYm / 1000:F1}m，房间标签命中 {c.LabelHits}");
            if (clusters.Count > 1)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "SUB_DRAWINGS_SKIPPED",
                    Message = $"检测到 {clusters.Count} 个子图混排，已按房间标签定位目标户型，跳过 {clusters.Count - 1} 个非目标子图"
                });

            annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));
            diag.Add($"面积标注（去重后）：{annotations.Count} 处 → [{string.Join(", ", annotations.Select(a => a.AreaM2.ToString("F2")))}]");

            // ③b 标签定位目标户型副本 → 目标区域
            var target = TargetSelector.Select(entities, annotations, opt, diag);
            var targetEntities = entities.Where(e => e.BBox.Intersects(target.Region)).ToList();
            diag.Add($"目标区域内实体：{targetEntities.Count} 个");
            if (!target.FromLabels)
                issues.Add(new QualityIssue { Level = "warn", Code = "NO_ROOM_LABELS", Message = "全图未命中房间名标签，已取最大实体簇作为目标" });

            result.DrawingBounds = ToBounds(target.Region); // 兜底值：无房间产出时使用（见 ⑦ 末尾的几何外包络逻辑）

            // ④ HATCH 驱动墙体提取（含门槛石实心、墙/门线收集）
            var walls = WallExtractor.Extract(targetEntities, opt, diag);

            // ⑤ 空间多边形化（原始 cells）
            var texts = targetEntities.Where(e => e.Kind == FlatKind.Text).ToList();
            var dims = targetEntities.Where(e => e.Kind == FlatKind.Dimension).ToList();
            diag.Add($"目标区域文字 {texts.Count}、DIMENSION {dims.Count}");
            var cells = RoomPolygonizer.BuildRooms(walls, texts, opt, issues, diag);

            // ⑥ 语义对齐 + 标签关联（P2）
            var semanticAnns = AnnotationExtractor.PairWithNames(annotations, entities, opt.RoomLabelPattern);
            foreach (var sa in semanticAnns)
                diag.Add($"语义标注：「{sa.Name}」{sa.AreaM2:F2}㎡{(sa.IsComposite ? "（合并空间）" : "")}");
            var spaces = SpaceAligner.Align(cells, texts, walls, semanticAnns, opt, issues, diag);
            var labelRe = new System.Text.RegularExpressions.Regex(opt.RoomLabelPattern);
            foreach (var s in spaces)
                RoomLabelMatcher.AssignLabel(s, texts, labelRe);

            // ⑦ 尺寸交叉验证 + 输出装配
            var gf = NtsGeometryServices.Instance.CreateGeometryFactory();
            var outputEnvelope = new Envelope(); // 产出空间（房间+未命名）多边形并集外包络
            foreach (var s in spaces.OrderByDescending(x => x.Polygon.Area))
            {
                double areaM2 = Math.Round(s.Polygon.Area / 1e6, 2);
                if (areaM2 < opt.MinRoomAreaM2 || areaM2 > opt.MaxRoomAreaM2) continue;
                outputEnvelope.ExpandToInclude(s.Polygon.EnvelopeInternal);
                var bb = s.Polygon.EnvelopeInternal;
                double dx = bb.MaxX - bb.MinX, dy = bb.MaxY - bb.MinY;
                double widthMm = Math.Round(Math.Max(dx, dy), 0), depthMm = Math.Round(Math.Min(dx, dy), 0);

                if (s.Label == null)
                {
                    // 无标签空间 → 待命名清单（带相邻标签 hint）
                    result.UnnamedRegions.Add(new UnnamedRegionDto
                    {
                        Polygon = ToPolygon(s.Polygon),
                        BBox = ToBounds(bb),
                        WidthMm = widthMm,
                        DepthMm = depthMm,
                        AreaM2 = areaM2,
                        LabelPoint = ToPoint(s.Polygon.InteriorPoint.Coordinate),
                        Hint = RoomLabelMatcher.NearestLabelHint(s, texts, labelRe)
                    });
                    continue;
                }

                // 交叉验证：DIMENSION 实测 + 铺贴面积标注
                var checks = new List<(string Annotated, bool Consistent)>();
                var dimCheck = DimensionExtractor.CheckDimensions(
                    s.Polygon, widthMm, depthMm, dims, opt, issues, s.Label);
                if (dimCheck != null) checks.Add(dimCheck.Value);
                if (s.Annotation != null)
                {
                    double geom = s.Annotation.IsComposite && s.GroupAreaM2.HasValue ? s.GroupAreaM2.Value : areaM2;
                    double err = Math.Abs(geom - s.Annotation.AreaM2) / s.Annotation.AreaM2;
                    checks.Add(($"面积: {s.Annotation.AreaM2:F2}㎡", err <= 0.03));
                    if (err > 0.03 && !s.Annotation.IsComposite)
                        issues.Add(new QualityIssue
                        {
                            Level = "warn",
                            Code = "DIM_MISMATCH",
                            Message = $"{s.Label}：几何 {geom:F2}㎡ 与铺贴标注 {s.Annotation.AreaM2:F2}㎡ 偏差 {err:P1}（几何值为准）"
                        });
                }

                result.Rooms.Add(new RoomDto
                {
                    Label = s.Label,
                    RoomType = RoomLabelMatcher.MapRoomType(s.Label),
                    MemberLabels = s.MemberLabels.Count > 0 ? s.MemberLabels : null,
                    SpaceGroup = s.SpaceGroup,
                    Polygon = ToPolygon(s.Polygon),
                    BBox = ToBounds(bb),
                    WidthMm = widthMm,
                    DepthMm = depthMm,
                    AreaM2 = areaM2,
                    LabelPoint = ToPoint(s.Polygon.InteriorPoint.Coordinate),
                    DimensionSource = "cad_geometry",
                    DimensionCheck = checks.Count > 0
                        ? new DimensionCheckDto
                        {
                            Annotated = string.Join("；", checks.Select(c => c.Annotated)),
                            Consistent = checks.All(c => c.Consistent)
                        }
                        : null,
                    Confidence = checks.Count > 0 && checks.All(c => c.Consistent) ? "high" : "mid"
                });
            }
            diag.Add($"输出：命名房间 {result.Rooms.Count}，未命名空间 {result.UnnamedRegions.Count}");

            // drawingBounds = 产出空间多边形并集外包络 + OutputBoundsMarginMm 边距
            // （前端以此为图片叠加归一化基准，必须与公寓本体范围对齐，而非标签区域边距）；
            // 无产出空间时回退 target.Region（上面已设兜底值）。
            if (!outputEnvelope.IsNull)
            {
                outputEnvelope.ExpandBy(opt.OutputBoundsMarginMm);
                result.DrawingBounds = ToBounds(outputEnvelope);
                diag.Add($"drawingBounds（几何外包络+{opt.OutputBoundsMarginMm}mm）：({outputEnvelope.MinX:F0},{outputEnvelope.MinY:F0})~({outputEnvelope.MaxX:F0},{outputEnvelope.MaxY:F0})");
            }
            else
            {
                issues.Add(new QualityIssue { Level = "warn", Code = "BOUNDS_FALLBACK", Message = "无产出空间，drawingBounds 回退为目标标签区域" });
            }

            // 规范预览与房间多边形共用 drawingBounds，前端无需再做人工平移/缩放配准。
            try
            {
                result.Preview = CadPreviewRenderer.Render(targetEntities, result.DrawingBounds!);
                diag.Add($"规范预览：{result.Preview.Width}×{result.Preview.Height}px");
            }
            catch (Exception renderEx)
            {
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "PREVIEW_RENDER_FAILED",
                    Message = $"CAD 几何识别成功，但规范预览生成失败：{renderEx.Message}"
                });
                diag.Add("预览渲染异常：" + renderEx);
            }

            // 无几何对应的语义标注进质量门（如"盥洗间 2.46㎡"无墙围合）
            foreach (var sa in semanticAnns.Where(sa => !sa.IsComposite
                         && !spaces.Any(s => s.Annotation == sa)))
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "ANNOTATION_NO_GEOMETRY",
                    Message = $"铺贴标注「{sa.Name} {sa.AreaM2:F2}㎡」无对应墙体围合空间，需人工指定"
                });

            result.Success = issues.All(i => i.Level != "block");
            if (!result.Success)
                result.ErrorCode = issues.First(i => i.Level == "block").Code;
        }
        catch (Exception ex)
        {
            result.Success = false;
            result.ErrorCode = "FILE_CORRUPT";
            result.ErrorMessage = $"CAD 文件解析失败：{ex.Message}";
            diag.Add("异常：" + ex);
        }

        return new DetailedResult { Result = result, AreaAnnotations = annotations, Diagnostics = diag };
    }

    private static BoundsDto ToBounds(Envelope e) => new()
    {
        MinX = Math.Round(e.MinX, 1),
        MinY = Math.Round(e.MinY, 1),
        MaxX = Math.Round(e.MaxX, 1),
        MaxY = Math.Round(e.MaxY, 1)
    };

    private static PointDto ToPoint(Coordinate p) => new()
    {
        X = Math.Round(p.X, 1),
        Y = Math.Round(p.Y, 1)
    };

    private static double[][] ToPolygon(Polygon p)
    {
        return p.ExteriorRing.Coordinates
            .Select(c => new[] { Math.Round(c.X, 1), Math.Round(c.Y, 1) })
            .ToArray();
    }
}
