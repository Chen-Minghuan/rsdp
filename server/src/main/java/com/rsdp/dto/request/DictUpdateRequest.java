package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 字典项更新请求。
 *
 * <p>字典类型与编码是引用键，不允许修改；仅支持改名称、英文名、别名与排序。</p>
 */
@Data
public class DictUpdateRequest {

    @Size(max = 64, message = "字典名称长度不能超过 64")
    private String dictName;

    @Size(max = 64, message = "字典英文名长度不能超过 64")
    private String dictNameEn;

    /**
     * 同义词别名列表，整体替换；传 null 表示不修改。
     */
    private List<String> aliases;

    private Integer sortOrder;

    /**
     * 备注说明（六维字典为视觉判别要点）；传 null 表示不修改，传空字符串表示清空。
     */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
