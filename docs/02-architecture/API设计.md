> 本文档描述 RSDP 的目标 API 契约。当前 MVP 已实现产品录入、产品库、复核、图片访问、工厂管理与 RSKU 报价录入接口。

## 通用约定

- 业务 API 基地址：`http://localhost:8081/api/v1`
- 统一响应：`{ code, message, data }`
- 分页响应：`{ total, page, rows }`

## 核心 API 端点设计

### 产品管理

```
POST   /api/v1/products/entry
       # 新品录入（同步完成图片保存与任务创建，AI 识别在后台异步执行）
       # Request:  multipart/form-data
       #   image: File (必填, ≤20MB, jpg/png/webp/gif/bmp)
       # Response: { taskId, rspuId, imageId, message }

GET    /api/v1/tasks/{taskId}
       # 查询异步任务状态（前端轮询用）
       # Response: {
       #   taskId, taskType, status: "pending"|"processing"|"done"|"failed",
       #   progress: 0..100,
       #   result: { aiLabels }  // status=done 时
       #   errorMessage: ""       // status=failed 时
       #   createdAt, completedAt
       # }

GET    /api/v1/products
       # 产品列表（分页+多条件筛选，已实现）
       # Query: page, size, category_code, positioning_label, status,
       #        review_status, keyword（搜 category_path 或 positioning_label）
       # Response: { total, page, size, rows: [ProductSummary...] }

GET    /api/v1/products/{rspuId}
       # 产品详情（含图片和 AI 识别记录，已实现）
       # Response: { rspu: RspuMaster, images: [ImageAssets...], recognitions: [AiRecognition...] }

PUT    /api/v1/products/{rspuId}/review
       # 人工复核确认/存疑（已实现）
       # Request: { reviewStatus: "已确认"|"存疑", reviewComment? }

PUT    /api/v1/products/{rspuId}
       # 更新产品元数据（六维标签、场景、尺寸等，待实现）
       # Request: JSON Body（只传要更新的字段）

DELETE /api/v1/products/{rspuId}
       # 软删除（待实现）

### 图片访问

```
GET    /api/v1/images/{imageId}
       # 根据图片 ID 获取图片文件流
       # 底层存储由 StorageService 统一封装，支持 local / minio 两种实现
       # Response: image/jpeg | image/png | image/webp 等二进制流
```

### 供应管理

```
GET    /api/v1/products/{rspuId}/rsku
       # 查询某 RSPU 下的 RSKU 工厂报价列表（已实现）
       # Response: [RskuSupply...]

POST   /api/v1/products/{rspuId}/rsku
       # 为该 RSPU 新增工厂报价（已实现）
       # Request: { factoryCode, factorySku?, factoryPrice, materialDescription?,
       #            leadTimeDays?, moq?, warrantyYears?, shippingFrom?, diffNotes?, quoteConfidence? }
       # Response: void

PUT    /api/v1/sku/{rskuId}
       # 更新报价（价格变动时，待实现）

DELETE /api/v1/sku/{rskuId}
       # 删除报价（该厂不再供应，待实现）

GET    /api/v1/sku/compare/{rspuId}
       # 同款多厂比价列表（待实现）
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
       # 工厂列表（已实现）
       # Query: keyword（搜 factory_name / factory_code）
       # Response: [FactoryMaster...]

POST   /api/v1/factories
       # 新增工厂（已实现）
       # Request: { factoryCode, factoryName, contactPerson?, contactPhone?,
       #            address?, city?, province?, country?, website?, tags? }

PUT    /api/v1/factories/{code}
       # 更新工厂（已实现）

GET    /api/v1/factories/{code}
       # 工厂详情（已实现）
```

### 导出

```
POST   /api/v1/export/ppt
POST   /api/v1/export/excel
POST   /api/v1/export/batch-import
```

### 系统

```
GET    /api/v1/enums
       # 前端下拉选项数据

GET    /api/v1/health
       # 健康检查（检查 PostgreSQL + ChromaDB + AI + Redis 连通性）
```
