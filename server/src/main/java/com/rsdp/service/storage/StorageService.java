package com.rsdp.service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储服务接口。
 *
 * <p>统一封装本地磁盘、MinIO 等不同存储后端的访问方式。业务层通过 objectKey
 * （相对路径或桶内对象名）操作文件，无需关心底层存储类型。</p>
 */
public interface StorageService {

    /**
     * 存储上传文件。
     *
     * @param file      上传文件
     * @param objectKey 对象键，例如 {@code images/IMG-XXX.jpg}
     * @return 可用于后续读取的存储路径（通常就是 objectKey）
     * @throws IOException 存储失败
     */
    String store(MultipartFile file, String objectKey) throws IOException;

    /**
     * 存储输入流（适用于从 URL 下载等非 MultipartFile 场景）。
     *
     * @param inputStream 输入流
     * @param objectKey   对象键
     * @param size        数据大小（字节），-1 表示未知
     * @param contentType 内容类型，可为 null
     * @return 可用于后续读取的存储路径
     * @throws IOException 存储失败
     */
    String store(InputStream inputStream, String objectKey, long size, String contentType) throws IOException;

    /**
     * 读取文件流。
     *
     * @param objectKey 对象键
     * @return 文件输入流，调用方负责关闭
     * @throws IOException 读取失败或文件不存在
     */
    InputStream get(String objectKey) throws IOException;

    /**
     * 判断文件是否存在。
     *
     * @param objectKey 对象键
     * @return true 表示存在
     * @throws IOException 存储后端访问异常（与「不存在」区分，避免误判为不存在后继续写入）
     */
    boolean exists(String objectKey) throws IOException;

    /**
     * 删除文件。
     *
     * @param objectKey 对象键
     * @throws IOException 删除失败
     */
    void delete(String objectKey) throws IOException;
}
