package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * PDF 页面渲染器。
 */
@Slf4j
public final class PdfRenderer {

    private PdfRenderer() {
    }

    /**
     * 将 PDF 首页渲染为 PNG 图片字节（户型图 PDF 支持，v3.0 §8 P2，KISS：仅渲染第 1 页，
     * 渲染后进入既有图片识别管线，PDF 原文件不留存）。
     *
     * @param bytes PDF 文件字节
     * @param dpi   渲染 DPI（沿用既有默认 200）
     * @return 首页 PNG 图片字节
     * @throws BusinessException PDF 已加密 / 非法或损坏 / 无页面（400 中文可读提示）
     */
    public static byte[] renderFirstPageAsPng(byte[] bytes, float dpi) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() == 0) {
                throw new BusinessException("PDF 文件没有任何页面");
            }
            long start = System.currentTimeMillis();
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, dpi);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            log.debug("渲染 PDF 首页完成，耗时 {}ms，尺寸 {}x{}",
                System.currentTimeMillis() - start, image.getWidth(), image.getHeight());
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw new BusinessException("PDF 文件已加密，请先解除密码保护后重新上传");
        } catch (IOException e) {
            log.warn("PDF 首页渲染失败", e);
            throw new BusinessException("PDF 文件无法解析或已损坏，请上传有效的 PDF 户型图");
        }
    }

    /**
     * 将 PDF 所有页面渲染为图片。
     *
     * @param inputStream PDF 输入流
     * @param dpi         渲染 DPI，建议 150~200（越高裁剪图越清晰，内存占用随平方增长）
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
                images.add(renderPage(renderer, i, dpi));
            }
            return images;
        }
    }

    /**
     * 渲染 PDF 单页为图片（阶段 3.1 逐页流式处理：调用方复用同一 PDDocument 逐页渲染，
     * 处理完一页即可释放该页位图，避免全量页位图驻留堆内存）。
     *
     * @param document  已打开的 PDF 文档（调用方负责关闭）
     * @param pageIndex 页码（0 起）
     * @param dpi       渲染 DPI
     * @return 该页位图
     * @throws IOException 渲染失败
     */
    public static BufferedImage renderPage(PDDocument document, int pageIndex, float dpi) throws IOException {
        return renderPage(new PDFRenderer(document), pageIndex, dpi);
    }

    private static BufferedImage renderPage(PDFRenderer renderer, int pageIndex, float dpi) throws IOException {
        long start = System.currentTimeMillis();
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi);
        log.debug("渲染 PDF 第 {} 页完成，耗时 {}ms，尺寸 {}x{}",
            pageIndex + 1, System.currentTimeMillis() - start, image.getWidth(), image.getHeight());
        return image;
    }
}
