using NetTopologySuite.Geometries;

namespace RsdpCadParser.Parser;

/// <summary>
/// 2D 仿射变换（块局部坐标 → 世界毫米坐标）：
/// x' = A*x + B*y + Tx；y' = C*x + D*y + Ty。
/// </summary>
public readonly struct Affine2D
{
    public readonly double A, B, C, D, Tx, Ty;

    public Affine2D(double a, double b, double c, double d, double tx, double ty)
    {
        A = a; B = b; C = c; D = d; Tx = tx; Ty = ty;
    }

    public static Affine2D Identity => new(1, 0, 0, 1, 0, 0);

    /// <summary>由 INSERT 参数构造：p' = InsertPoint + R(rotation) · S(sx,sy) · (p − basePoint)。rotation 为弧度。</summary>
    public static Affine2D FromInsert(double insertX, double insertY, double rotationRad, double scaleX, double scaleY, double baseX, double baseY)
    {
        double cos = Math.Cos(rotationRad), sin = Math.Sin(rotationRad);
        double a = scaleX * cos, b = -scaleY * sin;
        double c = scaleX * sin, d = scaleY * cos;
        // 先平移 −basePoint，再旋转缩放，最后平移到插入点
        double tx = insertX - (a * baseX + b * baseY);
        double ty = insertY - (c * baseX + d * baseY);
        return new Affine2D(a, b, c, d, tx, ty);
    }

    /// <summary>this ∘ inner（先应用 inner，再应用 this）。</summary>
    public Affine2D Compose(Affine2D inner) => new(
        A * inner.A + B * inner.C,
        A * inner.B + B * inner.D,
        C * inner.A + D * inner.C,
        C * inner.B + D * inner.D,
        A * inner.Tx + B * inner.Ty + Tx,
        C * inner.Tx + D * inner.Ty + Ty);

    public Coordinate Apply(Coordinate p) => new(A * p.X + B * p.Y + Tx, C * p.X + D * p.Y + Ty);

    /// <summary>逆变换（用于世界坐标 → 块局部坐标，求局部轴尺寸）。</summary>
    public Affine2D Inverse()
    {
        double det = A * D - B * C;
        if (Math.Abs(det) < 1e-12) return Identity;
        double a = D / det, b = -B / det, c = -C / det, d = A / det;
        return new Affine2D(a, b, c, d, -(a * Tx + b * Ty), -(c * Tx + d * Ty));
    }

    /// <summary>累积旋转角（degree，[0,360)）。镜像/非等比缩放下为主轴方向近似。</summary>
    public double RotationDeg()
    {
        double deg = Math.Atan2(C, A) * 180.0 / Math.PI;
        return ((deg % 360) + 360) % 360;
    }

    public double[] ToArray() => new[] { A, B, C, D, Tx, Ty };
}
