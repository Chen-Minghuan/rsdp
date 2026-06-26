package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ProductService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AsyncTaskProcessor asyncTaskProcessor;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    private final ImageUploadValidator imageUploadValidator = new ImageUploadValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() throws Exception {
        setField("maxFileSize", "20MB");
        setField("imageUploadValidator", imageUploadValidator);
        setField("objectMapper", objectMapper);
        setField("storageService", storageService);
        setField("auditLogService", auditLogService);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ProductService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(productService, value);
    }

    @Test
    void createEntry_shouldCreateDraftAndTriggerAsyncTask() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        Map<String, Object> result = productService.createEntry(List.of(image), null);

        assertThat(result).containsKeys("taskId", "rspuId", "imageIds", "message");
        assertThat(result.get("imageIds")).asList().hasSize(1);

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getStatus()).isEqualTo("processing");
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("FS");

        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper, times(1)).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getFormat()).isEqualTo("jpg");
        assertThat(imageCaptor.getValue().getAiProcessed()).isFalse();
        assertThat(imageCaptor.getValue().getStoragePath()).startsWith("images/");
        assertThat(imageCaptor.getValue().getPrimary()).isTrue();
        assertThat(imageCaptor.getValue().getImageType()).isEqualTo("white_bg");

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(1)).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("pending");

        verify(storageService, times(1)).store(any(), anyString());
        verify(asyncTaskProcessor, times(1))
            .processProductEntry(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createEntry_withMultipleImages_shouldCreateOneRspuAndMultipleImages() throws Exception {
        MockMultipartFile primary = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        MockMultipartFile detail = new MockMultipartFile(
            "image", "chair-detail.jpg", "image/jpeg", "fake-detail".getBytes()
        );
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        Map<String, Object> result = productService.createEntry(List.of(primary, detail), "DT");

        assertThat(result).containsKeys("taskId", "rspuId", "imageIds", "message");
        assertThat(result.get("imageIds")).asList().hasSize(2);

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("DT");
        assertThat(rspuCaptor.getValue().getCategoryPath()).contains("桌子");
        verify(imageAssetsMapper, times(2)).insert(any(ImageAssets.class));
        verify(asyncTaskMapper, times(1)).insert(any(AsyncTask.class));
        verify(asyncTaskProcessor, times(1))
            .processProductEntry(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createEntry_shouldRejectEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "image", "empty.jpg", "image/jpeg", new byte[0]
        );

        assertThatThrownBy(() -> productService.createEntry(List.of(emptyFile), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请上传图片文件");

        verifyNoInteractions(rspuMapper, asyncTaskMapper, imageAssetsMapper, asyncTaskProcessor);
    }

    @Test
    void createEntry_shouldRejectNonImageFile() {
        MockMultipartFile textFile = new MockMultipartFile(
            "image", "readme.txt", "text/plain", "hello".getBytes()
        );

        assertThatThrownBy(() -> productService.createEntry(List.of(textFile), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片格式");
    }

    @Test
    void createEntry_shouldRejectEmptyImageList() {
        assertThatThrownBy(() -> productService.createEntry(List.of(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请至少上传一张图片");

        verifyNoInteractions(rspuMapper, asyncTaskMapper, imageAssetsMapper, asyncTaskProcessor);
    }
}
