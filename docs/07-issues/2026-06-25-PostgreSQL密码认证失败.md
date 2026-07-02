**日期**：2026-06-25

**影响范围**：开发环境（dev profile），应用启动后 DataSource 健康检查报 503

---

## 现象

Spring Boot 应用启动日志中反复出现：

```
WARN  o.s.b.a.j.DataSourceHealthIndicator - DataSource health check failed
Caused by: org.postgresql.util.PSQLException: FATAL: password authentication failed for user "rsdp"
```

`/actuator/health` 返回 503，db 组件状态 DOWN。

---

## 原因

操作系统环境变量 `DB_PASSWORD` 被设置为与数据库实际密码不一致的值。

`application-dev.yml` 配置了占位符取值：

```yaml
password: ${DB_PASSWORD:rsdp}
```

Spring Boot 优先读取环境变量，若环境变量为 `123456`，则实际连接密码为 `123456`，而数据库中 `rsdp` 用户密码可能仍为 `rsdp`，导致不匹配。

从 Windows 宿主机通过 `localhost:5433` 连接 Docker 容器中的 PostgreSQL 时，源 IP 为 Docker 网桥地址（如 `172.17.0.1`），命中 `pg_hba.conf` 末尾的 `host all all all scram-sha-256` 规则，必须验证密码。

---

## 排查过程

1. 确认 Docker 容器状态：
   ```powershell
   docker ps
   ```
2. 容器内直接连接测试（Unix socket 免密）：
   ```powershell
   docker exec rsdp-postgres psql -U rsdp -d rsdp -c "SELECT 1 AS connected;"
   ```
3. 检查操作系统环境变量：
   ```powershell
   [Environment]::GetEnvironmentVariable("DB_PASSWORD", "User")
   ```
4. 检查 `server/src/main/resources/application-dev.yml` 中的密码配置。

---

## 解决方法

确保以下三处密码一致：

1. `deploy/.env` 或 Docker Compose 中的 `POSTGRES_PASSWORD`（数据库中 rsdp 用户的实际密码）。
2. `server/src/main/resources/application-dev.yml` 中的 `spring.datasource.password`。
3. 数据库中 `rsdp` 用户的实际密码。

修改数据库用户密码示例：

```sql
ALTER USER rsdp WITH PASSWORD 'rsdp';
```

在容器内执行：

```powershell
docker exec -it rsdp-postgres psql -U postgres -c "ALTER USER rsdp WITH PASSWORD 'rsdp';"
```

---

## 经验总结

1. Spring Boot `${VAR:default}` 占位符中，**环境变量优先于默认值**。
2. Docker 端口映射场景下，宿主机连接容器数据库时源 IP 不是 `127.0.0.1`，不能依赖 `trust` 认证。
3. 排查密码认证失败时，不仅要检查配置文件，还要确认操作系统环境变量是否干扰。

## 相关配置

- 配置文件：`server/src/main/resources/application-dev.yml`
- Docker 容器：`rsdp-postgres`（PostgreSQL 16）
