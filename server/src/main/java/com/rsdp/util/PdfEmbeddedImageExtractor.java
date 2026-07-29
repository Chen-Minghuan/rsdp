package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 嵌入图片直取器。
 *
 * <p>产品画册 PDF 中的产品图多为嵌入位图，直接抽取可绕过渲染环节，
 * 零失真、天然就是完整产品图，是主图"完整度"的最强保障。</p>
 *
 * <p>通过 {@link PDFStreamEngine} 解析页面内容流，按 CTM 计算图片的
 * 实际绘制尺寸（点），仅保留绘制面积占页面比例达标且像素足够大的图，
 * 过滤 Logo、图标、装饰背景等干扰图片。</p>
 */
@Slf4j
public final class PdfEmbeddedImageExtractor {

    private PdfEmbeddedImageExtractor() {
    }

    /**
     * 抽取 PDF 每页的大面积嵌入图片。
     *
     * @param pdfBytes      PDF 文件字节
     * @param minAreaRatio  图片绘制面积占页面面积的最小比例（如 0.20）
     * @param minPixelEdge  图片原始像素的最小边长（如 200），过滤缩略图
     * @return 页码（0 起）→ 该页大面积嵌入图列表；无合格图片的页不出现在结果中
     * @throws IOException PDF 解析失败
     */
    public static Map<Integer, List<BufferedImage>> extractLargeImages(byte[] pdfBytes,
                                                                       double minAreaRatio,
                                                                       int minPixelEdge) throws IOException {
        Map<Integer, List<BufferedImage>> result = new HashMap<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                LargeImageCollector collector = new LargeImageCollector(page, minAreaRatio, minPixelEdge);
                try {
                    collector.processPage(page);
                } catch (Exception e) {
                    log.warn("抽取第 {} 页嵌入图失败，跳过该页", i + 1, e);
                    continue;
                }
                if (!collector.images.isEmpty()) {
                    result.put(i, collector.images);
                    log.debug("第 {} 页抽取到 {} 张大嵌入图", i + 1, collector.images.size());
                }
            }
        }
        return result;
    }

    /**
     * 单页大图收集器：遍历内容流中的 Do 操作符，按 CTM 计算绘制尺寸。
     */
    private static final class LargeImageCollector extends PDFStreamEngine {

        private final double pageArea;
        private final double minAreaRatio;
        private final int minPixelEdge;
        private final List<BufferedImage> images = new ArrayList<>();
        private final Set<COSBase> seen = new HashSet<>();

        private LargeImageCollector(PDPage page, double minAreaRatio, int minPixelEdge) {
            // PDFStreamEngine 默认构造不注册任何操作符处理器，
            // 必须手动注册图形状态处理器，否则 q/cm 不会生效、CTM 永远是单位矩阵
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Save(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate(this));
            this.pageArea = (double) page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
            this.minAreaRatio = minAreaRatio;
            this.minPixelEdge = minPixelEdge;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (!"Do".equals(operator.getName()) || operands.isEmpty()) {
                super.processOperator(operator, operands);
                return;
            }
            COSBase base = operands.get(0);
            COSName objectName = base instanceof COSObject cosObject
                ? (COSName) cosObject.getObject() : (COSName) base;
            PDXObject xobject = getResources().getXObject(objectName);

            if (xobject instanceof PDImageXObject image) {
                collectIfLarge(image);
            } else if (xobject instanceof PDFormXObject form) {
                showForm(form);
            } else {
                super.processOperator(operator, operands);
            }
        }

        private void collectIfLarge(PDImageXObject image) throws IOException {
            if (image.isStencil() || !seen.add(image.getCOSObject())) {
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            double drawnArea = (double) ctm.getScalingFactorX() * ctm.getScalingFactorY();
            if (pageArea <= 0 || drawnArea / pageArea < minAreaRatio) {
                return;
            }
            if (image.getWidth() < minPixelEdge || image.getHeight() < minPixelEdge) {
                return;
            }
            try {
                images.add(image.getImage());
            } catch (Exception e) {
                log.debug("嵌入图解码失败，跳过（可能为不支持的色彩空间/编码）", e);
            }
        }
    }
}
