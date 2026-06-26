package com.rsdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ProductQueryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AiRecognitionMapper aiRecognitionMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ProductQueryService productQueryService;

    @Test
    void listProducts_shouldReturnPageResult() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setCategoryPath("[\"家具\",\"座椅\"]");
        rspu.setPositioningLabel("中古风");
        rspu.setStatus("active");
        rspu.setReviewStatus("待复核");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getRspuId()).isEqualTo("RSPU-TEST01");
    }

    @Test
    void getProductDetail_shouldReturnDetail() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setStatus("active");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(aiRecognitionMapper.selectList(any())).thenReturn(List.of());

        ProductDetailResponse detail = productQueryService.getProductDetail("RSPU-TEST01");

        assertThat(detail.getRspu()).isNotNull();
        assertThat(detail.getImages()).isEmpty();
        assertThat(detail.getRecognitions()).isEmpty();
    }

    @Test
    void getProductDetail_shouldThrowWhenNotFound() {
        when(rspuMapper.selectById(eq("RSPU-NOTEXIST"))).thenReturn(null);

        assertThatThrownBy(() -> productQueryService.getProductDetail("RSPU-NOTEXIST"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reviewProduct_shouldUpdateReviewStatus() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setReviewStatus("待复核");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);

        productQueryService.reviewProduct("RSPU-TEST01", "已确认", "人工复核通过");

        assertThat(rspu.getReviewStatus()).isEqualTo("已确认");
    }
}
