using System.Text.RegularExpressions;

namespace RsdpCadParser.Parser;

/// <summary>
/// MTEXT 格式码清洗：去掉 {\fFont|b0|i0|c134|p49;\C3;文字} 之类的格式组，
/// 保留组内文字。ACadSharp 的 MText.PlainText 已做大部分工作，这里做兜底。
/// </summary>
public static class MTextCleaner
{
    private static readonly Regex InlineCode = new(@"\\[A-Za-z][^;\\{}]*;", RegexOptions.Compiled);
    private static readonly Regex EscapedChar = new(@"\\([\\{}])", RegexOptions.Compiled);
    private static readonly Regex Group = new(@"\{[^{}]*\}", RegexOptions.Compiled);

    public static string Clean(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var s = raw;
        // 格式组：{\f...;\C3;客厅} → 客厅（取最后一个 ';' 之后的内容，反复处理嵌套）
        while (true)
        {
            var t = Group.Replace(s, m =>
            {
                var inner = m.Value[1..^1];
                var idx = inner.LastIndexOf(';');
                return idx >= 0 ? inner[(idx + 1)..] : inner;
            });
            if (t == s) break;
            s = t;
        }
        s = s.Replace("\\P", " ").Replace("\\p", " ");
        s = InlineCode.Replace(s, "");
        s = EscapedChar.Replace(s, "$1");
        s = s.Replace("{", "").Replace("}", "");
        s = s.Replace("%%c", "φ").Replace("%%d", "°").Replace("%%p", "±");
        return s.Trim();
    }
}
