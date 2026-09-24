package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 扩展家具品类的发布与旁路识别开关。
 *
 * <p>{@code enabled=false} 时扩展码不会出现在正式业务字典读取结果中；
 * {@code shadowEnabled=true} 仅开启旁路识别和结果记录，不会回写 RSPU。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.ai.category.extended")
public class ExtendedCategoryProperties {

    /** 扩展品类是否进入正式录入、导入与 AI 分类候选集。 */
    private boolean enabled = false;

    /** 是否开启只写旁路表的 Shadow Mode。 */
    private boolean shadowEnabled = false;

    /** 受开关保护的扩展品类码。 */
    private List<String> codes = new ArrayList<>(List.of("DK", "MT", "MR", "RG", "PD", "CW"));

    /**
     * 判断给定品类码是否属于受保护的扩展品类。
     *
     * @param categoryCode 品类码
     * @return 属于扩展品类时为 true
     */
    public boolean isExtendedCode(String categoryCode) {
        return categoryCode != null && codes.stream().anyMatch(categoryCode::equalsIgnoreCase);
    }
}
