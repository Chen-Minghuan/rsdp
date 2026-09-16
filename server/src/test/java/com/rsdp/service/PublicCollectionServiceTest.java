package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.response.PublicCollectionDetailResponse;
import com.rsdp.dto.response.PublicCollectionSummaryResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.ProductCollection;
import com.rsdp.entity.ProductCollectionItem;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductCollectionItemMapper;
import com.rsdp.mapper.ProductCollectionMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublicCollectionService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PublicCollectionServiceTest {

    @Mock
    private ProductCollectionMapper collectionMapper;

    @Mock
    private ProductCollectionItemMapper itemMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PublicCollectionService publicCollectionService;

    private ProductCollection publishedCollection() {
        ProductCollection collection = new ProductCollection();
        collection.setCollectionId("COL-1");
        collection.setName("中古风客厅");
        collection.setDescription("精选中古风客厅搭配");
        collection.setCategoryCodes("[\"FS\"]");
        collection.setIsPublished(true);
        return collection;
    }

    private ProductCollectionItem item(String rspuId, int sortOrder) {
        ProductCollectionItem item = new ProductCollectionItem();
        item.setCollectionId("COL-1");
        item.setRspuId(rspuId);
        item.setSortOrder(sortOrder);
        return item;
    }

    private RspuMaster activeRspu(String rspuId, String name) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setProductName(name);
        rspu.setStatus("active");
        rspu.setRetailPrice(new BigDecimal("1999.00"));
        return rspu;
    }

    @Test
    void listPublished_shouldOnlyQueryPublishedCollections() {
        when(collectionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        List<PublicCollectionSummaryResponse> result = publicCollectionService.listPublished();

        ArgumentCaptor<QueryWrapper<ProductCollection>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collectionMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("is_published");
        assertThat(result).isEmpty();
    }

    @Test
    void listPublished_shouldCountOnlyActiveItems() {
        when(collectionMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(publishedCollection()));
        when(itemMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(item("RSPU-1", 0), item("RSPU-2", 1)));
        // RSPU-2 非在售，activeRspuMap 只回 RSPU-1（DB 层按 status='active' 过滤）
        when(rspuMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(activeRspu("RSPU-1", "休闲椅")));
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-1");
        image.setRspuId("RSPU-1");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image));

        List<PublicCollectionSummaryResponse> result = publicCollectionService.listPublished();

        assertThat(result).hasSize(1);
        PublicCollectionSummaryResponse summary = result.get(0);
        assertThat(summary.getCollectionId()).isEqualTo("COL-1");
        assertThat(summary.getItemCount()).isEqualTo(1);
        assertThat(summary.getCoverImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(summary.getCategoryCodes()).containsExactly("FS");
    }

    @Test
    void getPublishedDetail_unpublished_shouldThrowNotFound() {
        ProductCollection collection = publishedCollection();
        collection.setIsPublished(false);
        when(collectionMapper.selectById("COL-1")).thenReturn(collection);

        assertThatThrownBy(() -> publicCollectionService.getPublishedDetail("COL-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("未发布");
    }

    @Test
    void getPublishedDetail_notExists_shouldThrowNotFound() {
        when(collectionMapper.selectById("COL-9")).thenReturn(null);

        assertThatThrownBy(() -> publicCollectionService.getPublishedDetail("COL-9"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublishedDetail_shouldOnlyReturnActiveProductsWithPublicFields() {
        when(collectionMapper.selectById("COL-1")).thenReturn(publishedCollection());
        when(itemMapper.selectByCollectionId("COL-1"))
            .thenReturn(List.of(item("RSPU-1", 0), item("RSPU-2", 1)));
        // RSPU-2 已下架，只回 RSPU-1
        when(rspuMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(activeRspu("RSPU-1", "休闲椅")));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(rspuVariantMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of());

        PublicCollectionDetailResponse detail = publicCollectionService.getPublishedDetail("COL-1");

        assertThat(detail.getCollectionId()).isEqualTo("COL-1");
        assertThat(detail.getItemCount()).isEqualTo(1);
        assertThat(detail.getItems()).hasSize(1);
        var product = detail.getItems().get(0);
        assertThat(product.getRspuId()).isEqualTo("RSPU-1");
        assertThat(product.getProductName()).isEqualTo("休闲椅");
        assertThat(product.getRetailPrice()).isEqualByComparingTo("1999.00");
    }
}
