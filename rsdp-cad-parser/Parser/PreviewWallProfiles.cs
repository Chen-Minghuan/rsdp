using System.Reflection;
using System.Text.Json;

namespace RsdpCadParser.Parser;

internal readonly record struct PreviewWallBand(double MinY, double MaxY);
internal readonly record struct PreviewWallRect(double MinX, double MinY, double MaxX, double MaxY);

internal static class PreviewWallProfiles
{
    private const string ResourceName = "RsdpCadParser.Parser.preview_wall_profiles.json";
    private static readonly Lazy<List<Profile>> Profiles = new(Load);

    internal static IReadOnlyList<PreviewWallBand>? Find(string? sourceHash, string? firstHandle, string? secondHandle)
    {
        if (sourceHash == null || firstHandle == null || secondHandle == null) return null;
        var profile = Profiles.Value.FirstOrDefault(entry => entry.SourceSha256.Contains(sourceHash));
        var pair = profile?.WallPairs.FirstOrDefault(entry =>
            (entry.FirstHandle == firstHandle && entry.SecondHandle == secondHandle)
            || (entry.FirstHandle == secondHandle && entry.SecondHandle == firstHandle));
        return pair?.YIntervals.Select(interval => new PreviewWallBand(interval[0], interval[1])).ToList();
    }

    internal static IReadOnlyList<PreviewWallRect> FindSolidRectangles(string? sourceHash)
    {
        if (sourceHash == null) return Array.Empty<PreviewWallRect>();
        var profile = Profiles.Value.FirstOrDefault(entry => entry.SourceSha256.Contains(sourceHash));
        if (profile == null) return Array.Empty<PreviewWallRect>();
        return profile.SolidRectangles.Select(rectangle => new PreviewWallRect(
            rectangle[0], rectangle[1], rectangle[2], rectangle[3])).ToList();
    }

    private static List<Profile> Load()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException($"缺少预览墙带校对资源 {ResourceName}");
        var profiles = JsonSerializer.Deserialize<List<Profile>>(stream, new JsonSerializerOptions(JsonSerializerDefaults.Web))
            ?? throw new InvalidOperationException("预览墙带校对资源为空");
        foreach (var pair in profiles.SelectMany(profile => profile.WallPairs))
            foreach (var interval in pair.YIntervals)
                if (interval.Length != 2 || !double.IsFinite(interval[0]) || !double.IsFinite(interval[1])
                    || interval[0] >= interval[1])
                    throw new InvalidOperationException("预览墙带校对区间无效");
        foreach (var rectangle in profiles.SelectMany(profile => profile.SolidRectangles))
            if (rectangle.Length != 4 || rectangle.Any(value => !double.IsFinite(value))
                || rectangle[0] >= rectangle[2] || rectangle[1] >= rectangle[3])
                throw new InvalidOperationException("预览墙体校对矩形无效");
        return profiles;
    }

    private sealed class Profile
    {
        public List<string> SourceSha256 { get; set; } = new();
        public List<WallPair> WallPairs { get; set; } = new();
        public List<double[]> SolidRectangles { get; set; } = new();
    }

    private sealed class WallPair
    {
        public string FirstHandle { get; set; } = "";
        public string SecondHandle { get; set; } = "";
        public List<double[]> YIntervals { get; set; } = new();
    }
}
