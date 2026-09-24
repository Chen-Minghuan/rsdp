package com.rsdp.dto;

/**
 * 扩展品类 Shadow Mode 的模型预测。
 *
 * @param categoryCode 预测一级品类码
 * @param productType  预测二级产品类型码，可空
 * @param confidence   模型置信等级
 * @param reason       与正式品类的差异说明
 */
public record CategoryShadowPrediction(
    String categoryCode,
    String productType,
    String confidence,
    String reason
) {
}
