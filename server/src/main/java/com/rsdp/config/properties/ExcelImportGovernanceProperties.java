package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 导入治理配置（设计文档 v3.3，P0-B3）。
 *
 * <p>覆盖两块：</p>
 * <ul>
 *   <li><b>hmac-keys / active-hmac-key-version</b>（§42.5 / §43.2）：敏感值 HMAC
 *       版本化 keyring。Key 只经环境变量 / 启动注入进入应用，禁止入库入仓；
 *       新值永远用 Active 版本签发，旧版本保留「仅验证」能力直到引用计数为零；</li>
 *   <li><b>snapshot-gc.*</b>（§44）：受限 Snapshot GC 的保留期与分页参数。</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.excel-import")
public class ExcelImportGovernanceProperties {

    /**
     * 敏感值 HMAC keyring：version → Base64 编码密钥（≥ 32 字节）。
     * 生产环境必须经环境变量注入（如 {@code RSDP_EXCEL_IMPORT_HMAC_KEYS_1}）。
     * 为空时开发环境启动生成一次性临时 Key（重启后历史摘要不可验证，仅用于本地联调）。
     */
    private Map<Integer, String> hmacKeys = new LinkedHashMap<>();

    /**
     * 当前 Active HMAC Key Version（新 Sensitive Value 永远用它签发）。
     */
    private int activeHmacKeyVersion = 1;

    /**
     * 受限 Snapshot GC（§44.2）。
     */
    private final SnapshotGc snapshotGc = new SnapshotGc();

    @Getter
    @Setter
    public static class SnapshotGc {
        /** 是否启用定时 GC（管理员也可手动触发服务方法）。 */
        private boolean enabled = true;
        /** 未 Confirm 快照保留天数（§44：180 天）。 */
        private int unconfirmedRetentionDays = 180;
        /** 已 Confirm / Import 快照保留天数（§44：至少 365 天，不早于 Attempt 保留期）。 */
        private int confirmedRetentionDays = 365;
        /** 单次运行扫描的候选批次上限（分页提交，避免长事务 / WAL 峰值）。 */
        private int batchSize = 20;
    }
}
