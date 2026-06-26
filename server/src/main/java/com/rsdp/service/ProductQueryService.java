package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 产品查询与复核服务。
 */
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AiRecognitionMapper aiRecognitionMapper;

    /**
     * 分页查询产品列表。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    public PageResult<ProductSummaryResponse> listProducts(ProductListRequest request) {
        Page<RspuMaster> pageParam = new Page<>(request.getPage(), request.getSize());
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        wrapper.isNull("deleted_at");

        if (StringUtils.hasText(request.getCategoryCode())) {
            wrapper.eq("category_code", request.getCategoryCode());
        }
        if (StringUtils.hasText(request.getPositioningLabel())) {
            wrapper.eq("positioning_label", request.getPositioningLabel());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        if (StringUtils.hasText(request.getReviewStatus())) {
            wrapper.eq("review_status", request.getReviewStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim() + "%";
            wrapper.and(w -> w.like("category_path", keyword).or().like("positioning_label", keyword));
        }

        wrapper.orderByDesc("created_at");
        Page<RspuMaster> page = rspuMapper.selectPage(pageParam, wrapper);

        List<ProductSummaryResponse> rows = page.getRecords().stream()
            .map(this::toSummary)
            .collect(Collectors.toList());

        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 查询产品详情。
     *
     * @param rspuId RSPU ID
     * @return 产品详情
     */
    public ProductDetailResponse getProductDetail(String rspuId) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).orderByDesc("is_primary")
        );
        List<AiRecognition> recognitions = aiRecognitionMapper.selectList(
            new QueryWrapper<AiRecognition>().eq("rspu_id", rspuId).orderByDesc("created_at")
        );

        ProductDetailResponse response = new ProductDetailResponse();
        response.setRspu(rspu);
        response.setImages(images);
        response.setRecognitions(recognitions);
        return response;
    }

    /**
     * 复核确认产品。
     *
     * @param rspuId         RSPU ID
     * @param reviewStatus   复核状态
     * @param reviewComment  复核备注
     */
    public void reviewProduct(String rspuId, String reviewStatus, String reviewComment) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }

        rspu.setReviewStatus(reviewStatus);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);

        // 复核备注暂不单独建表，可写入 audit_log（后续实现审计时补充）
    }

    private ProductSummaryResponse toSummary(RspuMaster rspu) {
        ProductSummaryResponse summary = new ProductSummaryResponse();
        summary.setRspuId(rspu.getRspuId());
        summary.setCategoryCode(rspu.getCategoryCode());
        summary.setCategoryPath(rspu.getCategoryPath());
        summary.setPositioningLabel(rspu.getPositioningLabel());
        summary.setColorPrimaryName(rspu.getColorPrimaryName());
        summary.setStatus(rspu.getStatus());
        summary.setReviewStatus(rspu.getReviewStatus());
        summary.setAestheticsConfidence(rspu.getAestheticsConfidence());
        summary.setCreatedAt(rspu.getCreatedAt());
        summary.setUpdatedAt(rspu.getUpdatedAt());

        ImageAssets primaryImage = imageAssetsMapper.selectOne(
            new QueryWrapper<ImageAssets>()
                .eq("rspu_id", rspu.getRspuId())
                .eq("is_primary", true)
                .last("LIMIT 1")
        );
        if (primaryImage != null) {
            summary.setPrimaryImageUrl(buildImageUrl(primaryImage.getImageId()));
        }
        return summary;
    }

    private String buildImageUrl(String imageId) {
        return "/api/v1/images/" + imageId;
    }
}
