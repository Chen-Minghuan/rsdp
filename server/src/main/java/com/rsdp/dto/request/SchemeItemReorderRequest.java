package com.rsdp.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 方案明细拖拽排序请求：itemIds 为全部明细按新顺序排列的完整列表。
 */
@Data
public class SchemeItemReorderRequest {

    @NotEmpty(message = "排序列表不能为空")
    private List<Long> itemIds;

    /**
     * 空间覆盖标签（明细 ID → 场景字典码，可空）。
     *
     * <p>仅出现的键会更新 scheme_item.space_tag：值为非空码表示覆盖空间分区，
     * 值为 {@code null}（显式）表示清除覆盖、恢复跟随产品推导；键不出现时不动该列
     * （兼容纯排序调用）。覆盖码不强制校验字典存在（允许先拖入后建字典的兜底）。</p>
     */
    private Map<Long, String> spaceTags;
}
