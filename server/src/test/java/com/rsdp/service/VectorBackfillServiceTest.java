package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorBackfillService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VectorBackfillServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChromaDbClient chromaDbClient;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private VectorBackfillService vectorBackfillService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Field field = VectorBackfillService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(vectorBackfillService, objectMapper);
    }

    @Test
    void backfill_shouldUpdatePgAfterChromaDbSuccess() throws Exception {
        // Given
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        Page<ImageAssets> page = new Page<>(1, 100);
        page.setRecords(List.of(image));
        when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setStatus("active");
        doReturn(List.of(rspu)).when(rspuMapper).selectBatchIds(anyCollection());

        when(storageService.get("images/IMG-001.jpg")).thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        // When
        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        // Then
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).updateById(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getStyleVector()).isEqualTo("[0.1,0.2,0.3]");

        ArgumentCaptor<List<Map<String, Object>>> metadataCaptor = ArgumentCaptor.forClass(List.class);
        verify(chromaDbClient).upsert(eq(List.of("IMG-001")), any(), metadataCaptor.capture(), eq(null));
        Map<String, Object> metadata = metadataCaptor.getValue().get(0);
        assertThat(metadata).containsEntry("rspu_id", "RSPU-001");
        assertThat(metadata).containsEntry("status", "active");
        assertThat(metadata).containsKey("category_code");
        assertThat(metadata).containsKey("positioning_label");
        assertThat(metadata).containsKey("color_primary_name");
        assertThat(metadata).containsKey("material_tags");
        assertThat(metadata).containsKey("scene_tags");
        assertThat(metadata).containsEntry("image_size", 0L);
    }

    @Test
    void backfill_shouldNotUpdatePgWhenChromaDbFails() throws Exception {
        // Given
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        Page<ImageAssets> page = new Page<>(1, 100);
        page.setRecords(List.of(image));
        when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setStatus("active");
        doReturn(List.of(rspu)).when(rspuMapper).selectBatchIds(anyCollection());

        when(storageService.get("images/IMG-001.jpg")).thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        doThrow(new RuntimeException("ChromaDB 异常")).when(chromaDbClient).upsert(any(), any(), any(), any());

        // When
        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        // Then
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
    }

    @Test
    void backfill_shouldSkipWhenStyleVectorAlreadyExists() throws Exception {
        // Given
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        Page<ImageAssets> page = new Page<>(1, 100);
        page.setRecords(List.of(image));
        when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setStyleVector("[0.9,0.8,0.7]");
        doReturn(List.of(rspu)).when(rspuMapper).selectBatchIds(anyCollection());

        // ChromaDB 中向量真实存在，才跳过
        when(chromaDbClient.getExistingIds(List.of("IMG-001"))).thenReturn(Set.of("IMG-001"));

        // When
        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        // Then
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(embeddingService, never()).embedImage(any());
        verify(chromaDbClient, never()).upsert(any(), any(), any(), any());
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
    }

    @Test
    void backfill_shouldRepairWhenPgHasVectorButChromaMissing() throws Exception {
        // Given：PG 有向量但 ChromaDB 缺失（死区记录），应复用存量向量补偿回填
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        Page<ImageAssets> page = new Page<>(1, 100);
        page.setRecords(List.of(image));
        when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setStyleVector("[0.9,0.8,0.7]");
        doReturn(List.of(rspu)).when(rspuMapper).selectBatchIds(anyCollection());

        when(chromaDbClient.getExistingIds(List.of("IMG-001"))).thenReturn(Set.of());

        // When
        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        // Then：补偿 upsert 成功，不重新调用 embedding API，也不重复写 PG
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(embeddingService, never()).embedImage(any());
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));

        ArgumentCaptor<List<float[]>> embeddingCaptor = ArgumentCaptor.forClass(List.class);
        verify(chromaDbClient).upsert(eq(List.of("IMG-001")), embeddingCaptor.capture(), any(), eq(null));
        assertThat(embeddingCaptor.getValue().get(0)).containsExactly(0.9f, 0.8f, 0.7f);
    }

    @Test
    void backfill_shouldCompensateDeleteChromaWhenPgUpdateFails() throws Exception {
        // Given：ChromaDB 写入成功但 PG 更新失败，应补偿删除 ChromaDB 向量
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        Page<ImageAssets> page = new Page<>(1, 100);
        page.setRecords(List.of(image));
        when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        doReturn(List.of(rspu)).when(rspuMapper).selectBatchIds(anyCollection());

        when(storageService.get("images/IMG-001.jpg")).thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        doThrow(new RuntimeException("PG 异常")).when(rspuMapper).updateById(any(RspuMaster.class));

        // When
        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        // Then
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(chromaDbClient).delete(List.of("IMG-001"));
    }
}
