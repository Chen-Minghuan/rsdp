package com.rsdp.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 为 Office 商品命令绑定专用的主库服务账号、工作区和工厂。 */
@Data
@Component
@ConfigurationProperties(prefix = "rsdp.office-catalog")
public class OfficeCatalogProperties {
    private String username = "";
    private String workspaceId = "";
    private String factoryCode = "";
}
