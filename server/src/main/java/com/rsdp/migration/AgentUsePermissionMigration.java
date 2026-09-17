package com.rsdp.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 营销选品 Agent 权限（agent:use）的存量修正与授权。
 *
 * <p>V10 建立了营销 Agent 数据层，SecurityConfig 要求 /api/v1/agent/** 持有
 * {@code agent:use}；但权限种子在 V1 基线（不可变），Flyway 只做结构演进，
 * 故按「数据修正不走 Flyway」约定，由启动期幂等任务补齐权限行与角色授权
 * （INSERT ... ON CONFLICT DO NOTHING，已存在自动跳过）。</p>
 *
 * <p>授权范围：ADMIN / EDITOR / DESIGNER（内部员工与设计师代录场景）。
 * 客户自助入口待客户角色（ROLE_CUSTOMER）方案确定后按同模式追加。</p>
 *
 * <p>启动期数据修正任务：由 DatabaseMigrationRunner 在应用启动后按序调用一次；
 * 幂等可安全重入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentUsePermissionMigration {

    private final JdbcTemplate jdbcTemplate;

    /** 幂等补建 agent:use 权限并授予 ADMIN/EDITOR/DESIGNER（已授权自动跳过）。 */
    public void execute() {
        int permission = jdbcTemplate.update("""
            INSERT INTO sys_permission (permission_code, permission_name)
            VALUES ('agent:use', '营销选品 Agent')
            ON CONFLICT (permission_code) DO NOTHING
            """);
        int granted = jdbcTemplate.update("""
            INSERT INTO sys_role_permission (role_id, permission_id)
            SELECT r.role_id, p.permission_id
            FROM sys_role r, sys_permission p
            WHERE r.role_code IN ('ADMIN', 'EDITOR', 'DESIGNER')
              AND p.permission_code = 'agent:use'
            ON CONFLICT DO NOTHING
            """);
        log.info("agent:use 权限就绪：权限行新增 {} 条，角色授权新增 {} 条（0 表示已存在跳过）", permission, granted);
    }
}
