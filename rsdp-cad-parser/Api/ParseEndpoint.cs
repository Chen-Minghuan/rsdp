using System.Text.Json;
using Microsoft.Extensions.Options;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser.Api;

/// <summary>POST /parse（multipart file，DWG/DXF）→ CadParseResult JSON。</summary>
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

            var form = await ctx.Request.ReadFormAsync();
            var file = form.Files.GetFile("file") ?? form.Files.FirstOrDefault();
            if (file == null || file.Length == 0)
                return Results.BadRequest(new CadParseResult
                {
                    Success = false,
                    ErrorCode = "BAD_REQUEST",
                    ErrorMessage = "缺少上传文件（字段名 file）"
                });

            using var ms = new MemoryStream();
            await file.CopyToAsync(ms);
            var result = CadParsePipeline.Parse(ms.ToArray(), file.FileName, opt.Value);
            return Results.Json(result, JsonOpts, statusCode: result.Success ? 200 : 422);
        })
        .Accepts<IFormFile>("multipart/form-data")
        .WithName("ParseCad");
    }
}
