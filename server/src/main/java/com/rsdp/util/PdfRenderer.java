package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 页面渲染器。
 */
@Slf4j
public final class PdfRenderer {

    private PdfRenderer() {
    }

    /**
     * 将 PDF 所有页面渲染为图片。
     *
     * @param inputStream PDF 输入流
     * @param dpi         渲染 DPI，建议 150
     * @return 按页码顺序排列的图片列表
     * @throws IOException 渲染失败
     */
    public static List<BufferedImage> renderPages(InputStream inputStream, float dpi) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return renderPages(bytes, dpi);
    }

    /**
     * 将 PDF 所有页面渲染为图片。
     *
     * @param bytes PDF 文件字节
     * @param dpi   渲染 DPI
     * @return 按页码顺序排列的图片列表
     * @throws IOException 渲染失败
     */
    public static List<BufferedImage> renderPages(byte[] bytes, float dpi) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = document.getNumberOfPages();
            List<BufferedImage> images = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                long start = System.currentTimeMillis();
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                images.add(image);
                log.debug("渲染 PDF 第 {} 页完成，耗时 {}ms，尺寸 {}x{}",
                    i + 1, System.currentTimeMillis() - start, image.getWidth(), image.getHeight());
            }
            return images;
        }
    }
}
