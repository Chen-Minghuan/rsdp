package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 订单合同服务：合同文件上传回填 / 下载 / 清除。
 *
 * <p>合同文件落 image_assets（image_type=contract）+ StorageService，
 * 订单通过 contract_file_id 关联；下载走归属校验的专用端点（不走公开图片通道）。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderContractService {

    /** 合同文件大小上限（20MB）。 */
    private static final long MAX_CONTRACT_SIZE_BYTES = 20L * 1024 * 1024;

    /** 允许的合同文件格式。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("doc", "docx", "pdf");

    private final OrderService orderService;
    private final DesignOrderMapper designOrderMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    /**
     * 合同文件（下载用）。
     *
     * @param content  文件流（调用方负责关闭）
     * @param fileName 下载文件名
     * @param size     文件大小（字节，未知为 -1）
     */
    public record ContractFile(InputStream content, String fileName, long size) {
    }

    /**
     * 上传合同文件并关联订单（覆盖旧合同关联）。仅订单创建人或 ADMIN。
     *
     * @param orderId 订单 ID
     * @param file    合同文件（doc/docx/pdf，≤20MB）
     * @throws IOException 存储失败时抛出
     */
    @Transactional
    public void uploadContract(String orderId, MultipartFile file) throws IOException {
        DesignOrder order = orderService.getAccessibleOrder(orderId);
        validate(file);

        String fileId = IdGenerator.imageId();
        String extension = getExtension(file.getOriginalFilename());
        String objectKey = "contracts/" + fileId + "." + extension;
        String storagePath = storageService.store(file, objectKey);

        ImageAssets asset = new ImageAssets();
        asset.setImageId(fileId);
        asset.setImageType("contract");
        asset.setStoragePath(storagePath);
        asset.setPrimary(false);
        asset.setAiProcessed(false);
        asset.setFileSize(file.getSize());
        asset.setFormat(extension);
        asset.setUploadedBy(SecurityOperatorContext.currentUsername());
        asset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(asset);

        DesignOrder oldSnapshot = snapshot(order);
        order.setContractFileId(fileId);
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.updateById(order);
        auditLogService.logUpdate("design_order", orderId, oldSnapshot, order,
            SecurityOperatorContext.currentUsername());
    }

    /**
     * 下载订单合同文件（归属校验，不走公开图片通道）。
     *
     * @param orderId 订单 ID
     * @return 合同文件流
     * @throws IOException 读取失败时抛出
     */
    public ContractFile downloadContract(String orderId) throws IOException {
        DesignOrder order = orderService.getAccessibleOrder(orderId);
        if (!StringUtils.hasText(order.getContractFileId())) {
            throw new ResourceNotFoundException("该订单尚未上传合同");
        }
        ImageAssets asset = imageAssetsMapper.selectById(order.getContractFileId());
        if (asset == null || !StringUtils.hasText(asset.getStoragePath())) {
            throw new ResourceNotFoundException("合同文件不存在");
        }
        InputStream content = storageService.get(asset.getStoragePath());
        String fileName = order.getOrderNo() + "-合同." + (StringUtils.hasText(asset.getFormat()) ? asset.getFormat() : "docx");
        return new ContractFile(content, fileName, asset.getFileSize() != null ? asset.getFileSize() : -1L);
    }

    /**
     * 清除订单合同关联（合同文件软删除）。
     *
     * @param orderId 订单 ID
     */
    @Transactional
    public void deleteContract(String orderId) {
        DesignOrder order = orderService.getAccessibleOrder(orderId);
        if (!StringUtils.hasText(order.getContractFileId())) {
            throw new ResourceNotFoundException("该订单尚未上传合同");
        }
        DesignOrder oldSnapshot = snapshot(order);
        String fileId = order.getContractFileId();
        order.setContractFileId(null);
        order.setUpdatedAt(LocalDateTime.now());
        designOrderMapper.update(null, new UpdateWrapper<DesignOrder>()
            .eq("order_id", orderId)
            .set("contract_file_id", null)
            .set("updated_at", order.getUpdatedAt()));
        imageAssetsMapper.deleteById(fileId);
        auditLogService.logUpdate("design_order", orderId, oldSnapshot, order,
            SecurityOperatorContext.currentUsername());
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传合同文件");
        }
        if (file.getSize() > MAX_CONTRACT_SIZE_BYTES) {
            throw new BusinessException("合同文件大小超过 20MB 限制");
        }
        if (!ALLOWED_EXTENSIONS.contains(getExtension(file.getOriginalFilename()))) {
            throw new BusinessException("仅支持 doc、docx、pdf 格式的合同文件");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }

    private DesignOrder snapshot(DesignOrder source) {
        DesignOrder copy = new DesignOrder();
        copy.setOrderId(source.getOrderId());
        copy.setOrderNo(source.getOrderNo());
        copy.setStatus(source.getStatus());
        copy.setContractFileId(source.getContractFileId());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
