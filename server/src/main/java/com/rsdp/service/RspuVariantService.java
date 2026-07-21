package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RSPU 变体服务，管理同一款式下的尺寸、颜色、材质组合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuVariantService {

    private final RspuVariantMapper variantMapper;
    private final RspuMapper rspuMapper;
    private final VariantCodeMapper variantCodeMapper;
    private final DictService dictService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 为指定 RSPU 创建变体。
     *
     * @param rspuId  RSPU ID
     * @param request 变体创建请求
     * @return 创建后的变体响应
     */
    @Transactional
    public RspuVariantResponse createVariant(String rspuId, RspuVariantCreateRequest request) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
        dataScopeHelper.assertCanAccessRspu(rspuId);

        validateVariantCodes(request);
        if (StringUtils.hasText(request.getProductLevel())) {
            validateDictCode("factory_level", request.getProductLevel(), "产品等级");
        }

        assertNoDuplicateDimensions(rspuId, request);

        String variantId = generateVariantId(rspuId);

        RspuVariant variant = new RspuVariant();
        variant.setVariantId(variantId);
        variant.setRspuId(rspuId);
        variant.setDisplayName(request.getDisplayName().trim());
        variant.setVariantCode(request.getVariantCode());
        variant.setSizeCode(request.getSizeCode());
        variant.setDimensions(request.getDimensions());
        variant.setColorCode(request.getColorCode());
        variant.setMaterialCode(request.getMaterialCode());
        variant.setMaterialMix(toJson(request.getMaterialMix()));
        variant.setReferencePriceBand(request.getReferencePriceBand());
        variant.setProductLevel(request.getProductLevel());
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());

        try {
            variantMapper.insert(variant);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null
                && (e.getMessage().contains("uk_variant_attrs") || e.getMessage().contains("idx_variant_unique_dims"))) {
                // 并发下兜底：数据库部分唯一索引拦截的重复维度组合
                throw new BusinessException("相同尺寸/颜色/材质的变体已存在");
            }
            // variant_id 为主键，冲突说明并发生成了重复编码，提示重试
            throw new BusinessException("变体编码冲突，请重试: " + variantId);
        }
        auditLogService.logCreate("rspu_variant", variantId, variant, SecurityOperatorContext.currentUsername());

        return toResponse(variant);
    }

    /**
     * 校验同一 RSPU 下不存在相同尺寸/颜色/材质组合的启用中变体。
     *
     * @param rspuId  RSPU ID
     * @param request 变体创建请求
     */
    private void assertNoDuplicateDimensions(String rspuId, RspuVariantCreateRequest request) {
        QueryWrapper<RspuVariant> wrapper = new QueryWrapper<RspuVariant>()
            .eq("rspu_id", rspuId);
        applyNullableEq(wrapper, "size_code", request.getSizeCode());
        applyNullableEq(wrapper, "color_code", request.getColorCode());
        applyNullableEq(wrapper, "material_code", request.getMaterialCode());
        Long count = variantMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException("相同尺寸/颜色/材质的变体已存在");
        }
    }

    private void applyNullableEq(QueryWrapper<RspuVariant> wrapper, String column, String value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }

    /**
     * 查询某 RSPU 下的所有有效变体。
     *
     * @param rspuId RSPU ID
     * @return 变体列表
     */
    public List<RspuVariantResponse> listVariantsByRspu(String rspuId) {
        List<RspuVariant> variants = variantMapper.selectList(
            new QueryWrapper<RspuVariant>()
                .eq("rspu_id", rspuId)
                .orderByAsc("created_at")
        );
        return variants.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 为 RSPU 初始化默认变体（供 AI 识别异步任务调用）。
     *
     * <p>若该 RSPU 已存在变体，则直接返回；否则根据 AI 识别结果创建一个默认变体，
     * 使后续批量绑定工厂报价时不必再手工创建变体。</p>
     *
     * @param rspuId RSPU ID
     * @param labels AI 识别结果
     * @return 变体 ID（新建或已存在）
     */
    public String initializeDefaultVariant(String rspuId, AiLabels labels) {
        List<RspuVariant> existing = variantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId)
        );
        if (!existing.isEmpty()) {
            return existing.get(0).getVariantId();
        }

        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || rspu.getDeletedAt() != null) {
            log.warn("初始化默认变体时 RSPU 不存在，rspuId={}", rspuId);
            return null;
        }

        String variantId = generateVariantId(rspuId);
        RspuVariant variant = new RspuVariant();
        variant.setVariantId(variantId);
        variant.setRspuId(rspuId);
        variant.setDisplayName("默认变体");
        variant.setVariantCode("M");
        variant.setDimensions(extractDimensionsJson(labels));
        variant.setProductLevel(rspu.getProductLevel());
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());

        try {
            variantMapper.insert(variant);
            log.info("AI 识别后为 RSPU 自动创建默认变体，rspuId={}，variantId={}", rspuId, variantId);
            return variantId;
        } catch (DataIntegrityViolationException e) {
            log.warn("默认变体编码冲突，rspuId={}", rspuId, e);
            return null;
        }
    }

    private String extractDimensionsJson(AiLabels labels) {
        if (labels == null || labels.getOcr() == null) {
            return null;
        }
        Dimensions dims = labels.getOcr().getDimensions();
        if (dims == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(dims);
        } catch (Exception e) {
            log.warn("AI 识别尺寸 JSON 序列化失败", e);
            return null;
        }
    }

    /**
     * 查询单个变体详情。
     *
     * @param variantId 变体 ID
     * @return 变体响应
     */
    public RspuVariantResponse getVariant(String variantId) {
        RspuVariant variant = variantMapper.selectById(variantId);
        if (variant == null || variant.getDeletedAt() != null) {
            throw new ResourceNotFoundException("变体不存在: " + variantId);
        }
        return toResponse(variant);
    }

    /**
     * 根据变体 ID 查询实体，供其他服务调用。
     *
     * @param variantId 变体 ID
     * @return 变体实体
     */
    public RspuVariant findById(String variantId) {
        RspuVariant variant = variantMapper.selectById(variantId);
        if (variant == null || variant.getDeletedAt() != null) {
            throw new ResourceNotFoundException("变体不存在: " + variantId);
        }
        return variant;
    }

    private String generateVariantId(String rspuId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Long nextSeq = variantCodeMapper.allocateSequence(rspuId);
            if (nextSeq == null) {
                throw new BusinessException("无法生成变体编码流水号: " + rspuId);
            }
            String candidate = String.format("%s-V%03d", rspuId, nextSeq);
            if (variantMapper.selectById(candidate) == null) {
                return candidate;
            }
        }
        throw new BusinessException("变体编码冲突，请重试: " + rspuId);
    }

    private void validateVariantCodes(RspuVariantCreateRequest request) {
        validateDictCode("size", request.getSizeCode(), "尺寸码");
        validateDictCode("color", request.getColorCode(), "颜色码");
        validateDictCode("material", request.getMaterialCode(), "材质码");
    }

    private void validateDictCode(String dictType, String code, String label) {
        if (!StringUtils.hasText(code)) {
            return;
        }
        boolean exists = dictService.listByType(dictType).stream()
            .anyMatch(d -> code.equals(d.getDictCode()));
        if (!exists) {
            throw new BusinessException(label + "不存在: " + code);
        }
    }

    private RspuVariantResponse toResponse(RspuVariant variant) {
        RspuVariantResponse response = new RspuVariantResponse();
        response.setVariantId(variant.getVariantId());
        response.setRspuId(variant.getRspuId());
        response.setDisplayName(variant.getDisplayName());
        response.setVariantCode(variant.getVariantCode());
        response.setSizeCode(variant.getSizeCode());
        response.setDimensions(variant.getDimensions());
        response.setColorCode(variant.getColorCode());
        response.setMaterialCode(variant.getMaterialCode());
        response.setMaterialMix(parseJsonList(variant.getMaterialMix()));
        response.setReferencePriceBand(variant.getReferencePriceBand());
        response.setProductLevel(variant.getProductLevel());
        response.setStatus(variant.getStatus());
        response.setCreatedAt(variant.getCreatedAt());
        response.setUpdatedAt(variant.getUpdatedAt());
        return response;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "[]";
        }
    }

    private List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("JSON 反序列化失败: {}", json, e);
            return null;
        }
    }
}
