using ACadSharp;
using ACadSharp.IO;
using ACadSharp.Types.Units;

namespace RsdpCadParser.Parser;

/// <summary>
/// CAD 文档载入：DWG/DXF 自动识别，读取 INSUNITS 换算为毫米系数。
/// </summary>
public static class CadDocumentLoader
{
    /// <summary>按扩展名载入文档；扩展名缺失时先尝试 DWG 再尝试 DXF。</summary>
    public static CadDocument Load(byte[] bytes, string? fileName)
    {
        var ext = Path.GetExtension(fileName ?? "").ToLowerInvariant();
        if (ext == ".dxf")
            return ReadDxf(bytes);
        if (ext == ".dwg")
            return ReadDwg(bytes);
        try { return ReadDwg(bytes); }
        catch { return ReadDxf(bytes); }
    }

    private static CadDocument ReadDwg(byte[] bytes)
    {
        using var ms = new MemoryStream(bytes);
        return DwgReader.Read(ms);
    }

    private static CadDocument ReadDxf(byte[] bytes)
    {
        using var ms = new MemoryStream(bytes);
        return DxfReader.Read(ms);
    }

    /// <summary>INSUNITS → 毫米换算系数。Unitless/未知默认 1（按毫米处理）。</summary>
    public static double ScaleToMm(CadDocument doc)
    {
        return doc.Header.InsUnits switch
        {
            UnitsType.Millimeters => 1.0,
            UnitsType.Centimeters => 10.0,
            UnitsType.Decimeters => 100.0,
            UnitsType.Meters => 1000.0,
            UnitsType.Kilometers => 1_000_000.0,
            UnitsType.Inches => 25.4,
            UnitsType.Feet => 304.8,
            UnitsType.Yards => 914.4,
            UnitsType.USSurveyFeet => 304.8006,
            UnitsType.USSurveyInches => 25.40005,
            _ => 1.0
        };
    }
}
