using RsdpCadParser.Parser;

namespace RsdpCadParser.Tests;

/// <summary>金样本文件定位：环境变量 CAD_GOLDEN_DWG 优先，否则向上查找 data/uploads/户型图.dwg。</summary>
public static class GoldenFile
{
    public const string DefaultFileName = "户型图.dwg";

    public static string? Locate(string fileName = DefaultFileName)
    {
        // 环境变量 CAD_GOLDEN_DWG 仅对默认文件名生效
        if (fileName == DefaultFileName)
        {
            var env = Environment.GetEnvironmentVariable("CAD_GOLDEN_DWG");
            if (!string.IsNullOrWhiteSpace(env) && File.Exists(env)) return env;
        }

        // 从测试程序集目录向上找 data/uploads/<file>（金样本不入库，测试环境缺文件时跳过）
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "data", "uploads", fileName);
            if (File.Exists(candidate)) return candidate;
            dir = dir.Parent;
        }
        return null;
    }
}
