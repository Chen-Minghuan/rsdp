# database/ 目录地图与执行手册

RSDP 数据库脚本按域组织：**schema/ 是基线 DDL，也是当前库结构的唯一真相**
> **时间列约定**：所有时间列统一为 `TIMESTAMPTZ`（存 UTC、按会话时区渲染），不要新增无时区的 `TIMESTAMP` 列。写入/读取由后端 `PgLocalDateTimeTypeHandler` 完成（JVM 默认时区必须与业务时区一致，compose/Dockerfile 已固定 `TZ=Asia/Shanghai`）。
（由原 V1__init_db.sql 按域拆分而来，历史增量 V2~V45 已全部并入并随归档删除，演进记录见 git 历史），**ops/ 是运维脚本**。开发期没有"存量环境升级"负担，改结构直接改对应域文件 + 同步 reset_db.sql 即可。

## 目录地图

```
database/
├── README.md                    # 本文件
├── schema/                      # 基线 DDL，文件名编号即执行顺序
│   ├── 01_dict.sql              # 字典域：category_dict / dict_alias / dict_unresolved_value / six_dim_schema
│   ├── 02_product.sql           # 产品域：rspu_master/rspu_style/rspu_scene/rspu_variant/rspu_relation/3 个 code_counter/rsku_supply/price_history/rspu_price_summary
│   ├── 03_factory.sql           # 工厂域：factory_master/level_capability/warehouse/variant_capacity/rspu_factory_mapping/lead_time_rule/capacity_assessment/factory_product_capability
│   ├── 04_image_ai.sql          # 图片与AI识别域：image_assets / ai_recognition / async_task
│   ├── 05_excel_import.sql      # Excel导入域：excel_import_batch/excel_import_row
│   ├── 06_floor_plan.sql        # 户型图域：floor_plan_analysis / floor_plan_room
│   ├── 07_project_scheme.sql    # 项目与方案域：project/scheme/scheme_item/scheme_candidate/favorite_folder/user_favorite/template_tag/product_collection/product_collection_item
│   ├── 08_order_pricing.sql     # 订单与定价域：design_order/design_order_item/order_no_counter/sys_config/pricing_rule/recommendation_score_config
│   ├── 09_user_team.sql         # 用户与团队域：sys_user/sys_role/sys_permission/sys_user_role/sys_role_permission/sys_user_factory/company/member_group/invite_record/designer_profile/user_operator
│   ├── 10_style_knowledge.sql   # 风格知识库域：style_case/style_element/style_matching_formula/product_style_match/matching_feedback
│   ├── 11_platform.sql          # 官网平台域：platform_banner/case/content/custom_dict/customized/lead
│   ├── 12_system.sql            # 系统域：audit_log + 跨域自增序列对齐（setval）段
│   ├── cross_domain_fk.sql      # 跨域/循环引用后置外键 ALTER（字母序排数字域文件之后执行）
│   └── zz_seed.sql              # 必需字典/权限种子（zz 前缀保证最后执行，原 seed_required_data.sql）：原文件已有的循环引用后置（excel_import_batch.created_by、sys_user↔company/member_group/invited_by、user_favorite.folder_id、scheme.project_id）+ 按域拆分后的跨域执行序后置（rsku_supply→factory_master/factory_warehouse；user_favorite/favorite_folder/product_collection/project/design_order/recommendation_score_config→sys_user）
├── ops/
│   └── reset_db.sql             # 数据库重置脚本（自包含单文件：DROP + 重建 + 必需种子 + 开发测试账号 + 序列对齐）
├── seed_style_knowledge.sql     # 风格知识库种子数据
└── seed_dev_data.sql            # 开发/演示种子（弱口令测试账号 + 演示工厂/产品，仅开发环境，绝不进生产）
```

## 种子数据分类

| 文件 | 性质 | 内容 | 何时执行 |
|:-----|:-----|:-----|:---------|
| `seed_style_knowledge.sql` | 知识库 | 风格案例/元素/搭配公式 | 需要风格知识库时（`make seed-style`） |
| `seed_dev_data.sql` | **开发/演示专用** | 弱口令测试账号（admin/editor/designer/factory/user，rsdp-dev-2026!）+ TEST 测试工厂 + 演示工厂 F001/F002、DEMO-* 产品/变体/图片/报价 | 仅开发/演示环境（`make seed-dev`，`make dev` 会自动执行）；**绝不进生产**，initdb 不挂载此文件 |

## 两种场景执行手册

### 1. 全新初始化

- **Docker Compose（推荐）**：`cd deploy && docker compose up -d postgres`。PostgreSQL 首次启动自动执行 initdb 目录：`database/schema/` 整体挂载为 `/docker-entrypoint-initdb.d`（基线 DDL + cross_domain_fk.sql + zz_seed.sql 单目录一次挂载），PG 按字母序执行（01_~12_ 数字编号基线 → cross_domain_fk.sql → zz_seed.sql 种子殿后）。
- **手工**：按文件名序逐个执行 `schema/*.sql`（含 zz_seed.sql，一个循环即可，字母序天然把种子排在最后）：
  ```bash
  for f in database/schema/*.sql; do psql -U rsdp -d rsdp -v ON_ERROR_STOP=1 -f "$f"; done
  ```
  （`make init-db` / `make dev` / `scripts/setup.sh` 即此流程的封装。）

### 2. 数据库重置

`ops/reset_db.sql` 为自包含单文件（DROP 全部表 → 重建 → 种子 → 序列对齐，幂等可重复执行），在 psql / DBeaver / DataGrip Console 中**全选执行**即可。

注意 reset_db.sql 是纯开发工具，其内容 = **schema 基线 + 必需种子（zz_seed.sql 镜像）+ 开发测试账号**（重置开发库后可直接用弱口令账号登录），但**不含**演示工厂 F001/F002 与 DEMO-* 产品数据（需要时再执行 `seed_dev_data.sql` 或 `make seed-dev`）。

## 同步约定（重要）

新增/修改表结构时，两处必须同步更新，保证全新初始化和重复执行都幂等安全：

1. **schema/ 对应域文件**（唯一结构真相；新表按 FK 拓扑序归入所属域，循环引用及跨域执行序冲突的外键放 cross_domain_fk.sql）；
2. **ops/reset_db.sql**（重置脚本镜像）。

开发期工作流：直接编辑 schema/ 域文件 → 对开发库执行变更语句（或整文件重跑，全部语句幂等）→ 同步 reset_db.sql → 跑 `node scripts/check_entity_db_fields.js` 对账。历史演进记录由 git 承载，不再维护独立迁移文件；待首个生产环境出现时再引入版本化迁移工具。

种子数据变化直接改 `schema/zz_seed.sql`（reset_db.sql 内嵌镜像需同步）。
