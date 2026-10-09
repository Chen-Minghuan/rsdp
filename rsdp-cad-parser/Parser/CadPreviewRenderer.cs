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
        => Render(entities, bounds, MaxSide);

    /// <summary>按输入文件指纹应用已核对的预览墙带区间。</summary>
    public static PreviewDto Render(IReadOnlyList<FlatEntity> entities, BoundsDto bounds, string sourceHash)
        => Render(entities, bounds, MaxSide, sourceHash);

    /// <summary>自定义最大边长的渲染（家具 isolation 小图用）。</summary>
    public static PreviewDto Render(IReadOnlyList<FlatEntity> entities, BoundsDto bounds, int maxSide, string? sourceHash = null)
    {
        double spanX = bounds.MaxX - bounds.MinX;
        double spanY = bounds.MaxY - bounds.MinY;
        if (spanX <= 0 || spanY <= 0)
            throw new ArgumentException("预览坐标范围无效");

        double ratio = maxSide / Math.Max(spanX, spanY);
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

        // 实心墙身按奇偶规则填充，保留内部孔洞，避免只画细边线而看不见墙厚。
        void Fill(IReadOnlyList<List<Coordinate>> rings, byte value)
        {
            var valid = rings.Where(r => r.Count >= 3).ToList();
            if (valid.Count == 0) return;
            int y0 = Math.Max(0, valid.SelectMany(r => r).Min(p => Py(p.Y)));
            int y1 = Math.Min(height - 1, valid.SelectMany(r => r).Max(p => Py(p.Y)));
            for (int y = y0; y <= y1; y++)
            {
                var intersections = new List<double>();
                double scan = y + 0.5;
                foreach (var ring in valid)
                    for (int i = 0; i < ring.Count; i++)
                    {
                        var a = ring[i]; var b = ring[(i + 1) % ring.Count];
                        double ay = Py(a.Y), by = Py(b.Y);
                        if ((ay > scan) == (by > scan)) continue;
                        intersections.Add(Px(a.X) + (scan - ay) / (by - ay) * (Px(b.X) - Px(a.X)));
                    }
                intersections.Sort();
                for (int i = 0; i + 1 < intersections.Count; i += 2)
                    for (int x = Math.Max(0, (int)Math.Ceiling(intersections[i]));
                         x <= Math.Min(width - 1, (int)Math.Floor(intersections[i + 1])); x++)
                        Plot(x, y, value);
            }
        }

        // 仅用于底图：原建筑/新建墙体图层的双边线配对成墙带，不进入房间面积事实。
        var windowVoids = entities.Where(e => (e.Layer.Contains("窗") && !e.Layer.Contains("窗帘"))
                || e.Layer.Contains("window", StringComparison.OrdinalIgnoreCase))
            .Select(e => e.BBox).Where(bb => bb.Width > 300 || bb.Height > 300).ToList();
        var wallSegments = new List<(Coordinate A, Coordinate B, string Layer, string? Handle)>();
        foreach (var e in entities.Where(e => e.Layer.Contains("墙体") && e.Points?.Count >= 2))
        {
            for (int i = 1; i < e.Points!.Count; i++)
                if (e.Points[i - 1].Distance(e.Points[i]) >= 300)
                    wallSegments.Add((e.Points[i - 1], e.Points[i], e.Layer, e.SourceHandle));
            // CAD 闭合多段线的末点通常不重复首点；闭合边也是实际墙边。
            if (e.Closed && e.Points[^1].Distance(e.Points[0]) >= 300)
                wallSegments.Add((e.Points[^1], e.Points[0], e.Layer, e.SourceHandle));
        }
        IEnumerable<(double X, double MinY, double MaxY)> VerticalEdges(Func<FlatEntity, bool> keep)
        {
            foreach (var entity in entities.Where(e => keep(e) && e.Points?.Count >= 2))
                for (int index = 1; index < entity.Points!.Count; index++)
                {
                    var first = entity.Points[index - 1];
                    var last = entity.Points[index];
                    if (Math.Abs(first.X - last.X) > 1 || Math.Abs(first.Y - last.Y) < 300) continue;
                    yield return (first.X, Math.Min(first.Y, last.Y), Math.Max(first.Y, last.Y));
                }
        }
        var finishEdges = VerticalEdges(e => e.Layer.Contains("完成面")).ToList();
        var outlineEdges = VerticalEdges(e => e.Layer == "0" && e.InsertPath == null).ToList();
        for (int i = 0; i < wallSegments.Count; i++)
        {
            var a = wallSegments[i]; double len = a.A.Distance(a.B);
            double ux = (a.B.X-a.A.X)/len, uy = (a.B.Y-a.A.Y)/len;
            for (int j = i+1; j < wallSegments.Count; j++)
            {
                var b = wallSegments[j]; double bl = b.A.Distance(b.B);
                double cross = Math.Abs(ux*(b.B.Y-b.A.Y)/bl-uy*(b.B.X-b.A.X)/bl);
                if (cross > 0.01) continue;
                double offset = ux*(b.A.Y-a.A.Y)-uy*(b.A.X-a.A.X);
                if (Math.Abs(offset) < 60 || Math.Abs(offset) > 350) continue;
                double t0 = ux*(b.A.X-a.A.X)+uy*(b.A.Y-a.A.Y);
                double t1 = ux*(b.B.X-a.A.X)+uy*(b.B.Y-a.A.Y);
                double start = Math.Max(0, Math.Min(t0,t1)), end = Math.Min(len, Math.Max(t0,t1));
                if (end-start < 300) continue;
                if (a.Layer.Contains("原建筑墙体") && b.Layer.Contains("原建筑墙体")
                    && Math.Abs(ux) < 0.01 && len >= 1200 && Math.Abs(offset) >= 150)
                {
                    double upperY = Math.Max(a.A.Y + uy*start, a.A.Y + uy*end);
                    foreach (var (faceX, outerX) in new[] { (a.A.X, b.A.X), (b.A.X, a.A.X) })
                    {
                        var continuation = finishEdges
                            .Where(face => Math.Abs(face.X - faceX) <= 80
                                && Math.Abs(face.MinY - upperY) <= 150 && face.MaxY >= upperY + 300)
                            .SelectMany(face => outlineEdges.Where(outline => Math.Abs(outline.X - outerX) <= 100
                                && outline.MinY <= face.MaxY - 100 && outline.MaxY > face.MaxY + 100
                                && outline.MaxY <= upperY + 2200)
                                .Select(outline => outline.MaxY))
                            .DefaultIfEmpty(upperY).Max();
                        if (continuation <= upperY) continue;
                        double projected = uy*(continuation-a.A.Y);
                        start = Math.Min(start, projected);
                        end = Math.Max(end, projected);
                        break;
                    }
                }
                Coordinate P(double t, double d) => new(a.A.X+ux*t-uy*d, a.A.Y+uy*t+ux*d);
                double Project(double x, double y) => ux*(x-a.A.X)+uy*(y-a.A.Y);
                var reviewed = PreviewWallProfiles.Find(sourceHash, a.Handle, b.Handle);
                var ranges = new List<(double Start, double End)>();
                if (reviewed == null) ranges.Add((start, end));
                else if (Math.Abs(ux) < 0.01)
                    foreach (var band in reviewed)
                    {
                        double first = uy*(band.MinY-a.A.Y);
                        double last = uy*(band.MaxY-a.A.Y);
                        ranges.Add((Math.Max(start, Math.Min(first, last)), Math.Min(end, Math.Max(first, last))));
                    }
                foreach (var range in ranges.Where(range => range.End - range.Start >= 1))
                {
                    var segmentBox = new Envelope(P(range.Start,0), P(range.End,offset));
                    var blocked = windowVoids.Where(bb => bb.Intersects(segmentBox))
                        .Select(bb => new[] { Project(bb.MinX, bb.MinY), Project(bb.MinX, bb.MaxY),
                            Project(bb.MaxX, bb.MinY), Project(bb.MaxX, bb.MaxY) })
                        .Select(values => (Start: Math.Max(range.Start, values.Min()), End: Math.Min(range.End, values.Max())))
                        .Where(interval => interval.End > interval.Start)
                        .OrderBy(interval => interval.Start);
                    double cursor = range.Start;
                    foreach (var interval in blocked)
                    {
                        PaintBand(cursor, interval.Start);
                        cursor = Math.Max(cursor, interval.End);
                    }
                    PaintBand(cursor, range.End);
                }

                void PaintBand(double from, double to)
                {
                    if (to - from < 1) return;
                    Fill(new List<List<Coordinate>> { new() { P(from,0), P(to,0), P(to,offset), P(from,offset), P(from,0) } }, 60);
                }
            }
        }

        foreach (var rectangle in PreviewWallProfiles.FindSolidRectangles(sourceHash))
            Fill(new List<List<Coordinate>> { new()
            {
                new(rectangle.MinX, rectangle.MinY), new(rectangle.MaxX, rectangle.MinY),
                new(rectangle.MaxX, rectangle.MaxY), new(rectangle.MinX, rectangle.MaxY)
            } }, 60);

        var viewport = new Envelope(bounds.MinX, bounds.MaxX, bounds.MinY, bounds.MaxY);
        foreach (var entity in entities.Where(e => e.BBox.Intersects(viewport)))
        {
            byte shade = Shade(entity);
            if ((entity.Kind == FlatKind.Line || entity.Kind == FlatKind.Polyline) && entity.Points != null)
                Poly(entity.Points, shade, entity.Closed);
            else if (entity.Kind == FlatKind.Hatch && entity.Rings != null)
            {
                if (entity.HatchIsSolid || entity.HatchPattern is "ANSI31" or "AR-CONC") Fill(entity.Rings, shade);
                foreach (var ring in entity.Rings) Poly(ring, shade, true);
            }
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
