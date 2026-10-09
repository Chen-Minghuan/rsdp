using ACadSharp;
using ACadSharp.Entities;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// BlockResolver（设计文档 §9）：递归展开模型空间 INSERT，
/// 同时输出块实例树（每个 INSERT 节点独立身份）与供空间算法使用的扁平展开几何。
///
/// - 展开图元携带 sourceHandle / insertPath / blockDefinitionName / 有效图层 / 已应用变换；
/// - 嵌套展开带环检测（按块定义句柄路径）与深度限制（MaxBlockDepth）；
/// - 块定义递归引用、缺失定义或展开数量超限（MaxExpandedEntities）时停止该分支并输出诊断，
///   不阻断其他家具和房间的解析；
/// - 块属性取当前实例（INSERT 上 ATTRIB）的值。
/// </summary>
public static class BlockResolver
{
    /// <summary>INSERT 实例节点（块实例树）。</summary>
    public class InsertNode
    {
        /// <summary>该 INSERT 自身句柄（hex）。</summary>
        public string Handle { get; set; } = "";
        public string BlockName { get; set; } = "";
        /// <summary>INSERT 所在图层。</summary>
        public string Layer { get; set; } = "";
        /// <summary>块局部坐标 → 世界毫米坐标的累积变换。</summary>
        public Affine2D Transform { get; set; } = Affine2D.Identity;
        /// <summary>从顶层 INSERT 到本节点的句柄路径。</summary>
        public List<string> InsertPath { get; set; } = new();
        /// <summary>实例属性值（ATTRIB Value 序列）。</summary>
        public List<string> Attributes { get; set; } = new();
        /// <summary>该节点自身的展开图元（不含子 INSERT 的）。</summary>
        public List<FlatEntity> Primitives { get; } = new();
        public List<InsertNode> Children { get; } = new();
        /// <summary>UNRESOLVED_BLOCK / BLOCK_EXPANSION_LIMIT / EMPTY_GEOMETRY 等。</summary>
        public List<string> QualityFlags { get; } = new();

        /// <summary>自身 + 全部子树图元。</summary>
        public IEnumerable<FlatEntity> AllPrimitives()
        {
            foreach (var p in Primitives) yield return p;
            foreach (var c in Children)
                foreach (var p in c.AllPrimitives()) yield return p;
        }
    }

    public class ResolveResult
    {
        public List<InsertNode> Roots { get; } = new();
        /// <summary>全部展开图元（并入主实体流，供聚类/墙体/预览使用）。</summary>
        public List<FlatEntity> ExpandedEntities { get; } = new();
        public List<QualityIssue> Issues { get; } = new();
        public int ExpandedCount { get; set; }
        public bool EntityLimitHit { get; set; }
    }

