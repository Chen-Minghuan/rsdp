package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageCropper} 单元测试。
 */
class ImageCropperTest {

    @Test
    void cropToJpeg_shouldCropCenterRegion() throws IOException {
        BufferedImage source = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ProductBoundingBox bbox = new ProductBoundingBox(0.25, 0.25, 0.5, 0.5);

        byte[] bytes = ImageCropper.cropToJpeg(source, bbox, 0.9f);

        assertThat(bytes).isNotNull().isNotEmpty();
    }

    @Test
    void cropToJpeg_shouldRejectInvalidBbox() {
        BufferedImage source = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ProductBoundingBox bbox = new ProductBoundingBox(0.5, 0.5, 0.6, 0.6);

        assertThatThrownBy(() -> ImageCropper.cropToJpeg(source, bbox, 0.9f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cropToJpeg_shouldRejectNegativeCoordinates() {
        BufferedImage source = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ProductBoundingBox bbox = new ProductBoundingBox(-0.1, 0.0, 0.5, 0.5);

        assertThatThrownBy(() -> ImageCropper.cropToJpeg(source, bbox, 0.9f))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
