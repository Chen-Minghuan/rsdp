package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 当前登录用户偏好更新请求。
 *
 * <p>仅允许用户修改自己的偏好设置，当前仅支持「显示全产品库（去重）」开关。</p>
 */
@Data
public class UserPreferenceUpdateRequest {

    @NotNull(message = "显示全产品库开关不能为空")
    private Boolean viewFullCatalog;
}