    public static ResolveResult Resolve(CadDocument doc, double scale, ParserOptions opt, CancellationToken ct, List<string>? diagnostics = null, Dictionary<string, int>? unsupported = null)
    {
        var result = new ResolveResult();

        foreach (var e in doc.ModelSpace.Entities)
        {
            if (e is not Insert ins || ins.IsInvisible) continue;
            ct.ThrowIfCancellationRequested();
            var node = ExpandInsert(ins, parentTransform: Affine2D.Identity, parentPath: new List<string>(),
                blockPath: new HashSet<ulong>(), depth: 1, scale, opt, result, ct, unsupported);
            if (node != null)
            {
                result.Roots.Add(node);
                CollectExpanded(node, result);
            }
        }
        if (result.EntityLimitHit)
        {
            result.Issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "ENTITY_LIMIT",
                Message = $"块展开图元数超过上限 {opt.MaxExpandedEntities}，部分 INSERT 未完整展开，结果不完整"
            });
            diagnostics?.Add("块展开达到 MaxExpandedEntities 上限，剩余 INSERT 仅保留实例节点");
        }

        diagnostics?.Add($"INSERT 实例：{result.Roots.Count} 个顶层节点，展开图元 {result.ExpandedCount} 个");
        return result;
    }

    private static void CollectExpanded(InsertNode node, ResolveResult result)
    {
        result.ExpandedEntities.AddRange(node.Primitives);
        foreach (var c in node.Children) CollectExpanded(c, result);
    }

    /// <summary>展开单个 INSERT：构造节点、展开块定义图元、递归子 INSERT。</summary>
    private static InsertNode? ExpandInsert(
        Insert ins, Affine2D parentTransform, List<string> parentPath, HashSet<ulong> blockPath,
        int depth, double scale, ParserOptions opt, ResolveResult result, CancellationToken ct,
        Dictionary<string, int>? unsupported, string? parentLayer = null)
    {
        ct.ThrowIfCancellationRequested();
        if (ins.IsInvisible) return null;
        var handle = ins.Handle.ToString("X");
        var block = ins.Block;
        var blockName = block?.Name ?? "";
        var node = new InsertNode
        {
            Handle = handle,
            BlockName = blockName,
            Layer = parentLayer == null ? ins.Layer?.Name ?? "" : EffectiveLayer(ins.Layer?.Name ?? "", parentLayer),
            InsertPath = parentPath.Concat(new[] { handle }).ToList(),
            Attributes = ReadAttributes(ins)
        };

        // 块局部 → 世界累积变换：p' = T_parent · T_insert · (p − basePoint)
        double baseX = 0, baseY = 0;
        try
        {
            if (block?.BlockEntity?.BasePoint is { } bp) { baseX = bp.X; baseY = bp.Y; }
        }
        catch { /* 基点缺失按 0 处理 */ }
        var local = Affine2D.FromInsert(ins.InsertPoint.X * scale, ins.InsertPoint.Y * scale,
            ins.Rotation, ins.XScale, ins.YScale, baseX * scale, baseY * scale);
        node.Transform = parentTransform.Compose(local);

        // 展开数量已超限：保留实例节点身份（家具候选不丢弃），不再展开几何
        if (result.EntityLimitHit)
        {
            node.QualityFlags.Add("UNRESOLVED_BLOCK");
            return node;
        }

        if (depth > opt.MaxBlockDepth)
        {
            node.QualityFlags.Add("UNRESOLVED_BLOCK");
            result.Issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "BLOCK_EXPANSION_LIMIT",
                Message = $"块「{blockName}」（handle {handle}）嵌套深度超过上限 {opt.MaxBlockDepth}，该分支停止展开"
            });
            return node;
        }

        if (block == null)
        {
            node.QualityFlags.Add("UNRESOLVED_BLOCK");
            result.Issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "UNRESOLVED_BLOCK",
                Message = $"INSERT（handle {handle}）引用的块定义缺失，无法展开"
            });
            return node;
        }

        // 环检测：同一块定义句柄出现在当前递归路径上
        if (!blockPath.Add(block.Handle))
        {
            node.QualityFlags.Add("UNRESOLVED_BLOCK");
            result.Issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "UNRESOLVED_BLOCK",
                Message = $"块「{blockName}」存在递归引用（环），该分支停止展开"
            });
            return node;
        }

        IEnumerable<Entity> blockEntities;
        try { blockEntities = block.Entities.ToList(); }
        catch (Exception ex)
        {
            node.QualityFlags.Add("UNRESOLVED_BLOCK");
            result.Issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "UNRESOLVED_BLOCK",
                Message = $"块「{blockName}」定义读取失败：{ex.Message}"
            });
            blockPath.Remove(block.Handle);
            return node;
        }

        foreach (var be in blockEntities)
        {
            ct.ThrowIfCancellationRequested();
            if (be is Insert nested)
            {
                var child = ExpandInsert(nested, node.Transform, node.InsertPath, blockPath, depth + 1, scale, opt, result, ct, unsupported, node.Layer);
                if (child != null) node.Children.Add(child);
                if (result.EntityLimitHit) { node.QualityFlags.Add("UNRESOLVED_BLOCK"); break; }
                continue;
            }
            if (result.ExpandedCount >= opt.MaxExpandedEntities)
            {
                result.EntityLimitHit = true;
                node.QualityFlags.Add("UNRESOLVED_BLOCK");
                break;
            }
            try
            {
                var flat = EntityExtractor.Convert(be, scale, node.Transform, unsupported);
                if (flat == null) continue;
                // 有效图层：块内 0/ByBlock 层解析为 INSERT 所在图层
                var effLayer = EffectiveLayer(flat.Layer, node.Layer);
                flat.EffectiveLayer = effLayer;
                flat.Layer = effLayer;
                flat.SourceHandle = be.Handle.ToString("X");
                flat.InsertPath = node.InsertPath;
                flat.BlockDefinitionName = blockName;
                flat.TransformApplied = node.Transform.ToArray();
                node.Primitives.Add(flat);
                result.ExpandedCount++;
            }
            catch (OperationCanceledException) { throw; }
            catch { /* 单图元展开失败不阻断 */ }
        }

        blockPath.Remove(block.Handle);
        return node;
    }

    /// <summary>读取 INSERT 实例属性值（ATTRIB）。</summary>
    private static List<string> ReadAttributes(Insert ins)
    {
        var values = new List<string>();
        try
        {
            foreach (var att in ins.Attributes)
            {
                var v = MTextCleaner.Clean(att.Value);
                if (!string.IsNullOrWhiteSpace(v)) values.Add(v.Trim());
            }
        }
        catch { /* 属性读取失败按无属性处理 */ }
        return values;
    }

    /// <summary>有效图层：块内实体在 0 层/ByBlock 时继承 INSERT 图层。</summary>
    public static string EffectiveLayer(string entityLayer, string insertLayer)
        => string.IsNullOrEmpty(entityLayer) || entityLayer == "0" || entityLayer.Equals("ByBlock", StringComparison.OrdinalIgnoreCase)
            ? insertLayer
            : entityLayer;
}
