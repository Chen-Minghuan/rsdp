package com.rsdp.controller;

import com.rsdp.service.ImageService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * 根据图片 ID 获取图片文件。
     *
     * @param imageId 图片 ID
     * @return 图片二进制流
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<Resource> getImage(@PathVariable @NotBlank(message = "图片 ID 不能为空") String imageId) {
        ImageService.LoadedImage loaded = imageService.loadImageResource(imageId);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, loaded.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + imageId + "\"")
            .body(loaded.resource());
    }
}
