using RsdpCadParser.Api;
using RsdpCadParser.Parser;

var defaultGolden = Environment.GetEnvironmentVariable("CAD_GOLDEN_DWG")
    ?? Path.Combine("..", "data", "uploads", "户型图.dwg");

// 诊断模式（开发调参用）：
//   --probe [file] [closeGapMm]        全链路诊断 + 标注匹配明细
//   --probe-deep [file]                聚类桥接/各簇 HATCH 明细
//   --probe-raw [file]                 HATCH 原始边界 + 标签坐标 + 图层构成
//   --probe-region [file]              标注区域内实体构成
//   --probe-copies [file] [closeGapMm] 逐户型副本墙体与房间产出
//   --probe-lines [file] [layerRe] [snapMm] [cells.png]  线网多边形化实验
//   --probe-merge [file] [snapMm] [cells.png]            弱边界合并实验
//   --probe-render [file] [out.png] [layerRe] [minX minY maxX maxY(米)]  区域渲染
//   --probe-preview [file] [out.png]    正式解析流水线生成的同坐标系规范预览
//   --probe-texts [file]               铺贴/标注图层文字 dump
string Mode(string name) => args.Length > 0 && args[0] == name ? name : "";
string Arg(int i, string def) => args.Length > i ? args[i] : def;
double Num(int i, double def) => args.Length > i && double.TryParse(args[i], out var v) ? v : def;
double? NumOrNull(int i) => args.Length > i && double.TryParse(args[i], out var v) ? v : null;

switch (Mode("--probe") is "" ? null : "--probe")
{
    case "--probe":
        RsdpCadParser.Probe.Run(Arg(1, defaultGolden), Num(2, 0));
        return;
}
if (Mode("--probe-deep") != "") { RsdpCadParser.ProbeDeep.Run(Arg(1, defaultGolden)); return; }
if (Mode("--probe-raw") != "") { RsdpCadParser.ProbeRaw.Run(Arg(1, defaultGolden)); return; }
if (Mode("--probe-region") != "") { RsdpCadParser.ProbeRegion.Run(Arg(1, defaultGolden)); return; }
if (Mode("--probe-copies") != "") { RsdpCadParser.ProbeCopies.Run(Arg(1, defaultGolden), Num(2, 0)); return; }
if (Mode("--probe-lines") != "") { RsdpCadParser.ProbeLines.Run(Arg(1, defaultGolden), Arg(2, "^0$"), Num(3, 5), args.Length > 4 ? args[4] : null); return; }
if (Mode("--probe-merge") != "") { RsdpCadParser.ProbeMerge.Run(Arg(1, defaultGolden), Num(2, 20), args.Length > 3 ? args[3] : null); return; }
if (Mode("--probe-merge2") != "") { RsdpCadParser.ProbeMerge2.Run(Arg(1, defaultGolden), Num(2, 20), args.Length > 3 ? args[3] : null); return; }
if (Mode("--probe-grid") != "") { RsdpCadParser.ProbeGridFilter.Run(Arg(1, defaultGolden), Num(2, 20), args.Length > 3 ? args[3] : null); return; }
if (Mode("--probe-tiles") != "") { RsdpCadParser.ProbeTiles.Run(Arg(1, defaultGolden), Num(2, 20), args.Length > 3 ? args[3] : null); return; }
if (Mode("--probe-merge3") != "") { RsdpCadParser.ProbeMerge3.Run(Arg(1, defaultGolden), Num(2, 20), args.Length > 3 ? args[3] : null); return; }
if (Mode("--probe-render") != "") { RsdpCadParser.ProbeRender.Run(Arg(1, defaultGolden), Arg(2, "probe-render.png"), args.Length > 3 ? args[3] : null, NumOrNull(4), NumOrNull(5), NumOrNull(6), NumOrNull(7)); return; }
if (Mode("--probe-preview") != "")
{
    var input = Arg(1, defaultGolden);
    var output = Arg(2, "probe-preview.png");
    var parsed = CadParsePipeline.Parse(File.ReadAllBytes(input), Path.GetFileName(input));
    if (!parsed.Success || parsed.Preview?.PngBase64 == null)
        throw new InvalidOperationException(parsed.ErrorMessage ?? "正式解析流水线未生成规范预览");
    File.WriteAllBytes(output, Convert.FromBase64String(parsed.Preview.PngBase64));
    Console.WriteLine($"已输出规范预览 {output}（{parsed.Preview.Width}×{parsed.Preview.Height}px）");
    return;
}
if (Mode("--probe-texts") != "") { RsdpCadParser.ProbeTexts.Run(Arg(1, defaultGolden)); return; }

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = 64L * 1024 * 1024);
var port = Environment.GetEnvironmentVariable("CAD_PARSER_PORT") ?? "8090";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
builder.Services.Configure<ParserOptions>(builder.Configuration.GetSection("Parser"));

var app = builder.Build();
app.MapParseEndpoint();
app.Run();
