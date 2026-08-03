package com.rsdp.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.SixDimNormalizationProperties;
import com.rsdp.service.DictResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SixDimNormalizationMigration} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SixDimNormalizationMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SixDimNormalizationProperties properties;

    @Mock
    private DictResolverService dictResolverService;

    private SixDimNormalizationMigration migration;

    @TempDir
    Path reportDir;

    @BeforeEach
    void setUp() {
        migration = new SixDimNormalizationMigration(
            properties, jdbcTemplate, dictResolverService, new ObjectMapper());
    }

    @Test
    void run_whenDisabled_shouldNotQuery() {
        when(properties.isEnabled()).thenReturn(false);

        migration.run();

        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void run_shouldNormalizeFreeTextAndSkipAlreadyNormalized() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);
        when(properties.getReportDir()).thenReturn(reportDir.toString());

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(
                Map.of("rspu_id", "RSPU-1", "category_code", "SF",
                    "six_dim_tags", "{\"A\":\"一字型\",\"B\":\"SF-高靠背\"}"),
                Map.of("rspu_id", "RSPU-2", "category_code", "SF",
                    "six_dim_tags", "{\"B\":\"SF-高靠背\"}")
            ))
            .thenReturn(List.of());
        when(dictResolverService.resolveSixDimCode("A", "SF", "一字型")).thenReturn("SF-一字型");
        when(dictResolverService.resolveSixDimCode("B", "SF", "SF-高靠背")).thenReturn("SF-高靠背");

        migration.run();

        // 仅 RSPU-1 有变化：A 维自由文本归一为字典码，B 维已是字典码原样保留
        ArgumentCaptor<String> tagsCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            eq("UPDATE rspu_master SET six_dim_tags = CAST(? AS jsonb) WHERE rspu_id = ?"),
            tagsCaptor.capture(),
            eq("RSPU-1"));
        assertThat(tagsCaptor.getValue()).isEqualTo("{\"A\":\"SF-一字型\",\"B\":\"SF-高靠背\"}");

        verify(jdbcTemplate, never()).update(anyString(), any(), eq("RSPU-2"));
    }

    @Test
    void run_shouldKeepUnmatchedAndWriteReport() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);
        when(properties.getReportDir()).thenReturn(reportDir.toString());

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(
                Map.of("rspu_id", "RSPU-3", "category_code", "SF",
                    "six_dim_tags", "{\"C\":\"某种奇怪扶手\",\"E\":\"皮革\"}")
            ))
            .thenReturn(List.of());
        when(dictResolverService.resolveSixDimCode("C", "SF", "某种奇怪扶手")).thenReturn(null);

        migration.run();

        // 未命中保留原文、不更新；E 维不参与归一（不调用解析）
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
        verify(dictResolverService, never()).resolveSixDimCode(eq("E"), any(), any());

        // 对账报表包含未命中清单
        try (var files = Files.list(reportDir)) {
            List<Path> reports = files.filter(p -> p.getFileName().toString().startsWith("six-dim-normalization-report-")).toList();
            assertThat(reports).hasSize(1);
            String content = Files.readString(reports.get(0));
            assertThat(content).contains("RSPU-3\tSF\tC\t某种奇怪扶手");
            assertThat(content).contains("未命中: 1");
        }
    }

    @Test
    void run_shouldSkipBlankAndInvalidJson() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);
        when(properties.getReportDir()).thenReturn(reportDir.toString());

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(
                Map.of("rspu_id", "RSPU-4", "category_code", "SF", "six_dim_tags", ""),
                Map.of("rspu_id", "RSPU-5", "category_code", "SF", "six_dim_tags", "not-json")
            ))
            .thenReturn(List.of());

        migration.run();

        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }
}
