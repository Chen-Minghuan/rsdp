using ACadSharp;
using ACadSharp.Types.Units;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>UnitResolver（设计文档 §7.1）：区分"归一化毫米坐标"与"已确认物理毫米"。</summary>
public static class UnitResolver
{
    public static UnitResolutionDto Resolve(CadDocument doc, double scaleToMm)
    {
        var insUnits = doc.Header.InsUnits;
        bool declared = insUnits is not UnitsType.Unitless;
        return new UnitResolutionDto
        {
            RawUnit = RawUnitName(insUnits),
            DeclaredUnit = declared ? RawUnitName(insUnits) : null,
            CanonicalUnit = "mm",
            ScaleToMm = scaleToMm,
            ScaleSource = declared ? "HEADER" : "ASSUMED",
            ScaleAssumed = !declared,
            Confirmed = declared,
            Confidence = declared ? "high" : "low"
        };
    }

    private static string RawUnitName(UnitsType u) => u switch
    {
        UnitsType.Millimeters => "MILLIMETER",
        UnitsType.Centimeters => "CENTIMETER",
        UnitsType.Decimeters => "DECIMETER",
        UnitsType.Meters => "METER",
        UnitsType.Kilometers => "KILOMETER",
        UnitsType.Inches => "INCH",
        UnitsType.Feet => "FOOT",
        UnitsType.Yards => "YARD",
        UnitsType.USSurveyFeet => "FOOT",
        UnitsType.USSurveyInches => "INCH",
        UnitsType.Unitless => "UNITLESS",
        _ => "OTHER"
    };
}
