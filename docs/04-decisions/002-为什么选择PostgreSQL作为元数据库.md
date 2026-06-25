## 背景

RSDP 需要支持多用户并发录入、复杂条件查询、JSON 字段检索，以及未来可能的多机部署。

## 决策

选择 PostgreSQL 作为元数据库，原因：

- 支持高并发读写，适合多用户同时录入产品。
- 原生支持 JSON/JSONB，便于存储和查询六维标签、空间约束等半结构化数据。
- 成熟的备份、恢复、监控生态。
- 与 SpringBoot + MyBatis-Plus 集成成熟。

## 影响

- 需要维护 PostgreSQL 服务（通过 Docker Compose 简化）。
- 部署成本略高于 SQLite，但性能和可扩展性更好。
