package com.rsdp.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.SixDimNormalizationProperties;
import com.rsdp.service.DictResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量六维标签归一迁移。
 *
 * <p>将 {@code rspu_master.six_dim_tags} 中的自由文本值按"品类 + 维度"批量归一为
 * 带品类前缀的字典码（如 {@code 宽厚扶手 → SF-宽厚扶手}），复用
 * {@link DictResolverService#resolveSixDimCode} 的四级匹配
 * （前缀码直通 → 枚举名/英文名 → 别名 → "其他"兜底），纯规则、零 AI 成本。</p>
 *
 * <p>已是字典码的值幂等跳过；未命中的值保留原文，输出对账报表
 * （总数 / 归一率 / 未命中清单文件，供字典运营补充枚举或别名后重跑）。
 * E 维（表面材质）不枚举，不参与归一。</p>
 *
 * <p>通过 {@code rsdp.migration.six-dim-normalization.enabled=true} 开启，默认关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SixDimNormalizationMigration implements CommandLineRunner {

    /** 参与归一的维度（E 维表面材质为自由文本，不枚举）。 */
    private static final List<String> DIM_KEYS = List.of("A", "B", "C", "D", "F");

    private final SixDimNormalizationProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final DictResolverService dictResolverService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            log.info("存量六维标签归一迁移已禁用（rsdp.migration.six-dim-normalization.enabled=false）");
            return;
        }

        log.info("开始存量六维标签归一迁移，每批 {} 条", properties.getBatchSize());
        migrate();
        log.info("存量六维标签归一迁移完成");
    }

    private void migrate() {
        int offset = 0;
        int totalRows = 0;
        int totalValues = 0;
        int totalAlreadyNormalized = 0;
        int totalMigrated = 0;
        int totalFailed = 0;
        List<String> unmatchedLines = new ArrayList<>();
        int batchSize = properties.getBatchSize();

        while (true) {
            String pagedSql = "SELECT rspu_id, category_code, six_dim_tags FROM rspu_master "
                + "WHERE six_dim_tags IS NOT NULL AND deleted_at IS NULL "
                + "ORDER BY rspu_id LIMIT " + batchSize + " OFFSET " + offset;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(pagedSql);
            if (rows.isEmpty()) {
                break;
            }

            for (Map<String, Object> row : rows) {
                String rspuId = (String) row.get("rspu_id");
                String categoryCode = (String) row.get("category_code");
                String rawTags = (String) row.get("six_dim_tags");
                if (rawTags == null || rawTags.isBlank()) {
                    continue;
                }

                Map<String, Object> tags = parseTags(rawTags);
                if (tags == null) {
                    totalFailed++;
                    log.error("RSPU {} 的 six_dim_tags 无法解析为 JSON 对象: {}", rspuId, rawTags);
                    continue;
                }

                boolean changed = false;
                for (String dimKey : DIM_KEYS) {
                    Object rawValue = tags.get(dimKey);
                    if (rawValue == null) {
                        continue;
                    }
                    String value = String.valueOf(rawValue).trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    totalValues++;
                    String resolved = dictResolverService.resolveSixDimCode(dimKey, categoryCode, value);
                    if (resolved == null) {
                        unmatchedLines.add(String.join("\t", rspuId, String.valueOf(categoryCode), dimKey, value));
                    } else if (!resolved.equals(value)) {
                        tags.put(dimKey, resolved);
                        changed = true;
                        totalMigrated++;
                    } else {
                        totalAlreadyNormalized++;
                    }
                }

                if (!changed) {
                    continue;
                }
                try {
                    jdbcTemplate.update(
                        "UPDATE rspu_master SET six_dim_tags = CAST(? AS jsonb) WHERE rspu_id = ?",
                        objectMapper.writeValueAsString(tags), rspuId);
                } catch (Exception e) {
                    totalFailed++;
                    log.error("RSPU {} 六维标签归一更新失败", rspuId, e);
                }
            }

            totalRows += rows.size();
            offset += rows.size();
            log.info("六维归一批次：偏移 {}，本批 {} 条", offset - rows.size(), rows.size());
        }

        int normalized = totalAlreadyNormalized + totalMigrated;
        double rate = totalValues == 0 ? 100.0 : normalized * 100.0 / totalValues;
        log.info("六维归一对账：RSPU 总数 {}，维度值总数 {}，已是字典码 {}，本次归一 {}，未命中 {}，失败 {}，归一率 {}%",
            totalRows, totalValues, totalAlreadyNormalized, totalMigrated,
            unmatchedLines.size(), totalFailed, String.format("%.1f", rate));
        writeReport(totalRows, totalValues, totalAlreadyNormalized, totalMigrated, totalFailed, unmatchedLines);
    }

    /**
     * 解析 six_dim_tags JSON 对象；解析失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawTags, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 输出对账报表文件：摘要 + 未命中清单（TSV：rspuId / 品类 / 维度 / 原值）。
     */
    private void writeReport(int totalRows, int totalValues, int alreadyNormalized,
                             int migrated, int failed, List<String> unmatchedLines) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportDir = Path.of(properties.getReportDir());
            Files.createDirectories(reportDir);
            Path report = reportDir.resolve("six-dim-normalization-report-" + timestamp + ".txt");

            int normalized = alreadyNormalized + migrated;
            double rate = totalValues == 0 ? 100.0 : normalized * 100.0 / totalValues;
            List<String> lines = new ArrayList<>();
            lines.add("# 存量六维标签归一对账报表 " + timestamp);
            lines.add("RSPU 总数: " + totalRows);
            lines.add("维度值总数(A/B/C/D/F): " + totalValues);
            lines.add("已是字典码: " + alreadyNormalized);
            lines.add("本次归一: " + migrated);
            lines.add("未命中: " + unmatchedLines.size());
            lines.add("失败: " + failed);
            lines.add(String.format("归一率: %.1f%%", rate));
            lines.add("");
            lines.add("# 未命中清单（rspu_id\t品类\t维度\t原值）");
            lines.addAll(unmatchedLines);
            Files.write(report, lines);
            log.info("六维归一对账报表已输出: {}", report.toAbsolutePath());
        } catch (Exception e) {
            log.warn("六维归一对账报表写入失败", e);
        }
    }
}
