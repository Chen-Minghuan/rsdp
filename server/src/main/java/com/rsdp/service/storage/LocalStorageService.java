package com.rsdp.service.storage;

import com.rsdp.config.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final StorageProperties storageProperties;

    @Override
    public String store(MultipartFile file, String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        Files.createDirectories(target.getParent());
        file.transferTo(target.toFile());
        log.debug("本地存储写入文件: {}", target);
        return objectKey;
    }

    @Override
    public String store(InputStream inputStream, String objectKey, long size, String contentType) throws IOException {
        Path target = resolvePath(objectKey);
        Files.createDirectories(target.getParent());
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("本地存储写入文件: {}", target);
        return objectKey;
    }

    @Override
    public InputStream get(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        if (!Files.exists(target)) {
            throw new IOException("文件不存在: " + objectKey);
        }
        return Files.newInputStream(target);
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolvePath(objectKey));
    }

    @Override
    public void delete(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        Files.deleteIfExists(target);
        log.debug("本地存储删除文件: {}", target);
    }

    /**
     * 将 objectKey 解析为本地绝对路径。
     *
     * <p>兼容旧数据：若 objectKey 本身已是绝对路径（如旧版直接存储的完整磁盘路径），
     * 且位于允许根目录内，则直接使用；否则按配置的 localPath 拼接，并校验不越界。</p>
     *
     * @param objectKey 对象键
     * @return 解析后的本地绝对路径
     * @throws IllegalArgumentException 当路径越界或非法时
     */
    private Path resolvePath(String objectKey) {
        Path root = resolveRoot();
        Path objectPath = Paths.get(objectKey).normalize();

        Path resolved = objectPath.isAbsolute()
            ? objectPath
            : root.resolve(objectPath).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法对象键，路径越界: " + objectKey);
        }
        return resolved;
    }

    /**
     * 解析并返回本地存储的根目录。
     *
     * @return 根目录绝对路径（已 normalize）
     */
    private Path resolveRoot() {
        String localPath = storageProperties.getLocalPath();
        Path root = Paths.get(localPath);
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir"), localPath);
        }
        return root.normalize();
    }
}
