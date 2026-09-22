using System.IO.Compression;
using System.Text;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 将目标户型实体渲染为白底灰度 PNG。像素画布严格映射到传入的 CAD bounds，
/// 因此该预览可直接作为房间多边形叠加的规范底图。
/// </summary>
public static class CadPreviewRenderer
{
    private const int MaxSide = 1400;

    public static PreviewDto Render(IReadOnlyList<FlatEntity> entities, BoundsDto bounds)
    {
        double spanX = bounds.MaxX - bounds.MinX;
        double spanY = bounds.MaxY - bounds.MinY;
        if (spanX <= 0 || spanY <= 0)
            throw new ArgumentException("预览坐标范围无效");

        double ratio = MaxSide / Math.Max(spanX, spanY);
        int width = Math.Max(1, (int)Math.Round(spanX * ratio));
        int height = Math.Max(1, (int)Math.Round(spanY * ratio));
        byte[] pixels = new byte[width * height];
        Array.Fill(pixels, (byte)255);

        int Px(double x) => (int)Math.Round((x - bounds.MinX) / spanX * (width - 1));
        int Py(double y) => (int)Math.Round((bounds.MaxY - y) / spanY * (height - 1));
        void Plot(int x, int y, byte value)
        {
            if (x >= 0 && x < width && y >= 0 && y < height && pixels[y * width + x] > value)
                pixels[y * width + x] = value;
        }
        void Line(Coordinate a, Coordinate b, byte value)
        {
            int x0 = Px(a.X), y0 = Py(a.Y), x1 = Px(b.X), y1 = Py(b.Y);
            int dx = Math.Abs(x1 - x0), dy = Math.Abs(y1 - y0);
            int steps = Math.Max(dx, dy) * 2 + 1;
            for (int i = 0; i <= steps; i++)
                Plot(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, value);
        }
        void Poly(IReadOnlyList<Coordinate> points, byte value, bool close)
        {
            for (int i = 0; i + 1 < points.Count; i++) Line(points[i], points[i + 1], value);
            if (close && points.Count > 2) Line(points[^1], points[0], value);
        }

        var viewport = new Envelope(bounds.MinX, bounds.MaxX, bounds.MinY, bounds.MaxY);
        foreach (var entity in entities.Where(e => e.BBox.Intersects(viewport)))
        {
            byte shade = Shade(entity);
            if ((entity.Kind == FlatKind.Line || entity.Kind == FlatKind.Polyline) && entity.Points != null)
                Poly(entity.Points, shade, entity.Closed);
            else if (entity.Kind == FlatKind.Hatch && entity.Rings != null)
                foreach (var ring in entity.Rings) Poly(ring, shade, true);
            else if (entity.Kind == FlatKind.Text && entity.TextPos != null)
            {
                int x = Px(entity.TextPos.X), y = Py(entity.TextPos.Y);
                for (int d = -2; d <= 2; d++) { Plot(x + d, y, shade); Plot(x, y + d, shade); }
            }
        }

        return new PreviewDto
        {
            Width = width,
            Height = height,
            Bounds = bounds,
            PngBase64 = Convert.ToBase64String(PngEncode(pixels, width, height))
        };
    }

    private static byte Shade(FlatEntity entity)
    {
        if (entity.Layer == "0") return 25;
        if (entity.Layer.Contains("门")) return 55;
        if (entity.Layer.Contains("墙")) return 35;
        if (entity.Kind == FlatKind.Hatch) return 70;
        if (entity.Kind == FlatKind.Text || entity.Kind == FlatKind.Dimension) return 95;
        if (entity.Layer.Contains("家具") || entity.Layer.Contains("装饰")) return 135;
        return 115;
    }

    /// <summary>编码 8-bit 灰度 PNG，使用 .NET 内置 zlib 压缩。</summary>
    internal static byte[] PngEncode(byte[] gray, int width, int height)
    {
        using var output = new MemoryStream();
        Write(output, new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 });

        using (var header = new MemoryStream())
        {
            WriteUInt32(header, (uint)width);
            WriteUInt32(header, (uint)height);
            Write(header, new byte[] { 8, 0, 0, 0, 0 });
            WriteChunk(output, "IHDR", header.ToArray());
        }

        var raw = new byte[(width + 1) * height];
        for (int y = 0; y < height; y++)
            Array.Copy(gray, y * width, raw, y * (width + 1) + 1, width);
        using (var compressed = new MemoryStream())
        {
            using (var zlib = new ZLibStream(compressed, CompressionLevel.Fastest, true))
                zlib.Write(raw);
            WriteChunk(output, "IDAT", compressed.ToArray());
        }
        WriteChunk(output, "IEND", Array.Empty<byte>());
        return output.ToArray();
    }

    private static void WriteChunk(Stream output, string type, byte[] data)
    {
        WriteUInt32(output, (uint)data.Length);
        byte[] typeBytes = Encoding.ASCII.GetBytes(type);
        Write(output, typeBytes);
        Write(output, data);
        WriteUInt32(output, Crc32(typeBytes.Concat(data).ToArray()));
    }

    private static void WriteUInt32(Stream stream, uint value) => Write(stream,
        new[] { (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value });

    private static void Write(Stream stream, byte[] bytes) => stream.Write(bytes, 0, bytes.Length);

    private static uint Crc32(byte[] data)
    {
        uint crc = 0xFFFFFFFF;
        foreach (byte value in data)
        {
            crc ^= value;
            for (int i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1;
        }
        return crc ^ 0xFFFFFFFF;
    }
}
