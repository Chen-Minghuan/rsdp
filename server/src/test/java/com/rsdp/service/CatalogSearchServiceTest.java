package com.rsdp.service;

import com.rsdp.exception.BusinessException;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorHit;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSearchServiceTest {

    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final ProductVectorStore store = mock(ProductVectorStore.class);
    private final CatalogSearchService search = new CatalogSearchService(embedding, store, new ImageUploadValidator());

    @Test
    void textSearchKeepsVectorOrderAndCachesOneHitPerProduct() {
        float[] vector = {1};
        when(embedding.embedText("中古风沙发")).thenReturn(vector);
        when(store.search(eq(vector), anyInt(), isNull(), eq(true))).thenReturn(List.of(
            new VectorHit("img1", "rspu1", .1),
            new VectorHit("img2", "rspu1", .2),
            new VectorHit("img3", "rspu2", .3)));

        var first = search.match(" 中古风沙发 ", null, true);
        assertThat(first.keyword()).isEqualTo("中古风沙发");
        assertThat(first.rankedIds()).containsExactly("rspu1", "rspu2");
        assertThat(search.match("中古风沙发", null, true)).isEqualTo(first);
        verify(embedding).embedText("中古风沙发");
    }

    @Test
    void textSearchKeepsNameMatchingWhenEmbeddingFails() {
        when(embedding.embedText("云朵")).thenThrow(new IllegalStateException("embedding unavailable"));

        assertThat(search.match("云朵", null, false))
            .isEqualTo(new CatalogSearchService.Match("云朵", List.of()));
    }

    @Test
    void imageSearchIdIsReusableOnlyInItsOriginalScope() {
        float[] vector = {1};
        when(embedding.embedImage(any())).thenReturn(vector);
        when(store.search(eq(vector), anyInt(), isNull(), eq(true)))
            .thenReturn(List.of(new VectorHit("img1", "rspu1", .1)));

        String id = search.startImageSearch(new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", new byte[] {1}), true);
        assertThat(search.match(null, id, true).rankedIds()).containsExactly("rspu1");
        assertThatThrownBy(() -> search.match(null, id, false)).isInstanceOf(BusinessException.class);
    }

    @Test
    void searchSqlBindsNamesAndIdsAndRanksNameBeforeVectors() {
        var query = new CatalogSearchQuery();
        var match = new CatalogSearchService.Match("云朵_100%", List.of("rspu2", "rspu3"));
        query.eq("status", "active");
        query.applyMatch(match, false);
        query.orderByMatch(match, false, "created_at DESC", false);

        assertThat(query.getSqlSegment()).contains("status =", "AND (product_name ILIKE", "OR rspu_id IN")
            .containsSubsequence("ORDER BY CASE WHEN lower(product_name)", "THEN 0", "THEN 1", "array_position");
        assertThat(query.getParamNameValuePairs().values())
            .contains("active", "云朵_100%", "%云朵!_100!%%", "rspu2", "rspu3");
    }
}
