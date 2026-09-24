package com.rsdp.controller;

import com.rsdp.service.ImageService;
import com.rsdp.service.OrderInviteService;
import com.rsdp.security.PublicFloorPlanTokenService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图片访问接口。
 */
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
@Validated
public class ImageController {

    private final ImageService imageService;
    private final OrderInviteService orderInviteService;
    private final PublicFloorPlanTokenService publicFloorPlanTokenService;

    /**
     * 根据图片 ID 获取图片文件。
     *
     * <p>支持两种访问模式：
     * <ul>
     *   <li>携带 {@code inviteToken}：免登录访问，仅允许查看该邀请订单关联的图片。</li>
     *   <li>携带 {@code floorPlanToken}：免登录访问该凭证绑定的游客户型原图或预览。</li>
     *   <li>均未携带：已登录用户按数据权限访问。</li>
     * </ul>
     *
     * @param imageId     图片 ID
     * @param inviteToken 订单邀请 token（可选）
     * @param floorPlanToken 游客户型访问凭证（可选）
     * @return 图片二进制流
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<Resource> getImage(
        @PathVariable @NotBlank(message = "图片 ID 不能为空") String imageId,
        @RequestParam(name = "inviteToken", required = false) String inviteToken,
        @RequestParam(name = "floorPlanToken", required = false) String floorPlanToken) {
        ImageService.LoadedImage loaded;
        if (StringUtils.hasText(inviteToken)) {
            String orderId = orderInviteService.resolveOrderIdFromToken(inviteToken);
            loaded = imageService.loadImageResourceForInvite(imageId, orderId);
        } else if (StringUtils.hasText(floorPlanToken)) {
            String analysisId = publicFloorPlanTokenService.resolveAnalysisId(floorPlanToken);
            loaded = imageService.loadImageResourceForPublicFloorPlan(imageId, analysisId);
        } else {
            loaded = imageService.loadImageResource(imageId);
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, loaded.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + imageId + "\"")
            .body(loaded.resource());
    }
}
