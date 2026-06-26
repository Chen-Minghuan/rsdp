package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.service.AiMatchingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 搭配推荐接口。
 */
@RestController
@RequestMapping("/api/v1/matching")
@RequiredArgsConstructor
public class MatchingController {

    private final AiMatchingService aiMatchingService;

    /**
     * 根据空间类型和预算生成 AI 搭配方案。
     *
     * @param request 请求
     * @return 搭配方案
     */
    @PostMapping("/room-scheme")
    public Result<RoomSchemeResponse> roomScheme(@Valid @RequestBody RoomSchemeRequest request) {
        return Result.ok(aiMatchingService.generateRoomScheme(request));
    }

    /**
     * 以某个产品为锚点推荐搭配产品（预留接口）。
     *
     * @param request 请求
     * @return 占位响应
     */
    @PostMapping("/recommend")
    public Result<Map<String, Object>> recommend(@RequestBody Map<String, String> request) {
        return Result.ok(Collections.singletonMap("message", "功能开发中，后续接入智能体推荐"));
    }
}
