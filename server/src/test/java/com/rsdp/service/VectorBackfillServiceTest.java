package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.service.vector.ExistingVector;
import com.rsdp.service.vector.ProductVectorProfile;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorStaleImageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorBackfillService} 单元测试（pgvector 逐图片重建口径）。
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
    private ProductVectorStore productVectorStore;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private VectorBackfillService vectorBackfillService;

    private ImageAssets image;
    private RspuMaster rspu;

    @BeforeEach
    void setUp() {
        image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        image.setStoragePath("images/IMG-001.jpg");
        image.setAiProcessed(true);

        rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setStatus("active");
    }

    /**
     * 正常路径：读取源图 → 统一编码 → upsert 向量，计入成功数。
     */
    @Test
    void backfill_shouldEncodeAndUpsert() throws Exception {
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList())).thenReturn(Map.of());
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenReturn(new EmbeddingService.ImageEmbedding(new float[]{0.1f, 0.2f, 0.3f}, "hash-1"));

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(productVectorStore).upsert(eq("IMG-001"), eq(1L), eq("hash-1"), any(float[].class));
    }

    /**
     * 已存在当前配置同内容版本的向量：幂等跳过，不计成功/失败，不重新编码。
     */
    @Test
    void backfill_shouldSkipWhenProfileAndRevisionMatch() throws Exception {
        image.setContentRevision(3L);
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList()))
            .thenReturn(Map.of("IMG-001",
                new ExistingVector("IMG-001", ProductVectorProfile.CURRENT, 3L)));

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(embeddingService, never()).embedImageWithHash(any());
        verify(productVectorStore, never()).upsert(any(), eq(3L), any(), any());
    }

    /**
     * 编码配置不匹配：重新编码并覆盖写入。
     */
    @Test
    void backfill_shouldReencodeWhenProfileMismatch() throws Exception {
        image.setContentRevision(3L);
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList()))
            .thenReturn(Map.of("IMG-001", new ExistingVector("IMG-001", "old-profile", 3L)));
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenReturn(new EmbeddingService.ImageEmbedding(new float[]{0.1f}, "hash-2"));

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(1);
        verify(productVectorStore).upsert(eq("IMG-001"), eq(3L), eq("hash-2"), any(float[].class));
    }

    /**
     * 内容版本不匹配（图片已更新）：重新编码并写入新版本向量。
     */
    @Test
    void backfill_shouldReencodeWhenRevisionMismatch() throws Exception {
        image.setContentRevision(4L);
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList()))
            .thenReturn(Map.of("IMG-001",
                new ExistingVector("IMG-001", ProductVectorProfile.CURRENT, 3L)));
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenReturn(new EmbeddingService.ImageEmbedding(new float[]{0.1f}, "hash-3"));

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(1);
        verify(productVectorStore).upsert(eq("IMG-001"), eq(4L), eq("hash-3"), any(float[].class));
    }

    /**
     * 编码期间图片被更新/删除（VectorStaleImageException）：跳过，不计失败。
     */
    @Test
    void backfill_shouldSkipWhenImageStale() throws Exception {
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList())).thenReturn(Map.of());
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenReturn(new EmbeddingService.ImageEmbedding(new float[]{0.1f}, "hash-4"));
        doThrow(new VectorStaleImageException("图片已更新"))
            .when(productVectorStore).upsert(any(), anyLong(), any(), any());

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
    }

    /**
     * 编码/读取异常：计入失败数。
     */
    @Test
    void backfill_shouldCountFailedWhenEmbedThrows() throws Exception {
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList())).thenReturn(Map.of());
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenThrow(new RuntimeException("Embedding API 异常"));

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(productVectorStore, never()).upsert(any(), anyLong(), any(), any());
    }

    /**
     * 边界：batchSize {@code <=0} 或 {@code >1000} 归一为 100（归一后才会发起扫描）。
     */
    @Test
    void backfill_shouldNormalizeInvalidBatchSize() throws Exception {
        mockPage(List.of(image));
        mockRspuMap(rspu);
        when(productVectorStore.findExisting(anyList())).thenReturn(Map.of());
        when(storageService.get("images/IMG-001.jpg"))
            .thenReturn(new ByteArrayInputStream("fake".getBytes()));
        when(embeddingService.embedImageWithHash(any()))
            .thenReturn(new EmbeddingService.ImageEmbedding(new float[]{0.1f}, "hash-5"));

        VectorBackfillService.BackfillResult zero = vectorBackfillService.backfill(0);
        assertThat(zero.successCount()).isEqualTo(1);
        verify(imageAssetsMapper, org.mockito.Mockito.times(1))
            .selectPage(any(Page.class), any(QueryWrapper.class));

        VectorBackfillService.BackfillResult oversized = vectorBackfillService.backfill(1001);
        assertThat(oversized.successCount()).isEqualTo(1);
        verify(imageAssetsMapper, org.mockito.Mockito.times(2))
            .selectPage(any(Page.class), any(QueryWrapper.class));
    }

    /**
     * 所属 RSPU 已删除（rspuMap 中缺失）：跳过，不计成功/失败。
     */
    @Test
    void backfill_shouldSkipWhenRspuMissing() throws Exception {
        mockPage(List.of(image));
        mockRspuMap(); // 空 RSPU 列表
        when(productVectorStore.findExisting(anyList())).thenReturn(Map.of());

        VectorBackfillService.BackfillResult result = vectorBackfillService.backfill(100);

        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(embeddingService, never()).embedImageWithHash(any());
    }

    /**
     * 模拟一页候选图片（记录数不足页大小 → 扫描一轮后候选集耗尽）。
     */
    private void mockPage(List<ImageAssets> records) {
        Page<ImageAssets> page = new Page<>(1, 200);
        page.setRecords(records);
        // lenient：batchSize 归一测试中会多次触发
        lenient().when(imageAssetsMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
            .thenReturn(page);
    }

    private void mockRspuMap(RspuMaster... rspus) {
        lenient().doReturn(List.of(rspus)).when(rspuMapper).selectBatchIds(anyCollection());
    }
}
