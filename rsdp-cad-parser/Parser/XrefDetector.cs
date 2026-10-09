using ACadSharp;
using ACadSharp.Blocks;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>XREF 检测（设计文档 §24.1）：P0 仅检测 XREF_PRESENT / XREF_UNRESOLVED，不解析外部文件。</summary>
public static class XrefDetector
{
    public static List<QualityIssue> Detect(CadDocument doc)
    {
        var issues = new List<QualityIssue>();
        try
        {
            var xrefs = doc.BlockRecords
                .Where(b => b.Flags.HasFlag(BlockTypeFlags.XRef) || b.Flags.HasFlag(BlockTypeFlags.XRefOverlay))
                .ToList();
            if (xrefs.Count == 0) return issues;

            var unresolved = xrefs.Where(b => !b.Flags.HasFlag(BlockTypeFlags.XRefResolved)).ToList();
            issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "XREF_PRESENT",
                Message = $"图纸包含 {xrefs.Count} 个外部参照（{string.Join("、", xrefs.Select(x => x.Name).Take(5))}）"
            });
            if (unresolved.Count > 0)
                issues.Add(new QualityIssue
                {
                    Level = "warn",
                    Code = "XREF_UNRESOLVED",
                    Message = $"当前 CAD 引用了 {unresolved.Count} 个未提供的外部图纸（{string.Join("、", unresolved.Select(x => x.Name).Take(5))}），场景对象可能不完整"
                });
        }
        catch { /* 块表读取失败不阻断 */ }
        return issues;
    }
}
