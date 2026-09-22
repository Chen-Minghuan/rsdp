using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>可视化诊断：目标区域实体渲染为 PNG（极简手写 PNG 编码器，灰度）。</summary>
public static class ProbeRender
{
    public static void Run(string path, string outPng, string? layerFilter = null,
        double? winMinX = null, double? winMinY = null, double? winMaxX = null, double? winMaxY = null)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);
        var opt = new ParserOptions();
        var annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));
        var target = TargetSelector.Select(entities, annotations, opt);
        var region = target.Region
            ?? throw new InvalidOperationException("未找到可渲染的 CAD 目标区域");
        if (winMinX.HasValue)
            region = new Envelope(winMinX.Value * 1000, winMaxX!.Value * 1000, winMinY!.Value * 1000, winMaxY!.Value * 1000);
        var inside = entities.Where(e => e.BBox.Intersects(region)).ToList();

        const int W = 1200, H = 1200;
        var img = new byte[W * H]; // 0=黑
        double sx = W / (region.MaxX - region.MinX), sy = H / (region.MaxY - region.MinY);
        double s = Math.Min(sx, sy) * 0.96;
        double ox = (W - s * (region.MaxX - region.MinX)) / 2, oy = (H - s * (region.MaxY - region.MinY)) / 2;
        int Px(double x) => (int)(ox + (x - region!.MinX) * s);
        int Py(double y) => (int)(H - (oy + (y - region!.MinY) * s)); // y 翻转

        void Plot(int x, int y, byte v)
        {
            if (x >= 0 && x < W && y >= 0 && y < H && img![y * W + x] < v) img[y * W + x] = v;
        }
        void Line(Coordinate a, Coordinate b, byte v)
        {
            int x0 = Px(a.X), y0 = Py(a.Y), x1 = Px(b.X), y1 = Py(b.Y);
            int dx = Math.Abs(x1 - x0), dy = Math.Abs(y1 - y0);
            int steps = Math.Max(dx, dy) * 2 + 1;
            for (int i = 0; i <= steps; i++)
                Plot(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, v);
        }
        void Poly(IReadOnlyList<Coordinate> pts, byte v, bool close)
        {
            for (int i = 0; i + 1 < pts.Count; i++) Line(pts[i], pts[i + 1], v);
            if (close && pts.Count > 2) Line(pts[^1], pts[0], v);
        }
        void Cross(Coordinate p, byte v)
        {
            int x = Px(p.X), y = Py(p.Y);
            for (int d = -8; d <= 8; d++) { Plot(x + d, y, v); Plot(x, y + d, v); }
        }

        byte Shade(string layer) => layer switch
        {
            "0" => 255,
            "地面铺贴图" => 100,
            "DOOR【门】" => 200,
            "天花布置" => 80,
            "家具、装饰轮廓线条" => 60,
            _ => 140
        };

        var layerFilterRe = layerFilter != null ? new System.Text.RegularExpressions.Regex(layerFilter) : null;
        foreach (var e in inside)
        {
            if (layerFilterRe != null && !layerFilterRe.IsMatch(e.Layer)) continue;
            var v = Shade(e.Layer);
            if ((e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline) && e.Points != null)
                Poly(e.Points, v, e.Closed);
            else if (e.Kind == FlatKind.Hatch && e.Rings != null)
                foreach (var r in e.Rings) Poly(r, (byte)Math.Min(255, v + 40), true);
            else if (e.Kind == FlatKind.Text && e.TextPos != null)
            {
                // 文字插入点渲染为小圆点
                int tx = Px(e.TextPos.X), ty = Py(e.TextPos.Y);
                for (int d = -2; d <= 2; d++) { Plot(tx + d, ty, v); Plot(tx, ty + d, v); }
            }
        }
        foreach (var a in annotations)
            if (region.Contains(a.Pos)) Cross(a.Pos, 255);

        File.WriteAllBytes(outPng, PngEncode(img, W, H));
        Console.WriteLine($"已渲染 {outPng}（区域 ({region.MinX / 1000:F1},{region.MinY / 1000:F1})~({region.MaxX / 1000:F1},{region.MaxY / 1000:F1})）");
        Console.WriteLine("灰度：0层=255 门=200 铺贴=100 天花=80 家具=60 其他=140；标注位置=白色十字");
    }

    /// <summary>极简 PNG 编码：8bit 灰度，zlib stored blocks。</summary>
    public static byte[] PngEncode(byte[] gray, int w, int h)
    {
        using var ms = new MemoryStream();
        void U32(uint v) { ms.WriteByte((byte)(v >> 24)); ms.WriteByte((byte)(v >> 16)); ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }
        void Chunk(string type, byte[] data)
        {
            U32((uint)data.Length);
            var tb = System.Text.Encoding.ASCII.GetBytes(type);
            ms.Write(tb, 0, 4);
            ms.Write(data, 0, data.Length);
            var crc = Crc32(tb.Concat(data).ToArray());
            U32(crc);
        }

        // 签名
        ms.Write(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }, 0, 8);
        // IHDR
        using (var ihdr = new MemoryStream())
        {
            void W32(uint v) { ihdr.WriteByte((byte)(v >> 24)); ihdr.WriteByte((byte)(v >> 16)); ihdr.WriteByte((byte)(v >> 8)); ihdr.WriteByte((byte)v); }
            W32((uint)w); W32((uint)h);
            ihdr.WriteByte(8); ihdr.WriteByte(0); ihdr.WriteByte(0); ihdr.WriteByte(0); ihdr.WriteByte(0);
            Chunk("IHDR", ihdr.ToArray());
        }
        // IDAT：raw scanlines (filter 0) → zlib stored
        var raw = new byte[(w + 1) * h];
        for (int y = 0; y < h; y++)
        {
            raw[y * (w + 1)] = 0;
            Array.Copy(gray, y * w, raw, y * (w + 1) + 1, w);
        }
        using (var idat = new MemoryStream())
        {
            idat.WriteByte(0x78); idat.WriteByte(0x01); // zlib header (no compression)
            int pos = 0;
            while (pos < raw.Length)
            {
                int block = Math.Min(65535, raw.Length - pos);
                bool last = pos + block >= raw.Length;
                idat.WriteByte((byte)(last ? 1 : 0));
                idat.WriteByte((byte)block); idat.WriteByte((byte)(block >> 8));
                idat.WriteByte((byte)~block); idat.WriteByte((byte)(~block >> 8));
                idat.Write(raw, pos, block);
                pos += block;
            }
            U32to(idat, Adler32(raw));
            Chunk("IDAT", idat.ToArray());
        }
        Chunk("IEND", Array.Empty<byte>());
        return ms.ToArray();

        static void U32to(Stream s, uint v) { s.WriteByte((byte)(v >> 24)); s.WriteByte((byte)(v >> 16)); s.WriteByte((byte)(v >> 8)); s.WriteByte((byte)v); }
    }

    private static uint Crc32(byte[] data)
    {
        uint crc = 0xFFFFFFFF;
        foreach (var b in data)
        {
            crc ^= b;
            for (int i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1;
        }
        return crc ^ 0xFFFFFFFF;
    }

    private static uint Adler32(byte[] data)
    {
        uint a = 1, b = 0;
        foreach (var x in data) { a = (a + x) % 65521; b = (b + a) % 65521; }
        return (b << 16) | a;
    }
}
