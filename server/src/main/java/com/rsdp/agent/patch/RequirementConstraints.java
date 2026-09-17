package com.rsdp.agent.patch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * 营销 Agent 需求约束档案（对应前端契约）。
 *
 * <p>需求更新不允许模型重生成完整档案：LLM 只输出 {@link RequirementPatch} operations，
 * 由 {@link RequirementPatchReducer} 在本对象上做确定性修改。</p>
 */
@Slf4j
@Data
public class RequirementConstraints {

    /** 共享反序列化器：null 字段不输出；未知字段忽略（前后端契约演进兼容）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 品类编码。 */
    private String categoryCode;

    /** 品类名称（展示用）。 */
    private String categoryName;

    /** 风格。 */
    private String style;

    /** 材质。 */
    private String material;

    /** 颜色。 */
    private String color;

    /** 预算上限。 */
    private BigDecimal budgetMax;

    /** 最大宽度（mm）。 */
    private Integer maxWidthMm;

    /** 最小宽度（mm）。 */
    private Integer minWidthMm;

    /** 沙发形态（如 L 型/一字型/贵妃）。 */
    private String sofaForm;

    /** 空间面积（平方米）。 */
    private BigDecimal areaM2;

    /** 备注（自由文本）。 */
    private String note;

    /**
     * 从 JSON 反序列化需求约束。
     *
     * @param json 约束 JSON，空/空白返回空档案
     * @return 需求约束；解析失败返回空档案（不让坏历史数据阻断会话）
     */
    public static RequirementConstraints fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new RequirementConstraints();
        }
        try {
            return MAPPER.readValue(json, RequirementConstraints.class);
        } catch (Exception e) {
            log.warn("需求约束 JSON 解析失败，按空档案处理，json={}", json, e);
            return new RequirementConstraints();
        }
    }

    /**
     * 序列化为 JSON（null 字段不输出）。
     *
     * @return 约束 JSON
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("需求约束序列化失败", e);
        }
    }

    /** 深拷贝（经 JSON 往返，保持与序列化口径一致）。 */
    public RequirementConstraints copy() {
        return fromJson(toJson());
    }
}
