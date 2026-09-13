package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * URL 图片下载的共享实现（4.3 批④：传统 Excel 导入与 Excel AI 导入两处副本收敛，
 * 防护口径自 0.3/P2-8 起已对齐，此处纯搬移合并）。
 *
 * <p>共享防护：禁自动重定向（防 SSRF 302 跳内网）、10s 连接 / 30s 读取超时、
 * Content-Length 预检（超限直接断开）、{@code readNBytes(MAX+1)} 限量读取
 * （chunked 谎报也能截断，绝不 readAllBytes 全量加载）。</p>
 *
 * <p>URL 合法性（ImageUrlValidator 白名单）与内容嗅探（魔数）不在本类职责内，
 * 由调用方按各自链路口径在 fetch 前后处理。</p>
 */
@Slf4j
public final class ImageUrlDownloads {

    /** 单张图片大小上限（20 MB，两处链路口径一致） */
    public static final int MAX_IMAGE_SIZE = 20 * 1024 * 1024;

    private ImageUrlDownloads() {
    }

    /** 抓取结果：图片字节 + 服务端 Content-Type（可为 null）。 */
    public record FetchResult(byte[] bytes, String contentType) {
    }

    /**
     * 下载图片（共享防护见类注释）。
     *
     * @param url 图片 URL（调用方须先过 ImageUrlValidator 白名单）
     * @return 抓取结果；失败/超限/空内容返回 null（原因记日志）
     */
    public static FetchResult fetch(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
            }
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            String contentType = connection.getContentType();
            // Content-Length 预检 + 限量读取：先判大小再下载，避免恶意 URL 用超大响应撑爆堆内存
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_SIZE) {
                log.warn("图片超过大小上限（{} 字节），已跳过: {}", contentLength, url);
                if (connection instanceof HttpURLConnection httpConnection) {
                    httpConnection.disconnect();
                }
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                // 多读 1 字节用于判断是否超限，无需 readAllBytes 全量加载
                byte[] bytes = in.readNBytes(MAX_IMAGE_SIZE + 1);
                if (bytes.length == 0) {
                    log.warn("图片 URL 返回空内容: {}", url);
                    return null;
                }
                if (bytes.length > MAX_IMAGE_SIZE) {
                    log.warn("图片超过最大限制 {}MB: {}", MAX_IMAGE_SIZE / 1024 / 1024, url);
                    return null;
                }
                return new FetchResult(bytes, contentType);
            }
        } catch (Exception e) {
            log.warn("下载图片失败: {}", url, e);
            return null;
        }
    }
}
