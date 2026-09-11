package com.rsdp.service.storage;

import com.rsdp.config.properties.StorageProperties;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

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
    public boolean exists(String objectKey) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            // 仅「对象不存在」返回 false；权限不足、桶不存在等其他错误按异常抛出，
            // 避免调用方把存储故障误判为文件不存在
            if (e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new IOException("MinIO 状态检查失败: " + objectKey, e);
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 状态检查失败: " + objectKey, e);
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

    @Override
    public int deleteByPrefix(String prefix) throws IOException {
        String bucketName = storageProperties.getMinio().getBucketName();
        try {
            List<DeleteObject> objects = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucketName).prefix(prefix).recursive(true).build())) {
                objects.add(new DeleteObject(result.get().objectName()));
            }
            if (objects.isEmpty()) {
                return 0;
            }
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(
                RemoveObjectsArgs.builder().bucket(bucketName).objects(objects).build());
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                if (error != null) {
                    throw new IOException("MinIO 批量删除失败: " + error.objectName() + " " + error.message());
                }
            }
            log.debug("MinIO 按前缀删除对象: {}/{} 共 {} 个", bucketName, prefix, objects.size());
            return objects.size();
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("MinIO 按前缀删除失败: " + prefix, e);
        }
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
