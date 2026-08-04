package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典项创建请求。
 *
 * <p>开放业务标签类字典（材质/面料/风格/场景/六维/工厂供应链等）的扩展，
 * 业务状态枚举由数据脚本维护。</p>
 */
@Data
public class DictCreateRequest {

    @NotBlank(message = "字典类型不能为空")
    @Size(max = 32, message = "字典类型长度不能超过 32")
    private String dictType;

    /**
     * 字典编码。普通类型为字母数字（服务层归一为大写）；
     * 六维类型（six_dim_*）遵循 V24 编码规范 {品类码}-{中文名}（如 SF-宽厚扶手），由服务层按类型校验。
     */
    @NotBlank(message = "字典编码不能为空")
    @Size(max = 64, message = "字典编码长度不能超过 64")
    private String dictCode;

    @NotBlank(message = "字典名称不能为空")
    @Size(max = 64, message = "字典名称长度不能超过 64")
    private String dictName;

    @Size(max = 64, message = "英文名称长度不能超过 64")
    private String dictNameEn;

    /**
     * 父级编码（如 six_dim_* 类型的所属品类码），可选。
     */
    @Size(max = 32, message = "父级编码长度不能超过 32")
    private String parentCode;

    /**
     * 备注说明（六维字典为视觉判别要点），可选。
     */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
