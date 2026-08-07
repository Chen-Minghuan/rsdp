package com.rsdp.dto.request;

import com.rsdp.dto.ProductBoundingBox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 一图多产品拆分导入请求：图内产品区域选择。
 *
 * @param regions 选中的产品区域（bbox 为相对坐标 0~1），每个区域独立建档
 */
public record RegionEntryRequest(
    @NotEmpty(message = "请至少选择一个产品区域")
    List<@Valid RegionSelection> regions
) {

    /**
     * 单个产品区域。
     *
     * @param bbox          产品相对位置框（0~1）
     * @param categoryCode  品类码（区域检测预估值，用户可改）
     * @param productName   产品名（区域检测 OCR 预估值，随任务作 pageOcr 合并）
     * @param dimensionText 尺寸文字（区域检测 OCR 预估值，随任务作 pageOcr 合并）
     */
    public record RegionSelection(
        @NotNull(message = "产品区域框不能为空") ProductBoundingBox bbox,
        String categoryCode,
        String productName,
        String dimensionText
    ) {
    }
}
