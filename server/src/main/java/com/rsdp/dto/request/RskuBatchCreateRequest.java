package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量创建 RSKU 报价请求。
 *
 * <p>支持为同一 RSPU 的多个变体一次性绑定多家工厂的报价。每家工厂的报价信息（价格、交期等）
 * 相同地应用到所有选中的变体，符合"同一产品不同变体同厂同价、不同工厂价格不同"的业务习惯。</p>
 */
@Data
public class RskuBatchCreateRequest {

    @NotEmpty(message = "至少选择一个变体")
    private List<@NotBlank(message = "变体 ID 不能为空") String> variantIds;

    @NotEmpty(message = "至少选择一家工厂")
    private List<@Valid RskuBatchFactoryQuote> factoryQuotes;

    /** 产品等级，为空时按 变体 > RSPU 继承。 */
    private String productLevel;

    /** 工厂无对应能力等级时，是否自动扩展工厂能力。 */
    private Boolean autoExtendCapability;
}
