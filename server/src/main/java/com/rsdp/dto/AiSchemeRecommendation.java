package com.rsdp.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 搭配方案推荐结果（用于解析 AI 返回的 JSON）。
 */
@Data
public class AiSchemeRecommendation {

    private List<String> rspuIds;
    private String reasoning;
}
