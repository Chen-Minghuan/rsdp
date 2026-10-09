using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 解析流水线编排：载入 → 单位判定 → XREF 检测 → 实体抽取 + INSERT 块递归展开 →
/// 子图聚类（混排计数）→ 标签定位目标副本（含候选清单）→ HATCH 墙体提取 → 空间多边形化 →
/// 语义对齐 → 标签关联 → DIMENSION 交叉验证 → INSERT 家具识别 → 能力/状态装配 → CadParseResult。
///
/// P0 契约：parseStatus（success=false 当且仅当 FAILED）、capabilities、unitResolution、
/// targetScene / targetSceneCandidates、furniture[]、unsupportedEntitySummary、资源守卫。
/// </summary>
public static class CadParsePipeline
{
    public class DetailedResult
    {
        public required CadParseResult Result { get; set; }
        public List<AreaAnnotation> AreaAnnotations { get; set; } = new();
        public List<string> Diagnostics { get; set; } = new();
    }

    public static CadParseResult Parse(byte[] bytes, string? fileName, ParserOptions? options = null,
        string? targetId = null, CancellationToken ct = default)
        => ParseDetailed(bytes, fileName, options, targetId, ct).Result;

    public static DetailedResult ParseDetailed(byte[] bytes, string? fileName, ParserOptions? options = null,
        string? targetId = null, CancellationToken ct = default)
    {
        var opt = options ?? new ParserOptions();
        var issues = new List<QualityIssue>();
        var diag = new List<string>();
        var result = new CadParseResult
        {
            QualityIssues = issues,
            SceneVersion = ParserInfo.SceneVersion,
            ParserVersion = ParserInfo.ParserVersion
        };
        var annotations = new List<AreaAnnotation>();
        var unsupported = new Dictionary<string, int>();

        // Stage ⑦ Scene Assembly 输入：输入指纹 + 空间分析墙体/尺寸产出（早期失败路径为 null）
        var inputHash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant();
        WallExtractor.WallExtractionResult? sceneWalls = null;
        List<FlatEntity>? sceneDims = null;

        // 统一收口：所有返回路径都经 Scene Assembly 装配 parsedScene（纯新增字段，不动顶层语义）
        DetailedResult Finish(CadParseResult r)
        {
            r.ParsedScene = SceneAssembly.CadSceneAssembler.Assemble(r, sceneWalls, sceneDims, inputHash, opt);
            return new DetailedResult { Result = r, AreaAnnotations = annotations, Diagnostics = diag };
        }

        // 资源守卫：解析超时（与调用方取消令牌联动）
        using var timeoutCts = opt.MaxParseTimeSeconds > 0
            ? CancellationTokenSource.CreateLinkedTokenSource(ct)
            : null;
        if (timeoutCts != null)
        {
            timeoutCts.CancelAfter(TimeSpan.FromSeconds(opt.MaxParseTimeSeconds));
            ct = timeoutCts.Token;
        }

        try
        {
            // ① 载入与单位规范化（UnitResolver：归一化毫米 ≠ 已确认物理毫米）
            ct.ThrowIfCancellationRequested();
            var doc = CadDocumentLoader.Load(bytes, fileName);
            double scale = CadDocumentLoader.ScaleToMm(doc);
            result.UnitResolution = UnitResolver.Resolve(doc, scale);
            diag.Add($"DWG/DXF 版本={doc.Header.VersionString}，INSUNITS={doc.Header.InsUnits}，毫米系数={scale}");
            if (result.UnitResolution.ScaleAssumed)
                issues.Add(new QualityIssue { Level = "warn", Code = "UNIT_ASSUMED", Message = "文件无单位标记（INSUNITS=Unitless），按毫米处理，单位待确认" });

            // ①b XREF 检测（P0 仅检测，不解析外部文件）
            issues.AddRange(XrefDetector.Detect(doc));

            // ② 实体抽取（不支持类型计入 unsupportedEntitySummary）+ INSERT 块递归展开
            ct.ThrowIfCancellationRequested();
            var ext = EntityExtractor.ExtractDetailed(doc.ModelSpace.Entities, scale, ct, diag);
            foreach (var kv in ext.Unsupported) unsupported[kv.Key] = unsupported.GetValueOrDefault(kv.Key) + kv.Value;
            var blocks = BlockResolver.Resolve(doc, scale, opt, ct, diag, unsupported);
            issues.AddRange(blocks.Issues);
            // 房间/墙体/标签/标注算法（P1/P2 调参基线）继续只消费模型空间直接实体——
            // 块展开几何仅用于家具识别与规范预览，避免改变已调参的空间多边形化行为（P1 门窗对象化时再接入）。
            // 例外：全部几何都在块内（无直接实体）时回退使用展开实体做空间分析，优于直接判 FAILED。
            var allEntities = ext.Entities.Concat(blocks.ExpandedEntities).ToList();
            var entities = ext.Entities.Count > 0 ? ext.Entities : allEntities;
            diag.Add($"模型空间实体：直接 {entities.Count} 个（另块展开 {blocks.ExpandedEntities.Count} 个供家具/预览）");
            if (allEntities.Count == 0)
            {
                result.Success = false;
                result.ParseStatus = "FAILED";
                result.ErrorCode = "NO_GEOMETRY";
                result.ErrorMessage = "文件中没有任何可恢复的实体数据";
                return Finish(result);
            }
            if (allEntities.Count > opt.MaxTotalPrimitives)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "ENTITY_LIMIT",
                    Message = $"图元总数 {allEntities.Count} 超过上限 {opt.MaxTotalPrimitives}，结果可能不完整"
                });

