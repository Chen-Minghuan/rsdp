package com.rsdp.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DESIGNER 补授产品集权限（{@code collection:create/update/delete}）的存量修正。
 *
 * <p>P2 在 {@code database/schema/zz_seed.sql} 与 {@code ops/reset_db.sql} 为 DESIGNER
 * 开放了产品集自建权限，但种子只在全新初始化时执行，存量库重启不会补齐。
 * 按「数据修正不走 Flyway」约定，由启动期幂等任务补齐（INSERT ... ON CONFLICT
 * DO NOTHING，已授权环境自动跳过）。</p>
 *
 * <p>启动期数据修正任务：由 DatabaseMigrationRunner 在应用启动后按序调用一次；
 * 幂等可安全重入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesignerCollectionPermissionMigration {

    private final JdbcTemplate jdbcTemplate;

    /** 为 DESIGNER 幂等补授 collection:create/update/delete 权限（已授权自动跳过）。 */
    public void execute() {
        int inserted = jdbcTemplate.update("""
            INSERT INTO sys_role_permission (role_id, permission_id)
            SELECT r.role_id, p.permission_id
            FROM sys_role r, sys_permission p
            WHERE r.role_code = 'DESIGNER'
              AND p.permission_code IN ('collection:create', 'collection:update', 'collection:delete')
            ON CONFLICT DO NOTHING
            """);
        log.info("DESIGNER 产品集权限补授完成：新增 {} 条（0 表示已授权跳过）", inserted);
    }
}
