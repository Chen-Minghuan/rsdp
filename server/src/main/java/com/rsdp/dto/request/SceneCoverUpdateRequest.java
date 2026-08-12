package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网空间场景封面图手配请求（V35）。
 *
 * <p>imageId 传 null 或空白表示清除手配封面（公开端回退产品主图兜底）。</p>
 */
@Data
public class SceneCoverUpdateRequest {

    /** 封面图 ID（image_assets.image_id），null/空白 = 清除手配。 */
    @Size(max = 64)
    private String imageId;
}
