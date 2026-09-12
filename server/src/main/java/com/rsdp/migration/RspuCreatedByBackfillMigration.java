package com.rsdp.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * rspu_master.created_by 存量回填迁移（阶段 4.2：RSPU 归属溯源）。
 *
 * <p>V3 新增 {@code rspu_master.created_by}（sys_user.user_id）后，历史行全部为 NULL。
 * 本任务从 audit_log（table_name='rspu_master'、action='CREATE' 的首条记录）取操作人
 * username，join sys_user 反查 user_id 尽力回填；查不到对应用户的历史行
 * （anonymous/system 操作、用户已删）保持 NULL，口径为「历史未知」，不编造归属。</p>
 *
 * <p>启动期数据修正任务：由 DatabaseMigrationRunner 在应用启动后按序调用一次；
 * 幂等可安全重入（仅处理 created_by IS NULL 的行，已回填自动跳过）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RspuCreatedByBackfillMigration {

    private final JdbcTemplate jdbcTemplate;

    /** 回填 rspu_master.created_by（仅 NULL 行，audit_log 反查，幂等）。 */
    public void execute() {
        int updated = jdbcTemplate.update("""
            UPDATE rspu_master m
            SET created_by = u.user_id
            FROM sys_user u
            WHERE m.created_by IS NULL
              AND u.username = (
                  SELECT a.operator
                  FROM audit_log a
                  WHERE a.table_name = 'rspu_master'
                    AND a.record_id = m.rspu_id
                    AND a.action = 'CREATE'
                  ORDER BY a.created_at ASC
                  LIMIT 1
              )
            """);
        log.info("rspu_master.created_by 存量回填完成：回填 {} 条（剩余 NULL 行为历史未知，保持留空）", updated);
    }
}
