package com.rsdp.floorplan.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.exception.BusinessException;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.floorplan.parser.dto.CadParseResultValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;

/**
 * CAD 户型解析器（CAD 户型导入 P3，docs/08-roadmap/CAD户型导入架构设计.md §4.1）：
 * 调 rsdp-cad-parser 微服务（POST {baseUrl}/parse，multipart 字段名 file），
 * 把 CadParseResult 归一化为 {@link FloorPlanParseResult}。
 *
 * <p>解析失败（success=false / HTTP 错误 / 服务不可达）一律抛带中文可读提示的
 * {@link BusinessException}，由异步任务体系落 failed。</p>
 */
@Slf4j
@Component
public class CadFloorPlanParser implements FloorPlanParser {

    private final RestClient cadParserRestClient;
    private final ObjectMapper objectMapper;

    /** P1-C 家具视觉语义回填开关（开启时向解析器请求 isolation 视图）。 */
    @org.springframework.beans.factory.annotation.Value("${rsdp.floor-plan.furniture-vision-enabled:true}")
    private boolean furnitureVisionEnabled;

    public CadFloorPlanParser(@Qualifier("cadParserRestClient") RestClient cadParserRestClient,
                              ObjectMapper objectMapper) {
        this.cadParserRestClient = cadParserRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(FloorPlanFileType fileType) {
        return fileType == FloorPlanFileType.CAD;
    }

    @Override
    public FloorPlanParseResult parse(FloorPlanParseRequest request) {
        String fileName = StringUtils.hasText(request.fileName()) ? request.fileName() : "floor-plan.dwg";
        ByteArrayResource fileResource = new ByteArrayResource(request.fileBytes()) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        // CAD Scene Parser 升级（V1.2.3 §8.2）：target-scene 切换重解析时 multipart 附带 targetId
        if (StringUtils.hasText(request.targetId())) {
            body.add("targetId", request.targetId());
        }
        // P1-C：请求家具视觉语义分组的 isolation 视图（visionGroupId → 白底线稿 PNG）
        if (furnitureVisionEnabled) {
            body.add("withIsolation", "true");
        }

        CadParseResult result;
        try {
            result = cadParserRestClient.post()
                .uri("/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(CadParseResult.class);
        } catch (ResourceAccessException e) {
            // 连接失败/超时：服务未启动或网络不可达
            log.error("CAD 解析服务不可达，fileName={}", fileName, e);
            throw new BusinessException("CAD 解析服务连接失败，请确认 rsdp-cad-parser 服务已启动后重试");
        } catch (RestClientResponseException e) {
            // 服务返回非 2xx（如 422 解析失败）：尽力透传服务端中文错误信息
            log.error("CAD 解析服务返回错误，fileName={}，status={}", fileName, e.getStatusCode(), e);
            throw new BusinessException("CAD 图纸解析失败: " + extractServerErrorMessage(e));
        } catch (RestClientException e) {
            log.error("CAD 解析调用异常，fileName={}", fileName, e);
            throw new BusinessException("CAD 图纸解析失败: " + e.getMessage());
        }

        if (result == null) {
            throw new BusinessException("CAD 图纸解析失败: 服务返回空结果");
        }
        if (!result.isSuccess()) {
            String message = StringUtils.hasText(result.getErrorMessage())
                ? result.getErrorMessage() : "未知错误";
            log.warn("CAD 解析失败，fileName={}，errorCode={}，errorMessage={}",
                fileName, result.getErrorCode(), result.getErrorMessage());
            throw new BusinessException("CAD 图纸解析失败: " + message);
        }
        // Scene 契约校验（V1.2.3 §18.6）：对象 ID/坐标/轮廓闭合/尺寸/枚举，违例即解析失败
        CadParseResultValidator.validate(result);
        byte[] previewBytes = null;
        if (result.getPreview() != null && StringUtils.hasText(result.getPreview().getPngBase64())) {
            try {
                previewBytes = Base64.getDecoder().decode(result.getPreview().getPngBase64());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("CAD 图纸解析失败: 规范预览数据损坏");
            }
            // 大体积二进制仅用于存储，不写入 floor_plan_analysis.raw_result。
            result.getPreview().setPngBase64(null);
        }
        // 候选预览 pngBase64 同样剥离（Invariant 12：raw_result 不存二进制）
        if (result.getTargetSceneCandidates() != null) {
            for (CadParseResult.TargetSceneCandidate candidate : result.getTargetSceneCandidates()) {
                if (candidate.getPreview() != null) {
                    candidate.getPreview().setPngBase64(null);
                }
            }
        }
        return FloorPlanParseResult.cad(result, previewBytes);
    }

    /**
     * 从非 2xx 响应体中提取服务端错误信息（响应体同为 CadParseResult 契约）。
     *
     * @param e HTTP 状态异常
     * @return 中文可读错误信息
     */
    private String extractServerErrorMessage(RestClientResponseException e) {
        try {
            String responseBody = e.getResponseBodyAsString();
            if (StringUtils.hasText(responseBody)) {
                CadParseResult errorResult = objectMapper.readValue(responseBody, CadParseResult.class);
                if (StringUtils.hasText(errorResult.getErrorMessage())) {
                    return errorResult.getErrorMessage();
                }
            }
        } catch (Exception parseException) {
            log.warn("解析 CAD 服务错误响应体失败", parseException);
        }
        return "服务返回 " + e.getStatusCode();
    }
}
