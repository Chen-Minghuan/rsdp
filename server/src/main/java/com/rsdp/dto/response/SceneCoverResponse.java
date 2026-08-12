package com.rsdp.dto.response;

import lombok.Data;

/**
 * 官网空间场景封面图响应（管理端，V35）。
 */
@Data
public class SceneCoverResponse {

    /** 场景字典码。 */
    private String code;

    /** 场景中文名。 */
    private String name;

    /** 手配封面图 ID（image_assets.image_id），未手配为 null。 */
    private String imageId;

    /** 封面图访问地址（/api/v1/images/{imageId}），未手配为 null。 */
    private String imageUrl;
}
