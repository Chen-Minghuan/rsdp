package com.rsdp.dto.response;

import lombok.Data;

/**
 * 用户端官网空间入口项（GET /api/v1/public/scenes）。
 */
@Data
public class PublicSceneResponse {

    /** 场景字典码（如 LIVING）。 */
    private String sceneCode;

    /** 场景中文名（如 客厅）。 */
    private String sceneName;

    private String sceneNameEn;

    /** 空间代表图访问地址（该场景下最新在售产品的主图，可能为空）。 */
    private String imageUrl;
}
