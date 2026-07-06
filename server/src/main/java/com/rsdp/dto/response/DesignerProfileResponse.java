package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设计师画像响应。
 */
@Data
public class DesignerProfileResponse {

    private String profileId;
    private String userId;
    private String username;
    private String realName;
    private String avatarUrl;
    private List<String> specialties;
    private List<String> preferredStyles;
    private List<String> preferredCategories;
    private String priceSensitivity;
    private String location;
    private String companyName;
    private String contactPhone;
    private String bio;
    private BigDecimal defaultBudgetMin;
    private BigDecimal defaultBudgetMax;
    private Boolean isPublic;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
