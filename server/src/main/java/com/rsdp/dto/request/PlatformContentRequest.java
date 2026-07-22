package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网内容配置创建/更新请求。code 创建后不可修改。
 */
@Data
public class PlatformContentRequest {

    @NotBlank(message = "内容编码不能为空")
    @Pattern(regexp = "^[a-z0-9_]{2,64}$", message = "内容编码仅允许小写字母/数字/下划线")
    private String code;

    @Size(max = 128)
    private String title;

    /** image=单图 / rich_text=富文本 / embed=嵌入代码 */
    @Size(max = 16)
    private String contentType;

    private String content;

    @Size(max = 16)
    private String status;
}
