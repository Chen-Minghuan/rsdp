package com.rsdp.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Component;

/**
 * Excel / POI 安全初始化。
 *
 * <p>在应用启动时设置 POI 全局安全阈值，防御 zip bomb、 billion laughs 等
 * 基于 Office 文档的攻击。该配置对 Apache POI 直接调用以及基于 POI 的
 * EasyExcel 解析均生效。</p>
 */
@Slf4j
@Component
public class ExcelSecurityInitializer {

    /** inflate 压缩比阈值，低于该值视为 zip bomb。 */
    private static final double MIN_INFLATE_RATIO = 0.001d;

    /** 单个 zip 条目最大允许大小（字节），约 100 MB。 */
    private static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;

    @PostConstruct
    public void init() {
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
        log.info("已启用 Excel 安全限制：minInflateRatio={}, maxEntrySize={}", MIN_INFLATE_RATIO, MAX_ENTRY_SIZE);
    }
}
