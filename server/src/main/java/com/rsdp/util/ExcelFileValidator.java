package com.rsdp.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Excel / CSV 文件魔数校验工具。
 *
 * <p>不依赖客户端提交的 {@code Content-Type}，通过文件头判断真实格式。</p>
 */
public final class ExcelFileValidator {

    private ExcelFileValidator() {
        // utility class
    }

    private static final byte[] XLSX_MAGIC = {0x50, 0x4B, 0x03, 0x04}; // ZIP / .xlsx
    private static final byte[] XLS_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
    }; // OLE2 / .xls

    /**
     * 校验文件是否为 Excel（.xlsx/.xls）或 CSV。
     *
     * @param file 上传文件
     * @return true 表示格式符合预期
     */
    public static boolean isExcelOrCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(8);
            if (startsWith(header, XLSX_MAGIC)) {
                return true;
            }
            if (startsWith(header, XLS_MAGIC)) {
                return true;
            }
            return looksLikeCsv(header, in);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean looksLikeCsv(byte[] header, InputStream remaining) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(header);

        byte[] chunk = new byte[1024];
        int read;
        int total = header.length;
        while (total < 2048 && (read = remaining.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            total += read;
        }

        String sample = buffer.toString(StandardCharsets.UTF_8);
        if (!isPrintableText(sample)) {
            return false;
        }
        return sample.contains(",") || sample.contains(";") || sample.contains("\t") || sample.contains("\n");
    }

    private static boolean isPrintableText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean printable = c == '\r' || c == '\n' || c == '\t' || !Character.isISOControl(c);
            if (!printable) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source == null || source.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
