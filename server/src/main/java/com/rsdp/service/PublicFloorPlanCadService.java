package com.rsdp.service;

import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.PublicCadAnalyzeResponse;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.PublicFloorPlanTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 官网游客 CAD 户型识别编排服务。
 */
@Service
@RequiredArgsConstructor
public class PublicFloorPlanCadService {

    private final FloorPlanService floorPlanService;
    private final PublicFloorPlanTokenService tokenService;

    /**
     * 创建游客 CAD 异步分析并签发短期访问凭证。
     *
     * @param image      可选 JPG/PNG 参考图
     * @param cad        DWG/DXF 图纸
     * @param sourceName 户型名称，可空
     * @return 分析任务及访问凭证
     */
    public PublicCadAnalyzeResponse analyze(MultipartFile image, MultipartFile cad,
                                             String sourceName) {
        Map<String, String> created = floorPlanService.analyzePublicCad(image, cad, sourceName);
        String analysisId = created.get("analysisId");
        return new PublicCadAnalyzeResponse(
            analysisId, created.get("taskId"), tokenService.generate(analysisId));
    }

    /**
     * 使用短期访问凭证读取游客 CAD 分析状态及结果。
     *
     * @param analysisId  分析批次 ID
     * @param accessToken 短期访问凭证
     * @return 分析详情；图片地址已附带同一访问凭证
     */
    public FloorPlanAnalysisResponse getAnalysis(String analysisId, String accessToken) {
        assertTokenMatches(analysisId, accessToken);
        FloorPlanAnalysisResponse response = floorPlanService.getPublicAnalysis(analysisId);
        response.setImageUrl(withToken(response.getImageUrl(), accessToken));
        response.setReferenceImageUrl(withToken(response.getReferenceImageUrl(), accessToken));
        response.setPreviewUrl(withToken(response.getPreviewUrl(), accessToken));
        return response;
    }

    /**
     * 校验游客凭证是否绑定指定分析记录。
     *
     * @param analysisId  分析批次 ID
     * @param accessToken 短期访问凭证
     */
    public void assertTokenMatches(String analysisId, String accessToken) {
        String tokenAnalysisId = tokenService.resolveAnalysisId(accessToken);
        if (!analysisId.equals(tokenAnalysisId)) {
            throw new ResourceNotFoundException("游客户型分析不存在或访问凭证已失效");
        }
    }

    private String withToken(String url, String accessToken) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "floorPlanToken="
            + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
    }
}
