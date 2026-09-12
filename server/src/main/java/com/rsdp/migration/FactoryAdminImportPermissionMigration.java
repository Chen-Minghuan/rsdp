package com.rsdp.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FACTORY_ADMIN 补授 {@code product:import} 权限的存量修正。
 *
 * <p>阶段 1.3 在 {@code database/schema/zz_seed.sql} 与 {@code ops/reset_db.sql}
 * 为 FACTORY_ADMIN 开放了 {@code product:import}，但 Flyway V1 基线不可变、
 * 无后续 V 脚本补授权，导致走 Flyway 初始化的环境与存量库实际无此权限。
 * 按「数据修正不走 Flyway」约定，由启动期幂等任务补齐（INSERT ... ON CONFLICT
 * DO NOTHING，已授权环境自动跳过）。</p>
 *
 * <p>启动期数据修正任务：由 DatabaseMigrationRunner 在应用启动后按序调用一次；
 * 幂等可安全重入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactoryAdminImportPermissionMigration {

    private final JdbcTemplate jdbcTemplate;

    /** 为 FACTORY_ADMIN 幂等补授 product:import 权限（已授权自动跳过）。 */
    public void execute() {
        int inserted = jdbcTemplate.update("""
            INSERT INTO sys_role_permission (role_id, permission_id)
            SELECT r.role_id, p.permission_id
            FROM sys_role r, sys_permission p
            WHERE r.role_code = 'FACTORY_ADMIN'
              AND p.permission_code = 'product:import'
            ON CONFLICT DO NOTHING
            """);
        log.info("FACTORY_ADMIN product:import 权限补授完成：新增 {} 条（0 表示已授权跳过）", inserted);
    }
}
