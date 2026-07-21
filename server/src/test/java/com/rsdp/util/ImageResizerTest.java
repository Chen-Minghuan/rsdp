package com.rsdp.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageResizer} 单元测试。
 */
class ImageResizerTest {

    private byte[] createPng(int width, int height, int type) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test
    void resizeToJpeg_shouldScaleLongEdgeToMaxDimension() throws IOException {
        byte[] source = createPng(2048, 1024, BufferedImage.TYPE_INT_RGB);

        byte[] result = ImageResizer.resizeToJpeg(source, 1024, 0.85f);

        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    void resizeToJpeg_shouldKeepAspectRatioForPortrait() throws IOException {
        byte[] source = createPng(500, 2000, BufferedImage.TYPE_INT_RGB);

        byte[] result = ImageResizer.resizeToJpeg(source, 1024, 0.85f);

        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result));
        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(1024);
    }

    @Test
    void resizeToJpeg_shouldReturnOriginalBytesWhenSmallEnough() throws IOException {
        byte[] source = createPng(800, 600, BufferedImage.TYPE_INT_RGB);

        byte[] result = ImageResizer.resizeToJpeg(source, 1024, 0.85f);

        assertThat(result).isSameAs(source);
    }

    @Test
    void resizeToJpeg_shouldHandleTransparentPng() throws IOException {
        byte[] source = createPng(3000, 1500, BufferedImage.TYPE_INT_ARGB);

        byte[] result = ImageResizer.resizeToJpeg(source, 1024, 0.85f);

        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    void resizeToJpeg_shouldThrowWhenBytesAreNotImage() {
        assertThatThrownBy(() -> ImageResizer.resizeToJpeg("not-an-image".getBytes(), 1024, 0.85f))
            .isInstanceOf(IOException.class);
    }

    @Test
    void resizeToJpeg_shouldThrowWhenBytesEmpty() {
        assertThatThrownBy(() -> ImageResizer.resizeToJpeg(new byte[0], 1024, 0.85f))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
