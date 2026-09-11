package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 导入批次/文件生命周期清理（数据保留策略）参数。
 *
 * <p>策略口径（阶段 3.3）：pending 批次（预览后放弃）超 {@link #pendingBatchMaxAgeHours}
 * 小时整批删除（批次行 + 行记录 + 原始文件 + 预览临时图）；done/failed 批次行与导入结果
 * 永久保留，但原始文件与 preview_rows 超 {@link #completedBatchFileRetentionDays} 天清理；
 * importing/processing 中的批次绝不处理；软删图片孤儿文件清理默认关闭。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.data-retention")
public class DataRetentionProperties {

    /** 清理任务总开关。 */
    private boolean enabled = true;

    /** pending 批次保留时长（小时），超期整批删除。 */
    private int pendingBatchMaxAgeHours = 24;

    /** done/failed 批次原始文件与 preview_rows 保留天数，超期清理（批次行与结果保留）。 */
    private int completedBatchFileRetentionDays = 7;

    /** 单次调度每类批次处理上限（防长事务/长运行，余量下个周期继续）。 */
    private int batchSize = 100;

    /** 软删图片孤儿存储文件清理开关（历史遗留数据，默认关闭，确认后手动打开）。 */
    private boolean orphanImageCleanupEnabled = false;

    /** 软删超过该天数的 image_assets 对应存储文件才物理删除（行保留不删）。 */
    private int orphanImageRetentionDays = 30;

    /** 孤儿文件清理每批上限。 */
    private int orphanImageBatchSize = 200;
}
