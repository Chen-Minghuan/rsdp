package com.rsdp.service.storage;

import com.rsdp.config.properties.StorageProperties;
import io.minio.*;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * MinIO 对象存储实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final StorageProperties storageProperties;
    private final MinioClient minioClient;

    @Override
    public String store(MultipartFile file, String objectKey) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        ensureBucketExists(bucketName);
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 上传失败: " + objectKey, e);
        }
        log.debug("MinIO 存储写入对象: {}/{}", bucketName, objectKey);
        return objectKey;
    }

    @Override
    public String store(InputStream inputStream, String objectKey, long size, String contentType) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        ensureBucketExists(bucketName);
        try (inputStream) {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build()
            );
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 上传失败: " + objectKey, e);
        }
        log.debug("MinIO 存储写入对象: {}/{}", bucketName, objectKey);
        return objectKey;
    }

    @Override
    public InputStream get(String objectKey) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()
            );
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 读取失败: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        String bucketName = storageProperties.getMinio().getBucketName();
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()
            );
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 删除失败: " + objectKey, e);
        }
        log.debug("MinIO 存储删除对象: {}/{}", bucketName, objectKey);
    }

    private void ensureBucketExists(String bucketName) throws IOException {
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 桶检查/创建失败: " + bucketName, e);
        }
    }
}
