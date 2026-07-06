package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.SchemeCandidateCreateRequest;
import com.rsdp.dto.request.SchemeCandidateUpdateRequest;
import com.rsdp.dto.response.SchemeCandidateResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SchemeCandidate;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.SchemeCandidateMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI 推荐候选清单服务。
 */
@Service
@RequiredArgsConstructor
public class SchemeCandidateService {

    private final SchemeCandidateMapper candidateMapper;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final ObjectMapper objectMapper;

    /**
     * 根据候选 ID 查询候选详情。
     *
     * @param candidateId 候选 ID
     * @return 候选响应
     */
    public SchemeCandidateResponse getById(String candidateId) {
        SchemeCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new ResourceNotFoundException("候选不存在: " + candidateId);
        }
        return enrichSingle(candidate);
    }

    /**
     * 根据推荐请求 ID 查询候选清单。
     *
     * @param recommendRequestId 推荐请求 ID
     * @return 候选响应列表
     */
    public List<SchemeCandidateResponse> listByRequestId(String recommendRequestId) {
        List<SchemeCandidate> candidates = candidateMapper.selectByRequestId(recommendRequestId);
        return enrich(candidates);
    }

    /**
     * 查询当前用户的候选清单（按请求 ID 聚合）。
     *
     * @return 候选响应列表
     */
    public List<SchemeCandidateResponse> listMyCandidates() {
        List<SchemeCandidate> candidates = candidateMapper.selectList(
            new QueryWrapper<SchemeCandidate>()
                .eq("created_by", SecurityOperatorContext.currentUsername())
                .orderByDesc("score")
        );
        return enrich(candidates);
    }

    /**
     * 创建候选。
     *
     * @param request 创建请求
     * @return 创建后的候选响应
     */
    @Transactional
    public SchemeCandidateResponse create(SchemeCandidateCreateRequest request) {
        assertRspuExists(request.getRspuId());

        SchemeCandidate candidate = new SchemeCandidate();
        candidate.setCandidateId(UUID.randomUUID().toString());
        candidate.setRecommendRequestId(request.getRecommendRequestId());
        candidate.setRspuId(request.getRspuId());
        candidate.setRskuId(request.getRskuId());
        candidate.setScore(request.getScore());
        candidate.setAiReason(request.getAiReason());
        candidate.setMatchFactors(toJson(request.getMatchFactors()));
        candidate.setStatus("pending");
        candidate.setCreatedBy(SecurityOperatorContext.currentUsername());
        candidate.setCreatedAt(LocalDateTime.now());
        candidate.setUpdatedAt(LocalDateTime.now());
        candidateMapper.insert(candidate);
        return enrichSingle(candidate);
    }

    /**
     * 批量创建候选。
     *
     * @param recommendRequestId 推荐请求 ID
     * @param requests           创建请求列表
     * @return 创建后的候选响应列表
     */
    @Transactional
    public List<SchemeCandidateResponse> batchCreate(String recommendRequestId, List<SchemeCandidateCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Collections.emptyList();
        }
        String createdBy = SecurityOperatorContext.currentUsername();
        LocalDateTime now = LocalDateTime.now();
        List<SchemeCandidate> candidates = requests.stream()
            .map(req -> {
                assertRspuExists(req.getRspuId());
                SchemeCandidate c = new SchemeCandidate();
                c.setCandidateId(UUID.randomUUID().toString());
                c.setRecommendRequestId(recommendRequestId);
                c.setRspuId(req.getRspuId());
                c.setRskuId(req.getRskuId());
                c.setScore(req.getScore());
                c.setAiReason(req.getAiReason());
                c.setMatchFactors(toJson(req.getMatchFactors()));
                c.setStatus("pending");
                c.setCreatedBy(createdBy);
                c.setCreatedAt(now);
                c.setUpdatedAt(now);
                return c;
            })
            .toList();
        candidateMapper.insertBatch(candidates);
        return enrich(candidates);
    }

    /**
     * 更新候选。
     *
     * @param candidateId 候选 ID
     * @param request     更新请求
     * @return 更新后的候选响应
     */
    @Transactional
    public SchemeCandidateResponse update(String candidateId, SchemeCandidateUpdateRequest request) {
        SchemeCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new ResourceNotFoundException("候选不存在: " + candidateId);
        }
        assertOwnerOrAdmin(candidate);

        if (request.getRskuId() != null) {
            candidate.setRskuId(request.getRskuId());
        }
        if (request.getScore() != null) {
            candidate.setScore(request.getScore());
        }
        if (request.getAiReason() != null) {
            candidate.setAiReason(request.getAiReason());
        }
        if (request.getMatchFactors() != null) {
            candidate.setMatchFactors(toJson(request.getMatchFactors()));
        }
        if (StringUtils.hasText(request.getStatus())) {
            candidate.setStatus(request.getStatus());
        }
        candidate.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        return enrichSingle(candidate);
    }

    /**
     * 删除候选。
     *
     * @param candidateId 候选 ID
     */
    @Transactional
    public void delete(String candidateId) {
        SchemeCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new ResourceNotFoundException("候选不存在: " + candidateId);
        }
        assertOwnerOrAdmin(candidate);
        candidateMapper.deleteById(candidateId);
    }

    private void assertRspuExists(String rspuId) {
        if (rspuMapper.selectById(rspuId) == null) {
            throw new ResourceNotFoundException("RSPU 不存在: " + rspuId);
        }
    }

    private void assertOwnerOrAdmin(SchemeCandidate candidate) {
        if (SecurityOperatorContext.isCurrentUserAdmin()) {
            return;
        }
        String currentUser = SecurityOperatorContext.currentUsername();
        if (!currentUser.equals(candidate.getCreatedBy())) {
            throw new BusinessException("无权操作该候选");
        }
    }

    private List<SchemeCandidateResponse> enrich(List<SchemeCandidate> candidates) {
        List<String> rspuIds = candidates.stream().map(SchemeCandidate::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = rspuIds.isEmpty()
            ? Map.of()
            : rspuMapper.selectList(new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds))
                .stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(rspuIds);
        return candidates.stream()
            .map(c -> buildResponse(c, rspuMap, imageUrlMap))
            .toList();
    }

    private SchemeCandidateResponse enrichSingle(SchemeCandidate candidate) {
        return enrich(List.of(candidate)).get(0);
    }

    private SchemeCandidateResponse buildResponse(SchemeCandidate c,
                                                  Map<String, RspuMaster> rspuMap,
                                                  Map<String, String> imageUrlMap) {
        RspuMaster rspu = rspuMap.get(c.getRspuId());
        SchemeCandidateResponse response = new SchemeCandidateResponse();
        response.setCandidateId(c.getCandidateId());
        response.setRecommendRequestId(c.getRecommendRequestId());
        response.setRspuId(c.getRspuId());
        response.setRspuName(rspu != null ? rspu.getPositioningLabel() : null);
        response.setPrimaryImageUrl(imageUrlMap.get(c.getRspuId()));
        response.setRskuId(c.getRskuId());
        response.setScore(c.getScore());
        response.setAiReason(c.getAiReason());
        response.setMatchFactors(fromJson(c.getMatchFactors()));
        response.setStatus(c.getStatus());
        response.setCreatedBy(c.getCreatedBy());
        response.setCreatedAt(c.getCreatedAt());
        response.setUpdatedAt(c.getUpdatedAt());
        return response;
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>()
                .in("rspu_id", rspuIds)
                .eq("is_primary", true)
        );
        return images.stream()
            .collect(Collectors.toMap(
                ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(),
                (a, b) -> a
            ));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON 反序列化失败: " + e.getMessage());
        }
    }
}
