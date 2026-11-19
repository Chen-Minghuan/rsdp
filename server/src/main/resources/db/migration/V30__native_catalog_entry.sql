-- Dedicated role for the Office catalog connection. The deployment switches the account after read verification.
INSERT INTO sys_role (role_code, role_name)
VALUES ('OFFICE_CATALOG_READER', 'Office 目录读取服务')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'OFFICE_CATALOG_READER'
  AND p.permission_code = 'product:read'
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS factory_product_entry_receipt (
    factory_code VARCHAR(16) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (factory_code, actor_id, request_id)
);
