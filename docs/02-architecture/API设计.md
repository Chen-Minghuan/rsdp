> 本文档描述 RSDP 的目标 API 契约。当前 MVP 已实现 `POST /api/v1/products/entry`，其余接口为后续开发的设计基准。

## 通用约定

- 业务 API 基地址：`http://localhost:8081/api/v1`
- 统一响应：`{ code, message, data }`
- 分页响应：`{ total, page, rows }`

## 核心 API 端点设计

### 产品管理

```
POST   /api/v1/products/entry
       # 新品录入（异步）
       # Request:  multipart/form-data
       #   image: File (必填, ≤20MB, jpg/png/webp)
       #   category_code: String (必填, FS/SF/TB/...)
       #   style_override: String (可选, 人工指定风格，跳过AI判定)
       # Response: { taskId: "task-abc123", status: "processing" }

GET    /api/v1/products/entry/{taskId}
       # 查询录入任务进度
       # Response: {
       #   taskId, status: "processing"|"done"|"failed",
       #   progress: { step: "labeling", percent: 60 },
       #   result: { aiLabels, similarProducts[], verdict }  // status=done时
       # }

GET    /api/v1/products
       # 产品列表（分页+多条件筛选）
       # Query: page, size, category_code, style_label, status,
       #        price_band, keyword（搜 category_path 或 six_dim_tags）
       # Response: { total, page, rows: [RspuSummary...] }

GET    /api/v1/products/{rspuId}
       # 产品详情（含关联的所有 RSKU 报价）
       # Response: { rspu: RspuDetail, rsku_list: [RskuInfo...], factory_count: 4 }

PUT    /api/v1/products/{rspuId}
       # 更新产品元数据（六维标签、场景、尺寸等）
       # Request: JSON Body（只传要更新的字段）

DELETE /api/v1/products/{rspuId}
       # 软删除
```

### 供应管理

```
POST   /api/v1/products/{rspuId}/sku
       # 为该 RSPU 新增工厂报价
       # Request: { factory_code, material_code, factory_price,
       #            lead_time_days, moq, diff_notes, ... }

PUT    /api/v1/sku/{rskuId}
       # 更新报价（价格变动时）

DELETE /api/v1/sku/{rskuId}
       # 删除报价（该厂不再供应）

GET    /api/v1/sku/compare/{rspuId}
       # 同款多厂比价列表
```

### 检索

```
POST   /api/v1/retrieval/similar
       # 以图搜图（同款判定，完整三层检索）
       # Request: multipart/form-data { image: File }

POST   /api/v1/retrieval/by-condition
       # 文字条件检索（不传图）
       # Request: { category_code?, style_label?, six_dim_A?,
       #            color_hue_range?, price_max?, scene_tag?, keyword? }
```

### 搭配推荐

```
POST   /api/v1/matching/recommend
       # 单个搭配推荐
       # Request: { existing_rspu_id, target_category_code }

POST   /api/v1/matching/batch
       # 批量搭配（一键生成客厅/餐厅/卧室方案）
       # Request: { room_type: "living_room", existing_products: ["FS-MC-001-M"],
       #            budget_limit: 30000 }
```

### 空间校验

```
POST   /api/v1/spatial/check
       # 产品组合 → 冲突报告

POST   /api/v1/spatial/table-chair
       # 餐桌椅耦合校验

POST   /api/v1/spatial/floor-plan
       # 平面占位计算
```

### 工厂管理

```
GET    /api/v1/factories
POST   /api/v1/factories
PUT    /api/v1/factories/{code}
GET    /api/v1/factories/{code}
```

### 导出

```
POST   /api/v1/export/ppt
POST   /api/v1/export/excel
POST   /api/v1/export/batch-import
```

### 风格数据库

```
POST   /api/v1/style-knowledge/import
       # 批量导入风格案例、元素、搭配公式
       # Request: multipart/form-data
       #   file: JSON 或 Excel（格式见 docs/03-guides/风格数据库导入数据格式.md）
       # Response: { imported_cases: 12, imported_formulas: 3, errors: [...] }

GET    /api/v1/style-knowledge/cases
       # 案例列表（支持 style_code、is_success、room_type 筛选）
       # Response: { total, page, rows: [StyleCaseSummary...] }

GET    /api/v1/style-knowledge/cases/{caseId}
       # 案例详情（含元素列表）

POST   /api/v1/style-knowledge/formulas
       # 新增/更新搭配公式

GET    /api/v1/style-knowledge/formulas
       # 公式列表

GET    /api/v1/style-knowledge/formulas/{formulaId}
       # 公式详情

POST   /api/v1/style-knowledge/match/{rspuId}
       # 手动触发某个 RSPU 的风格匹配计算
       # Response: { style_code, overall_score, confidence, element_match, formula_scores }

POST   /api/v1/style-knowledge/feedback
       # 提交推荐反馈，用于优化公式
       # Request: { rspu_id, recommended_rspu_id, formula_id, feedback, reason }
```

### 系统

```
GET    /api/v1/enums
       # 前端下拉选项数据

GET    /api/v1/health
       # 健康检查（检查 PostgreSQL + ChromaDB + AI + Redis 连通性）
```
