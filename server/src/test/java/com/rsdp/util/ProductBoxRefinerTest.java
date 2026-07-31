package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductBoxRefiner} 单元测试。
 */
class ProductBoxRefinerTest {

    @Test
    void refine_shouldReturnEmptyForNullOrEmpty() {
        assertThat(ProductBoxRefiner.refine(null, 1000, 1000)).isEmpty();
        assertThat(ProductBoxRefiner.refine(List.of(), 1000, 1000)).isEmpty();
    }

    @Test
    void refine_shouldKeepValidBox() {
        List<ProductBoundingBox> result = ProductBoxRefiner.refine(
            List.of(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4)), 1000, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWidth()).isEqualTo(0.4);
    }

    @Test
    void refine_shouldClampOutOfRangeCoordinates() {
        // x/y 越界为负、宽高超出页面 → 钳制回 [0,1]
        List<ProductBoundingBox> result = ProductBoxRefiner.refine(
            List.of(new ProductBoundingBox(-0.2, 0.5, 1.5, 0.4)), 1000, 1000);

        assertThat(result).hasSize(1);
        ProductBoundingBox clamped = result.get(0);
        assertThat(clamped.getX()).isEqualTo(0.0);
        assertThat(clamped.getY()).isEqualTo(0.5);
        assertThat(clamped.getWidth()).isEqualTo(1.0);
        assertThat(clamped.getHeight()).isEqualTo(0.4);
    }

    @Test
    void refine_shouldDropTooSmallAreaBox() {
        // 面积 0.01 * 0.01 = 0.0001 < 1% 阈值
        List<ProductBoundingBox> result = ProductBoxRefiner.refine(
            List.of(new ProductBoundingBox(0.1, 0.1, 0.01, 0.01)), 1000, 1000);

        assertThat(result).isEmpty();
    }

    @Test
    void refine_shouldDropTooSmallPixelBox() {
        // 面积占比足够（10% x 10%），但页面很小导致像素边长不足 32px
        List<ProductBoundingBox> result = ProductBoxRefiner.refine(
            List.of(new ProductBoundingBox(0.1, 0.1, 0.1, 0.1)), 100, 100);

        assertThat(result).isEmpty();
    }

    @Test
    void refine_shouldDeduplicateOverlappingBoxes() {
        ProductBoundingBox big = new ProductBoundingBox(0.1, 0.1, 0.5, 0.5);
        // 与 big 几乎完全重叠（IoU > 0.8）→ 应被去重
        ProductBoundingBox duplicate = new ProductBoundingBox(0.12, 0.12, 0.48, 0.48);
        // 独立框 → 保留
        ProductBoundingBox separate = new ProductBoundingBox(0.7, 0.7, 0.2, 0.2);

        List<ProductBoundingBox> result = ProductBoxRefiner.refine(
            List.of(duplicate, big, separate), 1000, 1000);

        assertThat(result).hasSize(2);
        // 面积降序，大框在前
        assertThat(result.get(0).getWidth()).isEqualTo(0.5);
    }

    @Test
    void refine_shouldKeepPartiallyOverlappingBoxes() {
        // IoU 远低于 0.8 的两个相邻产品框都应保留
        ProductBoundingBox left = new ProductBoundingBox(0.05, 0.2, 0.4, 0.5);
        ProductBoundingBox right = new ProductBoundingBox(0.5, 0.2, 0.4, 0.5);

        List<ProductBoundingBox> result = ProductBoxRefiner.refine(List.of(left, right), 1000, 1000);

        assertThat(result).hasSize(2);
    }
}
