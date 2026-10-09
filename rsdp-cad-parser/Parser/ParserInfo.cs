using System.Security.Cryptography;
using System.Text;

namespace RsdpCadParser.Parser;

/// <summary>P0 契约版本常量（sceneVersion / parserVersion）。</summary>
public static class ParserInfo
{
    public const string SceneVersion = "1.0";
    public const string ParserVersion = "1.3.0-p1";

    /// <summary>targetId = "SCENE-" + sha256(sourceSpace|round(minX)|round(minY)|round(maxX)|round(maxY)) 前 8 位 hex。</summary>
    public static string TargetId(string sourceSpace, double minX, double minY, double maxX, double maxY)
        => "SCENE-" + ShortHash($"{sourceSpace}|{Math.Round(minX)}|{Math.Round(minY)}|{Math.Round(maxX)}|{Math.Round(maxY)}");

    /// <summary>furnitureId = "F-" + sha256(insertPath 句柄按 ":" 连接) 前 8 位 hex。</summary>
    public static string FurnitureId(IEnumerable<string> insertPath)
        => "F-" + ShortHash(string.Join(":", insertPath));

    /// <summary>sha256 前 8 位 hex（小写），纯函数、可重现。</summary>
    public static string ShortHash(string input)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(input));
        var sb = new StringBuilder(8);
        for (int i = 0; i < 4; i++) sb.Append(hash[i].ToString("x2"));
        return sb.ToString();
    }
}
