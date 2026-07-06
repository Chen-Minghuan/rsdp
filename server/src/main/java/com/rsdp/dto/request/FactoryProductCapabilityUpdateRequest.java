package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新工厂产品能力档案请求。
 */
@Data
public class FactoryProductCapabilityUpdateRequest {

    /** 品类编码。 */
    @NotBlank(message = "品类编码不能为空")
    @Size(max = 16, message = "品类编码不能超过 16 个字符")
    private String categoryCode;

    /** 风格编码。 */
    @Size(max = 16, message = "风格编码不能超过 16 个字符")
    private String styleCode;

    /** 材质编码。 */
    @Size(max = 8, message = "材质编码不能超过 8 个字符")
    private String materialCode;
}
