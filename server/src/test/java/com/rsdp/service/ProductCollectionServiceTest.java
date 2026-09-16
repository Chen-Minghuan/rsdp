package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.ProductCollectionUpdateRequest;
import com.rsdp.dto.response.ProductCollectionResponse;
import com.rsdp.entity.ProductCollection;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductCollectionItemMapper;
import com.rsdp.mapper.ProductCollectionMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductCollectionService} 归属隔离与发布控制单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductCollectionServiceTest {

    @Mock
    private ProductCollectionMapper collectionMapper;

    @Mock
    private ProductCollectionItemMapper itemMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProductCollectionService productCollectionService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String userId, String username, String role) {
        SecurityUser user = new SecurityUser(userId, username, "",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private ProductCollection sampleCollection(String createdBy) {
        ProductCollection collection = new ProductCollection();
        collection.setCollectionId("COL-1");
        collection.setName("中古风客厅");
        collection.setCreatedBy(createdBy);
        collection.setIsPublished(false);
        return collection;
    }

    @Test
    void list_asDesigner_shouldFilterByCreatedBy() {
        authenticate("USER-D1", "designer1", "DESIGNER");
        when(collectionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        productCollectionService.list(null);

        ArgumentCaptor<QueryWrapper<ProductCollection>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collectionMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("created_by");
    }

    @Test
    void list_asPlatformStaff_shouldNotFilterByCreatedBy() {
        authenticate("USER-A1", "admin", "ADMIN");
        when(collectionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        productCollectionService.list(null);

        ArgumentCaptor<QueryWrapper<ProductCollection>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collectionMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("created_by");
    }

    @Test
    void getDetail_asNonOwner_shouldThrowNotFound() {
        authenticate("USER-D2", "designer2", "DESIGNER");
        when(collectionMapper.selectById("COL-1")).thenReturn(sampleCollection("USER-D1"));

        assertThatThrownBy(() -> productCollectionService.getDetail("COL-1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDetail_asOwner_shouldReturn() {
        authenticate("USER-D1", "designer1", "DESIGNER");
        when(collectionMapper.selectById("COL-1")).thenReturn(sampleCollection("USER-D1"));
        when(itemMapper.selectByCollectionId("COL-1")).thenReturn(List.of());

        ProductCollectionResponse detail = productCollectionService.getDetail("COL-1");

        assertThat(detail.getCollectionId()).isEqualTo("COL-1");
        assertThat(detail.getItemCount()).isEqualTo(0);
    }

    @Test
    void getDetail_asPlatformStaff_shouldReturnOthersCollection() {
        authenticate("USER-E1", "editor", "EDITOR");
        when(collectionMapper.selectById("COL-1")).thenReturn(sampleCollection("USER-D1"));
        when(itemMapper.selectByCollectionId("COL-1")).thenReturn(List.of());

        ProductCollectionResponse detail = productCollectionService.getDetail("COL-1");

        assertThat(detail.getCollectionId()).isEqualTo("COL-1");
    }

    @Test
    void update_publishAsDesigner_shouldThrow() {
        authenticate("USER-D1", "designer1", "DESIGNER");
        when(collectionMapper.selectById("COL-1")).thenReturn(sampleCollection("USER-D1"));
        ProductCollectionUpdateRequest request = new ProductCollectionUpdateRequest();
        request.setIsPublished(true);

        assertThatThrownBy(() -> productCollectionService.update("COL-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅平台运营人员");
        verify(collectionMapper, never()).updateById(any(ProductCollection.class));
    }

    @Test
    void update_publishAsPlatformStaff_shouldPass() {
        authenticate("USER-A1", "admin", "ADMIN");
        ProductCollection collection = sampleCollection("USER-D1");
        when(collectionMapper.selectById("COL-1")).thenReturn(collection);
        when(itemMapper.selectByCollectionId("COL-1")).thenReturn(List.of());
        ProductCollectionUpdateRequest request = new ProductCollectionUpdateRequest();
        request.setIsPublished(true);

        ProductCollectionResponse updated = productCollectionService.update("COL-1", request);

        ArgumentCaptor<ProductCollection> captor = ArgumentCaptor.forClass(ProductCollection.class);
        verify(collectionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getIsPublished()).isTrue();
        assertThat(updated.getIsPublished()).isTrue();
    }

    @Test
    void delete_asNonOwner_shouldThrowNotFound() {
        authenticate("USER-D2", "designer2", "DESIGNER");
        when(collectionMapper.selectById("COL-1")).thenReturn(sampleCollection("USER-D1"));

        assertThatThrownBy(() -> productCollectionService.delete("COL-1"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(collectionMapper, never()).deleteById(any(String.class));
    }
}
