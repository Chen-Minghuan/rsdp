package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Excel AI 导入：品类策略 / 行级最终品类的 Preview Edit 命令（P0-B2，09 方案 v2.3 §16/§17 +
 * 设计文档 v3.3 §4.1/§46/§47）。
 *
 * <p>用户对 mode / categoryHint / candidateCategoryCodes / rowCategorySelections 的修改
 * 通过本命令物化为新的 immutable Preview Revision（preview_revision + 1），
 * 不再是只随确认导入请求携带的临时参数。</p>
 *
 * <p>{@code expectedRevision} 为乐观锁版本号（§47）：与批次当前 preview_revision 不一致时
 * 返回 409 preview_revision_conflict，客户端需重新读取最新 Snapshot 后重试；
 * {@code null} 表示期望批次尚无快照（兼容 B2 前创建的旧批次，此时创建 revision=1）。</p>
 */
@Data
public class ExcelAiCategoryRevisionRequest {

    /**
     * 期望的当前 Preview Revision（乐观锁）；null = 期望尚无快照。
     */
    private Long expectedRevision;

    /**
     * 导入方式：SINGLE 单一品类 / MIXED 混合品类（本命令必须显式选择）。
     */
    @NotNull
    private CategoryMode categoryMode;

    /**
     * SINGLE 的默认品类（与现有「品类提示」同一字段）。
     */
    private String categoryHint;

    /**
     * MIXED 候选品类码列表（≥2 个）。
     */
    private List<String> candidateCategoryCodes;

    /**
     * 用户确认后的字段映射（原始表头 → 系统字段）；缺省时回退批次预览时保存的映射。
     * 参与 mapping_hash 计算与行内类别列确定性归一。
     */
    private Map<String, String> mapping;

    /**
     * 用户确认的品类映射（品类原始值 → 字典码）；行内类别列确定性归一的最高优先级。
     */
    private Map<String, String> categoryMapping;

    /**
     * 数据清洗页的行级最终品类：Excel 物理行号（1-based）→ 品类字典码（§17 唯一行级载体；
     * 组级/批量设置由前端展开为行级集合后提交）。
     */
    private Map<Integer, String> rowCategorySelections;

    /** Cell corrections sealed into the next revision before import. */
    private List<PreviewEdit> previewEdits;

    /** Physical Excel rows excluded from the next revision. */
    private List<Integer> skipRows;
}
