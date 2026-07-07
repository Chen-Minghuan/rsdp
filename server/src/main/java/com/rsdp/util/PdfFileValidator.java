package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 文件校验器。
 */
@Slf4j
public final class PdfFileValidator {

    private PdfFileValidator() {
    }

    /**
     * 校验 PDF 文件。
     *
     * @param file        上传文件
     * @param maxSizeBytes 最大允许字节数
     * @param maxPages    最大允许页数
     */
    public static void validate(MultipartFile file, long maxSizeBytes, int maxPages) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传 PDF 文件");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException("PDF 文件大小超过限制");
        }
        if (!isPdf(file)) {
            throw new BusinessException("仅支持 PDF 文件，请检查文件格式");
        }
        int pages = countPages(file);
        if (pages > maxPages) {
            throw new BusinessException("PDF 页数不能超过 " + maxPages + " 页");
        }
        if (pages == 0) {
            throw new BusinessException("PDF 文件没有可读取的页面");
        }
    }

    /**
     * 通过文件魔数判断是否为 PDF。
     */
    public static boolean isPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        try (InputStream in = file.getInputStream()) {
            byte[] header = new byte[4];
            int read = in.read(header);
            if (read < 4) {
                return false;
            }
            return header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F';
        } catch (IOException e) {
            log.warn("读取 PDF 文件魔数失败", e);
            return false;
        }
    }

    /**
     * 读取 PDF 页数。
     */
    public static int countPages(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            log.warn("读取 PDF 页数失败", e);
            return 0;
        }
    }
}
