package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * 图片像素尺寸读取（4.5：image_assets.width/height 主路径补写）。
 *
 * <p>字节已在内存中的登记点调用，解码失败（非位图/损坏）返回 null 不阻断主流程。</p>
 */
@Slf4j
public final class ImageDimensions {

    private ImageDimensions() {
    }

    /**
     * 读取图片像素尺寸。
     *
     * @param bytes 图片字节
     * @return [width, height]；解码失败返回 null
     */
    public static int[] read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            log.debug("图片尺寸解码失败（按 null 处理）: {}", e.getMessage());
            return null;
        }
    }
}
