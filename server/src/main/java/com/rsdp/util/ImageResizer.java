package com.rsdp.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片缩放工具：将超大图片等比缩放后输出 JPEG，用于减少 Embedding API 的传输体积。
 */
public final class ImageResizer {

    /**
     * Embedding 图片长边上限（像素）。
     */
    public static final int DEFAULT_MAX_DIMENSION = 1024;

    /**
     * 默认 JPEG 输出质量。
     */
    public static final float DEFAULT_JPEG_QUALITY = 0.85f;

    private ImageResizer() {
    }

    /**
     * 长边超过 maxDimension 时等比缩放到 maxDimension 并输出 JPEG；
     * 未超过时原样返回输入字节。
     *
     * @param sourceBytes  源图片字节
     * @param maxDimension 长边上限（像素）
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @return 缩放后的 JPEG 字节，或未超限时返回原始字节
     * @throws IOException 图片解析或编码失败
     */
    public static byte[] resizeToJpeg(byte[] sourceBytes, int maxDimension, float quality) throws IOException {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("源图片不能为空");
        }

        BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (source == null) {
            throw new IOException("无法解析图片内容");
        }

        int width = source.getWidth();
        int height = source.getHeight();
        if (Math.max(width, height) <= maxDimension) {
            return sourceBytes;
        }

        double scale = (double) maxDimension / Math.max(width, height);
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        // JPEG 不支持透明通道，统一转为 RGB 并填充白色背景
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, newWidth, newHeight);
            graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
        } finally {
            graphics.dispose();
        }

        return encodeJpeg(resized, quality);
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("找不到 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.0f, Math.min(1.0f, quality)));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
