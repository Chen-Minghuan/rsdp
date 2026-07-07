package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片裁剪工具。
 */
@Slf4j
public final class ImageCropper {

    private ImageCropper() {
    }

    /**
     * 根据相对 bbox 裁剪图片，输出 JPEG 字节。
     *
     * @param source        源图片
     * @param bbox          相对坐标框
     * @param outputQuality JPEG 质量，0.0 ~ 1.0
     * @return 裁剪后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropToJpeg(BufferedImage source, ProductBoundingBox bbox, float outputQuality) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("源图片不能为空");
        }
        if (bbox == null || !bbox.isValid()) {
            throw new IllegalArgumentException("裁剪框不合法");
        }

        int x = (int) Math.round(bbox.getX() * source.getWidth());
        int y = (int) Math.round(bbox.getY() * source.getHeight());
        int width = (int) Math.round(bbox.getWidth() * source.getWidth());
        int height = (int) Math.round(bbox.getHeight() * source.getHeight());

        // 边界保护
        x = Math.max(0, x);
        y = Math.max(0, y);
        width = Math.min(width, source.getWidth() - x);
        height = Math.min(height, source.getHeight() - y);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("裁剪后尺寸无效");
        }

        BufferedImage cropped = source.getSubimage(x, y, width, height);
        return encodeJpeg(cropped, outputQuality);
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
