using System.Text.Json;
using Microsoft.Extensions.Options;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser.Api;

/// <summary>
/// POST /parse（multipart：file=DWG/DXF 文件，可选 targetId=手动指定的目标场景候选 ID）→ CadParseResult JSON。
/// success=false（parseStatus=FAILED）返回 422；targetId 不在候选中返回 400。
/// </summary>
public static class ParseEndpoint
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public static void MapParseEndpoint(this WebApplication app)
    {
        app.MapPost("/parse", async (HttpContext ctx, IOptions<ParserOptions> opt) =>
        {
            if (!ctx.Request.HasFormContentType)
                return Results.BadRequest(new CadParseResult
                {
                    Success = false,
                    ErrorCode = "BAD_REQUEST",
                    ErrorMessage = "需要 multipart/form-data，字段名 file"
                });

            var form = await ctx.Request.ReadFormAsync(ctx.RequestAborted);
            var file = form.Files.GetFile("file") ?? form.Files.FirstOrDefault();
            if (file == null || file.Length == 0)
                return Results.BadRequest(new CadParseResult
                {
                    Success = false,
                    ErrorCode = "BAD_REQUEST",
                    ErrorMessage = "缺少上传文件（字段名 file）"
                });

            // 可选 multipart 字段 targetId：从候选中手动选择目标场景
            var targetId = form.TryGetValue("targetId", out var tv) && !string.IsNullOrWhiteSpace(tv.FirstOrDefault())
                ? tv.First()!.Trim()
                : null;

            // 可选 multipart 字段 withIsolation=true：输出家具视觉语义分组的 isolation 视图（P1-C）
            var parseOpt = opt.Value;
            if (form.TryGetValue("withIsolation", out var wv)
                && string.Equals(wv.FirstOrDefault(), "true", StringComparison.OrdinalIgnoreCase))
            {
                parseOpt = opt.Value.Clone();
                parseOpt.EmitIsolationImages = true;
            }

            using var ms = new MemoryStream();
            await file.CopyToAsync(ms, ctx.RequestAborted);
            var result = CadParsePipeline.Parse(ms.ToArray(), file.FileName, parseOpt, targetId, ctx.RequestAborted);
            if (result.ErrorCode == "TARGET_NOT_FOUND")
                return Results.Json(result, JsonOpts, statusCode: 400);
            return Results.Json(result, JsonOpts, statusCode: result.Success ? 200 : 422);
        })
        .Accepts<IFormFile>("multipart/form-data")
        .WithName("ParseCad");
    }
}
