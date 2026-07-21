package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 企业信息响应。
 */
@Data
public class CompanyResponse {

    private String companyId;
    private String companyName;
    private String logoImageId;
    private BigDecimal priceRatio;
    private String ownerId;
    private String ownerNickname;
    private String status;
    private Integer memberCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