            // ②b 平面性检查：Z 范围超容差 → warn，不静默压平
            if (ext.MinZ != double.MaxValue && ext.MaxZ - ext.MinZ > opt.PlanarityToleranceMm)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "NON_PLANAR_GEOMETRY",
                    Message = $"模型空间 Z 范围 {ext.MinZ:F0}~{ext.MaxZ:F0}mm 超过容差 {opt.PlanarityToleranceMm}mm，存在多高程/三维信息，已按 XY 投影处理"
                });

            // ③ 子图聚类拆分（实体密度聚类用于混排计数与兜底；目标选择走标签定位）
            ct.ThrowIfCancellationRequested();
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

            // ③b 标签定位目标户型副本 → 目标场景 + 候选清单
            // 房间名常位于 INSERT 中；可见性过滤后才可用于定位目标方案。
            // 只合并块中文字，避免家具细线改变无标签图纸的实体聚类兜底。
            var selectionEntities = entities.Concat(blocks.ExpandedEntities.Where(e => e.Kind == FlatKind.Text)).ToList();
            var selection = TargetSelector.SelectDetailed(selectionEntities, annotations, opt, diag);
            var selectionSource = "AUTO";
            var target = selection.Best;
            if (!string.IsNullOrWhiteSpace(targetId))
            {
                var manual = selection.Candidates.FirstOrDefault(c => CandidateId(c) == targetId);
                if (manual == null)
                {
                    result.Success = false;
                    result.ParseStatus = "FAILED";
                    result.ErrorCode = "TARGET_NOT_FOUND";
                    result.ErrorMessage = $"指定的 targetId「{targetId}」不在本次解析的 {selection.Candidates.Count} 个目标场景候选中";
                    return Finish(result);
                }
                target = manual;
                selectionSource = "MANUAL";
                diag.Add($"手动指定目标场景 {targetId}");
            }
            var targetEntities = entities.Where(e => e.BBox.Intersects(target.Region)).ToList();
            diag.Add($"目标区域内实体：{targetEntities.Count} 个");
            if (!target.FromLabels)
                issues.Add(new QualityIssue { Level = "warn", Code = "NO_ROOM_LABELS", Message = "全图未命中房间名标签，已取最大实体簇作为目标" });

            // targetScene / targetSceneCandidates 输出装配
            result.TargetScene = new TargetSceneDto
            {
                TargetId = CandidateId(target),
                SourceSpace = "MODEL_SPACE",
                Bounds = ToBounds(target.Region),
                SelectionSource = selectionSource,
                SelectionConfidence = target.FromLabels ? (target.AnnotationCount > 0 ? "high" : "mid") : "low",
                Reasons = target.Reasons
            };
            result.TargetSceneCandidates = selection.Candidates.Select((c, i) => new TargetSceneCandidateDto
            {
                TargetId = CandidateId(c),
                SourceSpace = "MODEL_SPACE",
                Bounds = ToBounds(c.Region),
                Score = c.Score,
                Reasons = c.Reasons,
                Recommended = i == 0
            }).ToList();

            result.DrawingBounds = ToBounds(target.Region); // 兜底值：无房间产出时使用（见 ⑦ 末尾的几何外包络逻辑）

            // ④~⑥ 空间分析：语义标注配对（两条路径共用）→ 主路径（模型空间直接实体）→
            // 无产出时按块角色回退块展开建筑几何（P1 Scene-aware Block Fallback）
            ct.ThrowIfCancellationRequested();
            var semanticAnns = AnnotationExtractor.PairWithNames(annotations, entities, opt.RoomLabelPattern);
            foreach (var sa in semanticAnns)
                diag.Add($"语义标注：「{sa.Name}」{sa.AreaM2:F2}㎡{(sa.IsComposite ? "（合并空间）" : "")}");
            var labelRe = new System.Text.RegularExpressions.Regex(opt.RoomLabelPattern);

            var analysis = AnalyzeSpaces(targetEntities, opt, semanticAnns, labelRe, "", issues, diag);
            // 回退触发：主路径无任何"可输出"空间（含被 MinRoomAreaM2 面积下限过滤掉的碎片）——
            // 不能只看 Spaces.Count，否则碎片区会挡住回退（深化图直接实体常只剩杂线围出的小碎片）
            var visibleLabels = selectionEntities.Where(e => e.Kind == FlatKind.Text && e.Text != null
                    && e.TextPos != null && target.Region.Contains(e.TextPos) && labelRe.IsMatch(e.Text))
                .Select(e => e.Text!).Distinct().ToHashSet();
            bool HasOutput(SpaceAnalysis a)
            {
                var output = a.Spaces.Where(sp =>
                {
                    double area = sp.Polygon.Area / 1e6;
                    return area >= opt.MinRoomAreaM2 && area <= opt.MaxRoomAreaM2;
                }).ToList();
                // 多个可见房间名却只有无标签小碎片时，不能让碎片挡住块几何/栅格回退。
                if (output.Count == 0) return false;
                var assignedLabels = output.SelectMany(sp =>
                    (sp.Label == null ? Array.Empty<string>() : new[] { sp.Label })
                    .Concat(sp.MemberLabels)).Distinct().Count();
                return visibleLabels.Count < 3 || (assignedLabels > 0
                    && (visibleLabels.Count < 6 || assignedLabels >= Math.Ceiling(visibleLabels.Count * 0.6)));
            }
            SpaceAnalysis? fbAnalysis = null;
            List<FlatEntity>? fbEntities = null;
            ParserOptions? fbOpt = null;
            if (!HasOutput(analysis) && opt.BlockGeometryFallbackEnabled && blocks.ExpandedEntities.Count > 0)
            {
                ct.ThrowIfCancellationRequested();
                fbOpt = opt.CloneForBlockFallback();
                fbEntities = targetEntities
                    .Concat(BuildArchitectureFallbackEntities(blocks.Roots, target.Region, opt, diag))
                    .ToList();
                if (fbEntities.Count > 0)
                {
                    var fb = AnalyzeSpaces(fbEntities, fbOpt, semanticAnns, labelRe, "【块展开回退】", issues, diag);
                    if (fb.Spaces.Count > 0 && HasOutput(fb))
                    {
                        analysis = fb;
                        issues.Add(new QualityIssue
                        {
                            Level = "warn",
                            Code = "ROOMS_FROM_BLOCK_FALLBACK",
                            Message = "模型空间直接实体未围合出空间，已回退用块展开建筑几何分析（墙体/面积需人工复核）"
                        });
                    }
                    else
                    {
                        diag.Add("块展开回退仍无空间产出");
                        fbAnalysis = fb; // 留作栅格重建的墙线/文字来源
                    }
                }
            }

            // ④c P1-E 栅格空间重建（最终回退）：深化图断线墙体矢量补缝不可靠时，
            // 位图膨胀封洞 → 泛洪分房 → 轮廓矢量化。文字/标签沿用回退路径集合
            if (!HasOutput(analysis) && opt.RasterRebuildEnabled)
            {
                ct.ThrowIfCancellationRequested();
                // 栅格墙线来源：直接实体 ∪ 块展开宽集（含 0 层/淡显层建筑线）。
                // 注意实体收集不能再用 target.Region 裁剪：标签区域常偏出建筑主体
                // （601 实测 12m 标签区只盖住公寓一半），按 2× TargetRegionMarginMm 外扩收集
                var rasterFilterRegion = target.Region.Copy();
                rasterFilterRegion.ExpandBy(opt.TargetRegionMarginMm * 2);
                var rasterEntities = ext.Entities.Where(e => e.BBox.Intersects(rasterFilterRegion))
                    .Concat(BuildArchitectureFallbackEntities(blocks.Roots, rasterFilterRegion, opt, diag, relaxedForRaster: true))
                    .ToList();
                var raster = TryRasterRebuild(
                    fbEntities ?? targetEntities, fbOpt ?? opt,
                    fbAnalysis ?? analysis, target.Region, opt, labelRe, diag,
                    rasterEntities.Count > 0 ? rasterEntities : null, allEntities);
                if (raster != null)
                {
                    analysis = raster;
                    issues.Add(new QualityIssue
                    {
                        Level = "warn",
                        Code = "ROOMS_FROM_RASTER_REBUILD",
                        Message = "墙体断线过多，已用栅格形态学估计空间；轮廓与面积需逐房间复核"
                    });
                    if (raster.LabelPartitioned)
                        issues.Add(new QualityIssue
                        {
                            Level = "warn",
                            Code = "ROOMS_FROM_LABEL_PARTITION",
                            Message = "部分连通区域依据可见房间名位置临时分区；分界线为估计线，需按参考图逐房间校正"
                        });
                    if (raster.LabelSnapped)
                        issues.Add(new QualityIssue
                        {
                            Level = "warn",
                            Code = "ROOM_LABEL_SNAPPED",
                            Message = "个别房间名位于封口墙带内，已就近指派给无名区域；名称需人工复核"
                        });
                }
            }
            if (analysis.LocalLabelRecovered > 0)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "ROOM_LABELS_LOCALLY_RECOVERED",
                    Message = $"有 {analysis.LocalLabelRecovered} 个房间使用局部墙体/门洞尺寸链恢复轮廓，面积与分界仍需人工复核"
                });
            var walls = analysis.Walls;
            var texts = analysis.Texts;
            var dims = analysis.Dims;
            var spaces = analysis.Spaces;
            // 采用路径确定后，并入该路径的墙体内侧面吸附质量门（废弃路径的提示已随候选对象丢弃）
            issues.AddRange(analysis.SnapIssues);
            sceneWalls = walls;
            sceneDims = dims;
            var resolvedLabels = spaces.SelectMany(sp =>
                (sp.Label == null ? Array.Empty<string>() : new[] { sp.Label })
                .Concat(sp.MemberLabels)).ToHashSet();
            var missingLabels = visibleLabels.Except(resolvedLabels).OrderBy(name => name).ToList();
            if (missingLabels.Count > 0)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "ROOM_LABELS_WITHOUT_GEOMETRY",
                    Message = $"可见房间名尚有 {missingLabels.Count} 个无对应空间：{string.Join("、", missingLabels)}；需在校对页补画或调整边界"
                });

            // 堆叠标签降级（P1）：同一点位堆叠多枚房间名（深化图标签块常见）不参与房间指派，
            // 降级为未命名空间的候选名清单，避免一个房间继承整户标签
            if (analysis.StackedGroups.Count > 0)
            {
                var names = analysis.StackedGroups.SelectMany(g => g.Names).Distinct().ToList();
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "LABELS_STACKED",
                    Message = $"检测到 {analysis.StackedGroups.Count} 处房间名标签堆叠（{names.Count} 个标签无法定位：{string.Join("、", names.Take(10))}），相关空间以未命名输出，请人工命名"
                });
                diag.Add($"堆叠标签 {analysis.StackedGroups.Count} 处，{names.Count} 个标签降级为候选名");
            }

            // ⑦ 尺寸交叉验证 + 输出装配
            ct.ThrowIfCancellationRequested();
            var outputEnvelope = new Envelope(); // 产出空间（房间+未命名）多边形并集外包络
            // DTO 与原始 NTS 多边形（含内环/孔洞）的并行索引：家具归属判定必须用带孔几何，
            // DTO 只保留外环（前端契约），直接回转会丢失孔洞导致墙条/框形空间误判包含
            var spaceGeomIndex = new List<(object Dto, Polygon Geom)>();
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
                    var unnamedDto = new UnnamedRegionDto
                    {
                        Polygon = ToPolygon(s.Polygon),
                        BBox = ToBounds(bb),
                        WidthMm = widthMm,
                        DepthMm = depthMm,
                        AreaM2 = areaM2,
                        LabelPoint = ToPoint(s.Polygon.InteriorPoint.Coordinate),
                        Hint = StackedCandidateHint(s, analysis.StackedGroups)
                               ?? RoomLabelMatcher.NearestLabelHint(s, analysis.AssignTexts, labelRe)
                    };
                    result.UnnamedRegions.Add(unnamedDto);
                    spaceGeomIndex.Add((unnamedDto, s.Polygon));
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

                var roomDto = new RoomDto
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
                    Confidence = analysis.RasterRebuilt ? "low"
                        : checks.Count > 0 && checks.All(c => c.Consistent) ? "high" : "mid"
                };
                result.Rooms.Add(roomDto);
                spaceGeomIndex.Add((roomDto, s.Polygon));
            }
            diag.Add($"输出：命名房间 {result.Rooms.Count}，未命名空间 {result.UnnamedRegions.Count}");

            // 房间稳定 ID：按 labelPoint 排序确定性编号（ROOM-001…），家具 roomId 引用之；
            // 未命名空间续编同一序列（P1：未命名空间也参与家具归属）
            RoomIdAssigner.Assign(result.Rooms, result.UnnamedRegions);

            var furniturePreviewPrimitives = new List<FlatEntity>();
            // ⑦b INSERT 家具识别（P0-B Parser 侧）
            ct.ThrowIfCancellationRequested();
            string? DtoId(object d) => d switch
            {
                RoomDto r => r.RoomId,
                UnnamedRegionDto u => u.RoomId,
                _ => null
            };
            var roomGeoms = spaceGeomIndex
                .Select(x => (Id: DtoId(x.Dto), x.Geom))
                .Where(x => x.Id != null)
                .Select(x => (x.Id!, x.Geom))
                .ToList();
            try
            {
                var furnResult = FurnitureDetector.DetectDetailed(blocks.Roots, roomGeoms, target.Region, diag, opt);
                result.Furniture = furnResult.Items;
                furniturePreviewPrimitives = furnResult.PreviewPrimitives;

                // P1-C isolation 视图（请求方开启 EmitIsolationImages 时）：每个视觉语义分组
                // 渲染一张白底线稿小图（块局部坐标，旋转已归一化），供后端视觉模型识别语义
                if (opt.EmitIsolationImages && furnResult.VisionGroupLocalPrims.Count > 0)
                {
                    var images = new Dictionary<string, string>();
                    foreach (var kv in furnResult.VisionGroupLocalPrims)
                    {
                        ct.ThrowIfCancellationRequested();
                        try
                        {
                            var bb = new Envelope();
                            foreach (var p in kv.Value)
                                if (!p.BBox.IsNull) bb.ExpandToInclude(p.BBox);
                            if (bb.IsNull) continue;
                            double margin = Math.Max(bb.MaxX - bb.MinX, bb.MaxY - bb.MinY) * 0.1 + 50;
                            bb.ExpandBy(margin);
                            var iso = CadPreviewRenderer.Render(kv.Value, ToBounds(bb), opt.IsolationImageMaxSide);
                            if (iso.PngBase64 != null) images[kv.Key] = iso.PngBase64;
                        }
                        catch (OperationCanceledException) { throw; }
                        catch (Exception isoEx) { diag.Add($"isolation 渲染失败（{kv.Key}）：{isoEx.Message}"); }
                    }
                    if (images.Count > 0)
                    {
                        result.IsolationImages = images;
                        diag.Add($"isolation 视图：{images.Count} 个视觉语义分组");
                    }
                }
            }
            catch (Exception furnEx)
            {
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "FURNITURE_DETECTION_FAILED",
                    Message = $"家具识别异常（不影响房间结果）：{furnEx.Message}"
                });
                diag.Add("家具识别异常：" + furnEx);
                result.Furniture = new List<FurnitureDto>();
            }

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
                // 无产出空间：目标标签区域可能偏离家具主体（标签堆叠/残缺时常见），
                // 扩展到覆盖全部家具候选包围盒，保证规范预览与 context 裁剪能覆盖家具
                if (result.Furniture != null)
                {
                    var furnitureExtent = new Envelope();
                    foreach (var f in result.Furniture)
                    {
                        if (f.FootprintPolygon == null) continue;
                        foreach (var p in f.FootprintPolygon)
                            furnitureExtent.ExpandToInclude(p[0], p[1]);
                    }
                    if (!furnitureExtent.IsNull)
                    {
                        var expanded = ToEnvelope(result.DrawingBounds!);
                        expanded.ExpandToInclude(furnitureExtent);
                        if (!expanded.Equals(ToEnvelope(result.DrawingBounds!)))
                        {
                            result.DrawingBounds = ToBounds(expanded);
                            diag.Add("drawingBounds 扩展覆盖家具包围盒（标签区域未覆盖全部家具）");
                        }
                    }
                }
                issues.Add(new QualityIssue { Level = "warn", Code = "BOUNDS_FALLBACK", Message = "无产出空间，drawingBounds 回退为目标标签区域（已扩展覆盖家具范围）" });
            }

            // 规范预览：单候选时 bounds == drawingBounds（行为不变）；
            // 多候选时 bounds = 全部候选 bounds 并集 + margin（前端可在同一坐标系查看/切换候选）。
            var previewBounds = result.DrawingBounds!;
            if (selection.Candidates.Count > 1)
            {
                var union = new Envelope();
                foreach (var c in selection.Candidates) union.ExpandToInclude(c.Region);
                union.ExpandBy(opt.OutputBoundsMarginMm);
                previewBounds = ToBounds(union);
            }
            try
            {
                ct.ThrowIfCancellationRequested();
                var previewEntities = CanonicalPreviewSelector.Select(ext.Entities, blocks.Roots, opt);
                previewEntities = previewEntities.Concat(furniturePreviewPrimitives).Distinct().ToList();
                // 预览范围包含附近可见线稿，避免只按房间外包络裁掉顶部窗台与外墙。
                var fullPreview = ToEnvelope(previewBounds);
                var nearby = fullPreview.Copy(); nearby.ExpandBy(1000);
                foreach (var e in previewEntities.Where(e => e.BBox.Intersects(nearby)
                    && e.BBox.Width <= nearby.Width && e.BBox.Height <= nearby.Height))
                    fullPreview.ExpandToInclude(e.BBox);
                fullPreview.ExpandBy(100);
                previewBounds = ToBounds(fullPreview);
                result.Preview = CadPreviewRenderer.Render(previewEntities, previewBounds, inputHash);
                diag.Add($"规范预览：{result.Preview.Width}×{result.Preview.Height}px，建筑与家具图元 {previewEntities.Count}/{allEntities.Count}");
            }
            catch (OperationCanceledException) { throw; }
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

            if (unsupported.Count > 0)
            {
                result.UnsupportedEntitySummary = unsupported.OrderByDescending(kv => kv.Value)
                    .ToDictionary(kv => kv.Key, kv => kv.Value);
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "UNSUPPORTED_ENTITIES",
                    Message = $"存在不支持/降级的实体类型（已保留包围盒，未静默丢弃）：{string.Join("，", result.UnsupportedEntitySummary.Take(5).Select(kv => $"{kv.Key}×{kv.Value}"))}"
                });
            }

            // parseStatus 兼容映射（§18.3）：房间阶段的 block 级问题（无墙体/无闭合空间）在
            // 存在其他可用事实（房间/家具/有效标签候选）时降级为 warn —— FAILED 只留给
            // "目标 Scene 无法形成且无有效候选 / 无可恢复数据" 的情况。
            var roomStageBlock = new HashSet<string> { "NO_WALLS_FOUND", "NO_CLOSED_REGION" };
            bool hasUsableFacts = result.Rooms.Count > 0 || result.UnnamedRegions.Count > 0
                                  || (result.Furniture?.Count ?? 0) > 0 || target.FromLabels;
            if (hasUsableFacts)
                foreach (var i in issues.Where(i => i.Level == "block" && roomStageBlock.Contains(i.Code)))
                    i.Level = "warn";

            result.Success = issues.All(i => i.Level != "block");
            if (!result.Success)
                result.ErrorCode = issues.First(i => i.Level == "block").Code;

            result.Capabilities = BuildCapabilities(result, blocks, dims.Count);
            result.ParseStatus = DeriveParseStatus(result, issues);
            if (!result.Success && result.ParseStatus != "FAILED")
                result.ParseStatus = "FAILED";
            diag.Add($"parseStatus={result.ParseStatus}，capabilities={string.Join(",", result.Capabilities.Select(kv => kv.Key + ":" + kv.Value))}");
        }
        catch (OperationCanceledException)
        {
            // 超时/取消：已有可展示结果 → PARTIAL；完全无可用结果 → FAILED
            diag.Add("解析超时或已取消");
            result.UnsupportedEntitySummary = unsupported.Count > 0 ? unsupported : null;
            if (result.Rooms.Count > 0 || (result.Furniture?.Count ?? 0) > 0 || result.DrawingBounds != null)
            {
                result.Success = true;
                result.ParseStatus = "PARTIAL";
                result.QualityIssues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "PARSER_TIMEOUT",
                    Message = $"解析超过时间上限（{opt.MaxParseTimeSeconds}s）或被取消，输出已产生的部分结果"
                });
                result.Capabilities ??= BuildCapabilities(result, null, 0);
            }
            else
            {
                result.Success = false;
                result.ParseStatus = "FAILED";
                result.ErrorCode = "PARSER_TIMEOUT";
                result.ErrorMessage = $"解析超过时间上限（{opt.MaxParseTimeSeconds}s）且无可恢复结果";
            }
            return Finish(result);
        }
        catch (Exception ex)
        {
            result.Success = false;
            result.ParseStatus = "FAILED";
            result.ErrorCode = "FILE_CORRUPT";
            result.ErrorMessage = $"CAD 文件解析失败：{ex.Message}";
            diag.Add("异常：" + ex);
            return Finish(result);
        }

        return Finish(result);
    }

    /// <summary>一次空间分析路径的产出（主路径 / 块展开回退共用结构）。</summary>
    private sealed class SpaceAnalysis
    {
        public List<AlignedSpace> Spaces { get; set; } = new();
        public WallExtractor.WallExtractionResult Walls { get; set; } = new();
        public List<FlatEntity> Texts { get; set; } = new();
        /// <summary>剔除堆叠标签后的指派用文字。</summary>
        public List<FlatEntity> AssignTexts { get; set; } = new();
        public List<FlatEntity> Dims { get; set; } = new();
        /// <summary>堆叠标签组（点位质心 + 去重标签名），供候选名 hint。</summary>
        public List<(Coordinate Center, List<string> Names)> StackedGroups { get; set; } = new();
        /// <summary>P1-E 栅格重建产出（置信度一律 low，需人工复核）。</summary>
        public bool RasterRebuilt { get; set; }
        public bool LabelPartitioned { get; set; }
        public bool LabelSnapped { get; set; }
        /// <summary>局部墙体/门洞候选恢复的标签空间数量。</summary>
        public int LocalLabelRecovered { get; set; }
        /// <summary>
        /// 内侧面吸附质量门（暂存）：AnalyzeSpaces/TryRasterRebuild 可能是被放弃的候选路径，
        /// 由编排层在确定最终采用的分析后才并入全局 issues，避免废弃路径的提示泄漏。
        /// </summary>
        public List<QualityIssue> SnapIssues { get; set; } = new();
    }

    /// <summary>
    /// 空间分析单路径（④ HATCH 墙体 → ⑤ 线网多边形化 → ⑥ 语义对齐 + 标签指派）。
    /// 堆叠标签在指派前剔除（含 SpaceAligner 的凹位归并保护），避免整户标签挂到单个空间。
    /// </summary>
    private static SpaceAnalysis AnalyzeSpaces(
        List<FlatEntity> scopeEntities, ParserOptions o,
        List<SemanticAnnotation> semanticAnns, System.Text.RegularExpressions.Regex labelRe,
        string tag, List<QualityIssue> issues, List<string> diag)
    {
        var analysis = new SpaceAnalysis { Walls = WallExtractor.Extract(scopeEntities, o, diag) };
        analysis.Texts = scopeEntities.Where(e => e.Kind == FlatKind.Text).ToList();
        analysis.Dims = scopeEntities.Where(e => e.Kind == FlatKind.Dimension).ToList();
        diag.Add($"{tag}目标区域文字 {analysis.Texts.Count}、DIMENSION {analysis.Dims.Count}");

        var stacked = DetectStackedLabels(analysis.Texts, labelRe, o);
        analysis.StackedGroups = stacked.Select(g => (g.Center, g.Names)).ToList();
        var stackedSet = new HashSet<FlatEntity>();
        foreach (var g in stacked) foreach (var e in g.Entities) stackedSet.Add(e);
        analysis.AssignTexts = stackedSet.Count == 0
            ? analysis.Texts
            : analysis.Texts.Where(t => !stackedSet.Contains(t)).ToList();

        var cells = RoomPolygonizer.BuildRooms(analysis.Walls, analysis.Texts, o, issues, diag);
        analysis.Spaces = SpaceAligner.Align(cells, analysis.AssignTexts, analysis.Walls, semanticAnns, o, issues, diag);
        foreach (var sp in analysis.Spaces)
            RoomLabelMatcher.AssignLabel(sp, analysis.AssignTexts, labelRe);
        // P2-S：房间轮廓统一贴合墙体内侧面（Room Polygon Snap 到 Wall Inner Face）。
        // 质量门暂存 SnapIssues：本路径可能被放弃，由编排层在采用后统一并入。
        if (o.WallSnapEnabled)
        {
            var faces = WallInnerFaceExtractor.Build(scopeEntities, analysis.Walls, o, diag);
            RoomWallSnapper.SnapAll(analysis.Spaces, faces, analysis.Walls.WallUnion, o, analysis.SnapIssues, diag, tag);
        }
        return analysis;
    }

    /// <summary>
    /// 建筑几何回退实体集（P1 Scene-aware Block Fallback）：块展开图元中，
    /// 剔除 FURNITURE 角色节点的线/填充（家具轮廓不围合空间）与排除图层（机电/淡显/窗帘等），
    /// 文字/尺寸全保留（标签与标注来源）。ARCHITECTURE / SCENE_CONTAINER / UNKNOWN 角色的
    /// 建筑线（墙/柱/梁/门窗洞口线）由此进入线网多边形化。
    /// </summary>
    private static readonly System.Text.RegularExpressions.Regex NonArchitectureBlockPattern = new(
        @"(?i)(给排水|管网|空调|吸尘器|电视机|家电|洁具|灯具|烟感|喷淋|摄像|equipment|plumbing|hvac)",
        System.Text.RegularExpressions.RegexOptions.Compiled);

    private static List<FlatEntity> BuildArchitectureFallbackEntities(
        IReadOnlyList<BlockResolver.InsertNode> roots, Envelope targetRegion, ParserOptions opt, List<string> diag,
        bool relaxedForRaster = false)
    {
        // 栅格重建用更宽的排除集：0 层/淡显层在深化图里常承载既有建筑结构（墙线），
        // 只剔除硬噪声（机电点位/文字标注/窗帘等），杂质交给形态学与面积过滤吸收
        var pattern = relaxedForRaster
            ? opt.RasterExcludeLayerPattern
            : opt.FallbackExcludeLayerPattern;
        var excludeRe = new System.Text.RegularExpressions.Regex(pattern);
        var list = new List<FlatEntity>();
        int furnitureNodes = 0, layerDropped = 0;

        void Walk(BlockResolver.InsertNode node)
        {
            var role = BlockRoleClassifier.Classify(node, opt);
            bool isFurniture = role.Role == BlockRole.FURNITURE;
            // 家具/设备父块的子树也属于该实例，不让显式 0 层子块重新进入建筑集。
            // 方案容器仍需下钻，因为它同时封装建筑与家具。
            bool excludedInstance = role.Role != BlockRole.SCENE_CONTAINER
                && (isFurniture || excludeRe.IsMatch(node.Layer ?? "")
                    || NonArchitectureBlockPattern.IsMatch(node.BlockName ?? ""));
            if (isFurniture) furnitureNodes++;
            if (excludedInstance)
            {
                list.AddRange(node.AllPrimitives().Where(p => p.Kind is FlatKind.Text or FlatKind.Dimension
                    && !p.BBox.IsNull && p.BBox.Intersects(targetRegion)));
                return;
            }
            foreach (var p in node.Primitives)
            {
                if (p.BBox.IsNull || !p.BBox.Intersects(targetRegion)) continue;
                bool isTextDim = p.Kind is FlatKind.Text or FlatKind.Dimension;
                if (!isTextDim)
                {
                    if (isFurniture) continue;
                    // 矢量回退（线网）对图层噪声敏感：块内 0 层线不可信（方案容器里墙线/家具线混杂），宁缺毋滥；
                    // 栅格重建对噪声容忍度高（膨胀封口+泛洪+面积过滤），0 层线放行
                    if (!relaxedForRaster && (p.Layer is "" or "0")) { layerDropped++; continue; }
                    if (excludeRe.IsMatch(p.Layer ?? "")) { layerDropped++; continue; }
                }
                list.Add(p);
            }
            foreach (var c in node.Children) Walk(c);
        }

        foreach (var r in roots) Walk(r);
        diag.Add($"块展开建筑几何{(relaxedForRaster ? "（栅格宽集）" : "")}：{list.Count} 图元（排除家具角色节点 {furnitureNodes} 个、噪声图层 {layerDropped} 图元）");
        return list;
    }

    /// <summary>
    /// 堆叠标签检测：半径 StackedLabelRadiusMm 内不同房间标签数 ≥ StackedLabelMinCount 的
    /// 标签簇判为堆叠（深化图整户标签块位置塌缩到同一点的典型形态）。
    /// </summary>
    private static List<(Coordinate Center, List<string> Names, List<FlatEntity> Entities)> DetectStackedLabels(
        List<FlatEntity> texts, System.Text.RegularExpressions.Regex labelRe, ParserOptions opt)
    {
        var labels = texts
            .Where(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
                        && t.Text.Length <= 12 && labelRe.IsMatch(t.Text) && !RoomLabelMatcher.IsCompositeName(t.Text))
            .ToList();
        int n = labels.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (labels[i].TextPos!.Distance(labels[j].TextPos!) <= opt.StackedLabelRadiusMm)
                { int a = Find(i), b = Find(j); if (a != b) parent[a] = b; }

        var result = new List<(Coordinate, List<string>, List<FlatEntity>)>();
        foreach (var g in labels.Select((e, i) => (e, root: Find(i))).GroupBy(x => x.root))
        {
            var members = g.Select(x => x.e).ToList();
            var names = members.Select(m => m.Text!).Distinct().ToList();
            if (names.Count < opt.StackedLabelMinCount) continue;
            double cx = members.Average(m => m.TextPos!.X), cy = members.Average(m => m.TextPos!.Y);
            result.Add((new Coordinate(cx, cy), names, members));
        }
        return result;
    }

    /// <summary>未命名空间的堆叠候选名 hint：空间多边形覆盖堆叠点时，给出候选名清单。</summary>
    private static string? StackedCandidateHint(AlignedSpace space,
        List<(Coordinate Center, List<string> Names)> stackedGroups)
    {
        foreach (var g in stackedGroups)
        {
            var point = Nts.GeometryFactory.CreatePoint(g.Center);
            if (space.Polygon.Contains(point) || space.Polygon.Touches(point))
                return "候选名：" + string.Join("/", g.Names);
        }
        return null;
    }

    /// <summary>
    /// 栅格重建尝试（P1-E）：从建筑实体集提取墙/门线 → RasterRoomReconstructor →
    /// 套用堆叠标签隔离与标签指派；有可输出空间时返回新的 SpaceAnalysis，否则 null。
    /// </summary>
    private static SpaceAnalysis? TryRasterRebuild(
        List<FlatEntity> archEntities, ParserOptions geometryOpt,
        SpaceAnalysis textSource, Envelope targetRegion, ParserOptions opt,
        System.Text.RegularExpressions.Regex labelRe, List<string> diag,
        List<FlatEntity>? rasterEntities = null, IReadOnlyList<FlatEntity>? visibleEntities = null)
    {
        // 栅格对图层噪声不敏感：用更宽的实体集（含块内 0 层/淡显层墙线）
        var walls = WallExtractor.Extract(rasterEntities ?? archEntities, geometryOpt, diag);
        int doorThresholds = DoorBoundaryReconstructor.Apply(walls);
        if (doorThresholds > 0) diag.Add($"门洞局部阈线：{doorThresholds} 处（门扇/开启弧及两侧建筑线共同确认）");
        // 栅格区域 = 目标标签区 ∪ 墙线实际分布：标签堆叠/残缺时标签区域可能偏离
        // 建筑主体（601 实测：12m 标签区只盖住公寓一半），以墙线包围盒为准兜底
        var rasterRegion = targetRegion.Copy();
        foreach (var pts in walls.WallLines.Concat(walls.DoorLines))
            foreach (var p in pts) rasterRegion.ExpandToInclude(p);
        var fillRings = walls.WallSolids.Select(g => g.ExteriorRing.Coordinates.ToList()).ToList();
        (List<AlignedSpace> Spaces, int SplitCount, int IndependentLabels, int CoveredLabels, int RawCount)
            Candidate(double radius, bool verbose)
        {
            var candidateOpt = opt.Clone();
            candidateOpt.RasterWallDilateMm = radius;
            var rebuilt = RasterRoomReconstructor.Rebuild(walls.WallLines, walls.DoorLines, rasterRegion,
                candidateOpt, verbose ? diag : null, fillRings,
                textSource.AssignTexts.Where(t => t.Kind == FlatKind.Text && t.Text != null
                    && labelRe.IsMatch(t.Text) && !RoomLabelMatcher.IsCompositeName(t.Text)).ToList());
            var split = RasterLabelPartitioner.Split(rebuilt.Spaces, textSource.AssignTexts, labelRe,
                opt.MinRoomAreaM2, out int count);
            foreach (var sp in split) RoomLabelMatcher.AssignLabel(sp, textSource.AssignTexts, labelRe);
            int labels = split.SelectMany(sp => (sp.Label == null ? Array.Empty<string>() : new[] { sp.Label })
                    .Concat(sp.MemberLabels)).Distinct().Count();
            int independent = split.Count(sp => sp.Label != null && sp.MemberLabels.Count == 0);
            return (split, count, independent, labels, rebuilt.Spaces.Count);
        }

        var best = Candidate(opt.RasterWallDilateMm, true);
        double bestRadius = opt.RasterWallDilateMm;
        int totalLabels = textSource.AssignTexts.Where(t => t.Kind == FlatKind.Text && t.Text != null
            && labelRe.IsMatch(t.Text)).Select(t => t.Text!).Distinct().Count();
        // 可见标签远多于围合空间时，补测较宽门洞；以可独立指派的标签数选结果。
        if (totalLabels >= 6 && best.IndependentLabels < totalLabels * 0.8)
            foreach (double radius in new[] { 100.0, 150.0, 200.0, 300.0, 450.0, 600.0, 650.0 }.Where(r => r != opt.RasterWallDilateMm))
            {
                var candidate = Candidate(radius, false);
                if (candidate.IndependentLabels <= best.IndependentLabels) continue;
                best = candidate;
                bestRadius = radius;
            }
        if (best.Spaces.Count == 0) return null;
        var assignedNames = best.Spaces.SelectMany(sp => (sp.Label == null ? Array.Empty<string>() : new[] { sp.Label })
            .Concat(sp.MemberLabels)).ToHashSet();
        var unmatchedTexts = textSource.AssignTexts.Where(t => t.Kind == FlatKind.Text && t.Text != null
                && t.TextPos != null && labelRe.IsMatch(t.Text) && !assignedNames.Contains(t.Text))
            .GroupBy(t => t.Text).Select(g => g.First()).ToList();
        int snapped = 0;
        foreach (var space in best.Spaces.Where(sp => sp.Label == null))
        {
            var near = unmatchedTexts.Select(t => (Text: t, Distance: space.Polygon.Distance(Nts.GeometryFactory.CreatePoint(t.TextPos!))))
                .OrderBy(x => x.Distance).ToList();
            if (near.Count == 0 || near[0].Distance > Math.Min(500, bestRadius)
                || near.Count > 1 && near[1].Distance - near[0].Distance < 150) continue;
            space.Label = near[0].Text.Text;
            unmatchedTexts.Remove(near[0].Text);
            snapped++;
        }
        // 主尺度保持已识别空间；其他尺度仅补回遗漏标签对应的独立小房间。
        // 不用整套候选替换主结果，避免找回公卫却丢失玄关等空间。
        int recovered = 0;
        foreach (double radius in new[] { 300.0, 450.0, 600.0, 650.0 }.Where(r => r != bestRadius))
        {
            var alternative = Candidate(radius, false);
            foreach (var space in alternative.Spaces.Where(sp => sp.Label != null && sp.MemberLabels.Count == 0))
            {
                if (best.Spaces.Any(sp => sp.Label == space.Label || sp.MemberLabels.Contains(space.Label!))) continue;
                if (space.Polygon.Area / 1e6 > 8) continue;
                var label = unmatchedTexts.FirstOrDefault(t => t.Text == space.Label);
                if (label?.TextPos == null || !space.Polygon.Covers(Nts.GeometryFactory.CreatePoint(label.TextPos))) continue;
                var occupied = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(
                    best.Spaces.Select(sp => (Geometry)sp.Polygon).ToList());
                var remaining = space.Polygon.Difference(occupied);
                if (remaining is not Polygon polygon || polygon.IsEmpty
                    || polygon.Area / space.Polygon.Area < 0.8
                    || polygon.Area / 1e6 < opt.MinRoomAreaM2
                    || !polygon.Covers(Nts.GeometryFactory.CreatePoint(label.TextPos))) continue;
                best.Spaces.Add(new AlignedSpace { Polygon = polygon, Label = space.Label });
                unmatchedTexts.Remove(label);
                recovered++;
            }
        }
        if (recovered > 0) diag.Add($"多尺度补回独立小房间 {recovered} 个（不覆盖已有空间，需人工复核）");
        diag.Add($"栅格重建选择封口半径 {bestRadius:F0}mm：原始空间 {best.RawCount}，标签分区 {best.SplitCount} 处 → {best.Spaces.Count} 个，独立标签 {best.IndependentLabels}、覆盖标签 {best.CoveredLabels}/{totalLabels}");
        if (snapped > 0) diag.Add($"封口墙带附近的可见标签就近指派 {snapped} 个");
        // P2-S：栅格轮廓统一贴合墙体内侧面（与栅格墙线同实体集，含完成面类图层线）
        var snapIssues = new List<QualityIssue>();
        WallInnerFaceSet? faces = null;
        if (opt.WallSnapEnabled)
        {
            faces = WallInnerFaceExtractor.Build(rasterEntities ?? archEntities, walls, opt, diag);
            RoomWallSnapper.SnapAll(best.Spaces, faces, walls.WallUnion, opt, snapIssues, diag, "【栅格重建】");
        }
        int finishRecovered = FinishRoomRecovery.Recover(best.Spaces, rasterEntities ?? archEntities,
            textSource.AssignTexts, labelRe, opt);
        if (finishRecovered > 0) diag.Add($"闭合完成面恢复 {finishRecovered} 个房间（替换估计边界）");
        int locallyRecovered = LocalLabelRoomRecovery.Recover(best.Spaces, rasterEntities ?? archEntities,
            textSource.AssignTexts, labelRe, opt, diag);
        if (locallyRecovered > 0)
            foreach (var space in best.Spaces)
                RoomLabelMatcher.AssignLabel(space, textSource.AssignTexts, labelRe);
        if (locallyRecovered > 0) diag.Add($"局部墙体/门洞边界恢复 {locallyRecovered} 个未闭合标签空间（结果待人工复核）");
        if (OpenPassageRefiner.Refine(best.Spaces, textSource.AssignTexts, opt, diag))
        {
            OpenLivingDiningRefiner.Refine(best.Spaces, textSource.AssignTexts,
                visibleEntities ?? rasterEntities ?? archEntities, opt, diag);
            if (faces != null)
                RoomWallSnapper.RefreshFaceNotFoundIssue(best.Spaces, faces, walls.WallUnion, opt, snapIssues);
        }
        return new SpaceAnalysis
        {
            Spaces = best.Spaces,
            Walls = walls,
            Texts = textSource.Texts,
            AssignTexts = textSource.AssignTexts,
            Dims = textSource.Dims,
            StackedGroups = textSource.StackedGroups,
            RasterRebuilt = true,
            LabelPartitioned = best.SplitCount > 0,
            LabelSnapped = snapped > 0,
            LocalLabelRecovered = locallyRecovered,
            SnapIssues = snapIssues
        };
    }

    private static string CandidateId(TargetSelector.TargetRegion c)
        => ParserInfo.TargetId("MODEL_SPACE", c.Region.MinX, c.Region.MinY, c.Region.MaxX, c.Region.MaxY);

    /// <summary>能力级状态装配（设计文档 §18）。</summary>
    private static Dictionary<string, string> BuildCapabilities(
        CadParseResult result, BlockResolver.ResolveResult? blocks, int dimensionCount)
    {
        // 一处有名称的空间不能证明整户已恢复。栅格重建的边界本身需要复核；
        // 未命名空间或堆叠标签意味着房间语义尚未完整。
        bool rasterRebuilt = result.QualityIssues.Any(i => i.Code == "ROOMS_FROM_RASTER_REBUILD");
        bool labelsStacked = result.QualityIssues.Any(i => i.Code == "LABELS_STACKED");
        bool missingLabels = result.QualityIssues.Any(i => i.Code == "ROOM_LABELS_WITHOUT_GEOMETRY");
        string rooms = rasterRebuilt || (result.Rooms.Count == 0 && result.UnnamedRegions.Count == 0)
            ? "NEEDS_REVIEW"
            : result.UnnamedRegions.Count > 0 || labelsStacked || missingLabels
                ? "PARTIAL"
                : "SUCCESS";

        string furniture;
        var furn = result.Furniture;
        if (blocks == null)
            furniture = "NEEDS_REVIEW";
        else if (furn == null || furn.Count == 0)
            furniture = blocks.Roots.Count == 0 ? "SUCCESS" : "PARTIAL";
        else if (furn.Any(f => f.QualityFlags.Contains("UNRESOLVED_BLOCK")))
            furniture = "PARTIAL";
        else if (furn.All(f => f.Category == "UNKNOWN"))
            furniture = "NEEDS_REVIEW";
        else if (furn.Any(f => f.Category == "UNKNOWN"))
            furniture = "PARTIAL";
        else
            furniture = "SUCCESS";

        return new Dictionary<string, string>
        {
            ["rooms"] = rooms,
            ["walls"] = result.Rooms.Count > 0 ? "PARTIAL" : "NEEDS_REVIEW",
            ["doors"] = "NOT_IMPLEMENTED",
            ["windows"] = "NOT_IMPLEMENTED",
            ["furniture"] = furniture,
            ["dimensions"] = dimensionCount > 0 ? "PARTIAL" : "SUCCESS"
        };
    }

    /// <summary>
    /// parseStatus 派生（设计文档 §18.1-§18.3）：success=false 当且仅当 FAILED；
    /// XREF_UNRESOLVED / 家具全部 UNKNOWN / 栅格重建 → NEEDS_REVIEW；其余 warn → PARTIAL。
    /// </summary>
    private static string DeriveParseStatus(CadParseResult result, List<QualityIssue> issues)
    {
        if (!result.Success) return "FAILED";

        bool allUnknown = result.Furniture is { Count: > 0 } f && f.All(x => x.Category == "UNKNOWN");
        var needsReview = new HashSet<string>
        {
            "XREF_UNRESOLVED", "FURNITURE_DETECTION_FAILED", "ROOMS_FROM_RASTER_REBUILD"
        };
        if (allUnknown || issues.Any(i => needsReview.Contains(i.Code)))
            return "NEEDS_REVIEW";
        if (issues.Any(i => i.Level is "warn" or "block"))
            return "PARTIAL";
        return "SUCCESS";
    }

    private static Envelope ToEnvelope(BoundsDto b) => new(b.MinX, b.MaxX, b.MinY, b.MaxY);

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
