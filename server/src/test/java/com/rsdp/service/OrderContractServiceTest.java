package com.rsdp.service;

import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderContractService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderContractServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private DesignOrderMapper designOrderMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private OrderContractService orderContractService;

    private DesignOrder order() {
        DesignOrder order = new DesignOrder();
        order.setOrderId("ORD-1");
        order.setOrderNo("DO-20260715-001");
        order.setStatus("PENDING");
        order.setCreatedBy("user-1");
        return order;
    }

    @Test
    void uploadContractShouldStoreAndLinkOrder() throws IOException {
        when(orderService.getAccessibleOrder("ORD-1")).thenReturn(order());
        MockMultipartFile file = new MockMultipartFile(
            "file", "contract.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[]{1, 2, 3});
        when(storageService.store(any(MockMultipartFile.class), anyString())).thenReturn("contracts/IMG-1.docx");

        orderContractService.uploadContract("ORD-1", file);

        ArgumentCaptor<ImageAssets> assetCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getImageType()).isEqualTo("contract");
        assertThat(assetCaptor.getValue().getStoragePath()).isEqualTo("contracts/IMG-1.docx");

        ArgumentCaptor<DesignOrder> orderCaptor = ArgumentCaptor.forClass(DesignOrder.class);
        verify(designOrderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getContractFileId()).startsWith("IMG-");
        verify(auditLogService).logUpdate(eq("design_order"), eq("ORD-1"), any(), any(DesignOrder.class), any());
    }

    @Test
    void uploadContractShouldRejectInvalidExtension() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "evil.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> orderContractService.uploadContract("ORD-1", file))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("doc、docx、pdf");
        verify(imageAssetsMapper, never()).insert(any(ImageAssets.class));
    }

    @Test
    void downloadContractShouldReturnStream() throws IOException {
        DesignOrder order = order();
        order.setContractFileId("IMG-1");
        when(orderService.getAccessibleOrder("ORD-1")).thenReturn(order);
        ImageAssets asset = new ImageAssets();
        asset.setImageId("IMG-1");
        asset.setStoragePath("contracts/IMG-1.pdf");
        asset.setFormat("pdf");
        asset.setFileSize(3L);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(asset);
        when(storageService.get("contracts/IMG-1.pdf")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        OrderContractService.ContractFile file = orderContractService.downloadContract("ORD-1");

        assertThat(file.fileName()).isEqualTo("DO-20260715-001-合同.pdf");
        assertThat(file.size()).isEqualTo(3L);
        assertThat(file.content().readAllBytes()).hasSize(3);
    }

    @Test
    void downloadContractShouldRejectWhenNoContract() {
        when(orderService.getAccessibleOrder("ORD-1")).thenReturn(order());

        assertThatThrownBy(() -> orderContractService.downloadContract("ORD-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("尚未上传合同");
    }

    @Test
    void deleteContractShouldClearLinkAndSoftDeleteAsset() {
        DesignOrder order = order();
        order.setContractFileId("IMG-1");
        when(orderService.getAccessibleOrder("ORD-1")).thenReturn(order);

        orderContractService.deleteContract("ORD-1");

        verify(designOrderMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class));
        verify(imageAssetsMapper).deleteById("IMG-1");
        verify(auditLogService).logUpdate(eq("design_order"), eq("ORD-1"), any(), any(DesignOrder.class), any());
    }

    @Test
    void deleteContractShouldRejectWhenNoContract() {
        when(orderService.getAccessibleOrder("ORD-1")).thenReturn(order());

        assertThatThrownBy(() -> orderContractService.deleteContract("ORD-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("尚未上传合同");
    }
}
