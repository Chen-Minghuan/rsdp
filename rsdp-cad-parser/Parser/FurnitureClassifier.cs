using System.Text.RegularExpressions;

namespace RsdpCadParser.Parser;

/// <summary>
/// 家具类别判定（设计文档 §10.1/§10.2）：
/// 先排除非家具块（门/窗/卫浴洁具/灯具/尺寸标注/图框/设备等，按块名 + 有效图层正则），
/// 再按块名 → ATTRIB 属性 → 有效图层给 SOFA/BED/TABLE/CHAIR/CABINET 打分；
/// 证据不足 → UNKNOWN（绝不丢弃、绝不强行归类）；确认是家具但不在词表 → OTHER。
/// `BLOCK1` 类通用名不算高可信。
/// </summary>
public static class FurnitureClassifier
{
    /// <summary>非家具排除正则（块名或有效图层命中即排除）。</summary>
    private static readonly Regex NonFurnitureRe = new(
        @"(?i)(door|门|window|(^|[^a-z])win|窗"
        + @"|马桶|坐便|蹲便|小便|洗手|洗漱|台盆|洗脸|浴缸|淋浴|花洒|龙头|洁具|toilet|basin|bidet|shower"
        + @"|吊灯|筒灯|射灯|吸顶灯|灯具|台灯|(?<![柜箱抽屉])灯|lamp|light"
        + @"|标注|标记|图框|图签|符号|marker|轴号|指北针|比例尺|legend|title|dim"
        + @"|开关|插座|配电|强弱电|设备|equip|空调|hvac|烟机|灶具|冰箱|洗衣机|烘干机|热水器|电视(?!柜)"
        + @"|电梯|楼梯|elevator|stair|植物|绿植|plant)",
        RegexOptions.Compiled);

    /// <summary>家具图层证据（命中但无类别词 → OTHER）。</summary>
    private static readonly Regex FurnitureLayerRe = new(@"(?i)(家具|陈设|活动家具|furn)", RegexOptions.Compiled);

    /// <summary>通用/匿名块名（不算高可信语义来源）。</summary>
    private static readonly Regex GenericNameRe = new(
        @"(?i)(^\*|^A\$|^block\d*$|^blk\d*$|^新建块|^unnamed|^untitled|^MARKER_)", RegexOptions.Compiled);

    // 类别词表（顺序即优先级：柜 优先于 床，使"床头柜"归 CABINET）
    private static readonly (string Category, Regex Re)[] CategoryRules =
    {
        ("SOFA", new Regex(@"(?i)(沙发|sofa)", RegexOptions.Compiled)),
        ("CABINET", new Regex(@"(?i)(柜|cabinet|wardrobe|closet|书架|bookcase)", RegexOptions.Compiled)),
        ("BED", new Regex(@"(?i)(床|bed)", RegexOptions.Compiled)),
        ("TABLE", new Regex(@"(?i)(桌|茶几|几案|餐台|吧台|table|desk|dining)", RegexOptions.Compiled)),
        ("CHAIR", new Regex(@"(?i)(椅|凳|chair|stool)", RegexOptions.Compiled)),
    };

    public class Verdict
    {
        /// <summary>true = 非家具（门/窗/洁具/灯具/标注/设备等），整块（含子树）排除。</summary>
        public bool Excluded { get; set; }
        public string? ExcludeReason { get; set; }
        /// <summary>SOFA/BED/TABLE/CHAIR/CABINET/OTHER/UNKNOWN；Excluded 时无意义。</summary>
        public string Category { get; set; } = "UNKNOWN";
        /// <summary>cad_block | cad_layer | geometry | null（UNKNOWN 且无证据）。</summary>
        public string? SemanticSource { get; set; }
        public List<string> Evidence { get; } = new();
        public string CategoryConfidence { get; set; } = "low";
    }

    /// <summary>判定一个 INSERT 节点（综合块名、属性、有效图层）。</summary>
    public static Verdict Classify(string blockName, IReadOnlyList<string> attributes, string effectiveLayer)
    {
        var v = new Verdict();

        // ① 非家具排除（块名 + 图层）
        if (Matches(NonFurnitureRe, blockName, out var why1) || Matches(NonFurnitureRe, effectiveLayer, out why1))
        {
            v.Excluded = true;
            v.ExcludeReason = why1;
            return v;
        }

        // ② 类别打分：块名 → ATTRIB → 图层
        bool nameGeneric = string.IsNullOrWhiteSpace(blockName) || GenericNameRe.IsMatch(blockName);
        if (!nameGeneric)
            foreach (var (cat, re) in CategoryRules)
                if (re.IsMatch(blockName))
                {
                    v.Category = cat;
                    v.SemanticSource = "cad_block";
                    v.Evidence.Add("block_name");
                    v.CategoryConfidence = "high";
                    return v;
                }

        foreach (var att in attributes)
            foreach (var (cat, re) in CategoryRules)
                if (re.IsMatch(att))
                {
                    v.Category = cat;
                    v.SemanticSource = "cad_block";
                    v.Evidence.Add("block_attrib");
                    v.CategoryConfidence = "high";
                    return v;
                }

        bool layerFurniture = !string.IsNullOrEmpty(effectiveLayer) && FurnitureLayerRe.IsMatch(effectiveLayer);
        if (layerFurniture)
        {
            foreach (var (cat, re) in CategoryRules)
                if (re.IsMatch(effectiveLayer))
                {
                    v.Category = cat;
                    v.SemanticSource = "cad_layer";
                    v.Evidence.Add("layer");
                    v.CategoryConfidence = "mid";
                    return v;
                }
            // 家具图层但无类别词：确认是家具但不在词表
            v.Category = "OTHER";
            v.SemanticSource = "cad_layer";
            v.Evidence.Add("layer");
            v.CategoryConfidence = "mid";
            return v;
        }

        // ③ 证据不足：UNKNOWN（保留候选，交人工）
        v.Category = "UNKNOWN";
        v.SemanticSource = "geometry";
        return v;
    }

    /// <summary>语义标签：属性值 &gt; 非通用块名 &gt; null。</summary>
    public static string? SemanticLabel(string blockName, IReadOnlyList<string> attributes)
    {
        if (attributes.Count > 0) return attributes[0];
        if (!string.IsNullOrWhiteSpace(blockName) && !GenericNameRe.IsMatch(blockName)) return blockName;
        return null;
    }

    private static bool Matches(Regex re, string? text, out string why)
    {
        why = "";
        if (string.IsNullOrEmpty(text)) return false;
        var m = re.Match(text);
        if (!m.Success) return false;
        why = m.Value;
        return true;
    }

    /// <summary>几何规模合理性（家具占地：100~6000mm、0.02~30㎡），仅作弱证据。</summary>
    public static bool PlausibleFurnitureSize(double widthMm, double depthMm)
    {
        double hi = Math.Max(widthMm, depthMm), lo = Math.Min(widthMm, depthMm);
        double area = hi * lo / 1e6;
        return hi is >= 100 and <= 6000 && lo is >= 50 and <= 3000 && area is >= 0.02 and <= 30;
    }
}
