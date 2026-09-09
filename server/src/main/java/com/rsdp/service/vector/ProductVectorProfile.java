package com.rsdp.service.vector;

/**
 * 图片向量编码配置（P0 单一配置）。
 *
 * <p>profileId 标识"模型 + 维度 + 预处理版本 + 距离类型"的完整编码口径，
 * <b>不可随意改变含义</b>：任一要素变化必须更换 profileId 并全量重建向量，
 * 同维不同配置不得混算。当前配置 = DashScope multimodal-embedding-v1 +
 * 1024 维 + 现有缩放预处理（长边 1024px JPEG，见 EmbeddingService）+ cosine。</p>
 */
public final class ProductVectorProfile {

    /** 当前编码配置标识 */
    public static final String CURRENT = "mm-emb-v1-1024-cosine";

    /** 向量维度（与 {@code product_image_embedding.embedding vector(1024)} 一致） */
    public static final int DIMENSION = 1024;

    private ProductVectorProfile() {
    }

    /**
     * 写入前校验向量：维度匹配、全部有限数值、非零范数。
     *
     * @param embedding 待校验向量
     * @throws IllegalArgumentException 校验失败
     */
    public static void validate(float[] embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("向量为空");
        }
        if (embedding.length != DIMENSION) {
            throw new IllegalArgumentException(
                "向量维度不匹配: 期望 " + DIMENSION + ", 实际 " + embedding.length);
        }
        double sumSquares = 0.0;
        for (float v : embedding) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("向量包含非有限数值(NaN/Infinity)");
            }
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            throw new IllegalArgumentException("向量范数为零");
        }
    }
}
