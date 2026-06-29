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
       #   images: File[] (必填, 第一张为主图, 其余为非主图/detail, 单张 ≤20MB)
       #   categoryCode: string (可选, 如 FS/DT/CB, 默认 FS)
       # Response: { taskId, rspuId, imageIds: string[], message }
       # 说明：AI 识别完成后会自动写入 rspu_style / rspu_scene 关联表，
       #      positioning_label 保存风格码（如 MC），material_tags 保存材质码（如 WO）

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
       # Query: page, size, categoryCode, positioningLabel（风格码）, sceneCode,
       #        materialTag（材质码）, status, reviewStatus,
       #        keyword（搜 category_path 或 rspu_id）
       # Response: { total, page, size, rows: [ProductSummary...] }
       # 说明：positioningLabel / sceneCode / materialTag 均按字典码精确查询

GET    /api/v1/products/{rspuId}
       # 产品详情（含图片和 AI 识别记录，已实现）
       # Response: { rspu: RspuMaster, images: [ImageAssets...], recognitions: [AiRecognition...] }

PUT    /api/v1/products/{rspuId}/review
       # 人工复核确认/存疑（已实现）
       # Request: { reviewStatus: "已确认"|"存疑", reviewComment? }

PUT    /api/v1/products/{rspuId}
       # 更新产品元数据（定位标签、颜色、材质、场景、六维标签、价格带、保修年限等，已实现）
       # Request: JSON Body（只传要更新的字段；定位标签/风格、场景会同步更新 rspu_style / rspu_scene 关联表）

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

GET    /api/v1/products/{rspuId}/rsku/{rskuId}
       # 查询单个 RSKU 报价详情（已实现）
       # Response: RskuSupply

GET    /api/v1/products/{rspuId}/variants
       # 查询某 RSPU 下的变体列表（已实现）
       # Response: [RspuVariant...]

POST   /api/v1/products/{rspuId}/variants
       # 为 RSPU 创建变体（尺寸 × 颜色 × 材质组合，已实现）
       # Request: { displayName, variantCode?, sizeCode?, dimensions?, colorCode?, materialCode, materialMix?, referencePriceBand? }
       # Response: RspuVariant

POST   /api/v1/products/{rspuId}/rsku
       # 为该 RSPU 新增工厂报价（已实现）
       # Request: { factoryCode, variantId（必填）, factorySku?, factoryPrice, materialDescription?,
       #            leadTimeDays?, moq?, warrantyYears?, shippingFrom?, diffNotes?, quoteConfidence? }
       # Response: void

PUT    /api/v1/products/{rspuId}/rsku/{rskuId}/price
       # 更新 RSKU 出厂价，自动写入 price_history（已实现）
       # Request: { factoryPrice, changeReason? }
       # Response: void

GET    /api/v1/rsku/{rskuId}/price-history
       # 查询 RSKU 价格历史（已实现）
       # Response: [PriceHistory...]

DELETE /api/v1/sku/{rskuId}
       # 删除报价（该厂不再供应，待实现）

GET    /api/v1/sku/compare/{rspuId}
       # 同款多厂比价列表（产品详情页 RSKU 列表已覆盖该能力，独立接口待评估）
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
POST   /api/v1/matching/room-scheme
       # 按空间类型一键生成搭配方案（已实现，接入 DashScope qwen3-vl-plus）
       # Request: { roomType: "LIVING_ROOM", budgetLimit: 30000, stylePreference?: "MC" }
       # Response: { roomType, budgetLimit, totalPrice, itemCount, reasoning, items: [SchemeItem...] }

POST   /api/v1/matching/recommend
       # 以某个产品为锚点推荐搭配产品（预留接口）
       # Request: { existingRspuId, targetCategoryCode }
       # Response: { message: "功能开发中..." }
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

PUT    /api/v1/factories/{code}/level
       # 更新工厂等级，工厂代码保持不变（已实现）
       # Request: { factoryLevel: "S"|"A"|"B"|"C" }
       # Response: void

GET    /api/v1/factories/{code}
       # 工厂详情（已实现）

GET    /api/v1/factories/{code}/rsku
       # 查询某工厂的所有 RSKU 报价（已实现）
       # Response: [RskuSupply...]
```

### 导出

```
POST   /api/v1/export/ppt
POST   /api/v1/export/excel
POST   /api/v1/export/batch-import
```

### 字典

```
GET    /api/v1/dicts/{dictType}
       # 查询指定类型的字典项（已实现）
       # dictType: style, scene, category, material, size, color,
       #           room_type, quote_confidence, review_status, factory_level, product_status 等
       # Response: [{ dictCode, dictName, dictNameEn, parentCode, sortOrder }]
```

### 报价单

```
POST   /api/v1/quotes/generate
       # 根据选中的 RSKU 列表生成报价单（已实现）
       # Request: { rskuIds: ["RSKU-001", ...] }
       # Response: { items: [QuoteItem...], summary: { totalPrice, itemCount, factoryCount, maxLeadTimeDays } }
```

### 系统

```
GET    /api/v1/enums
       # 前端下拉选项数据

GET    /api/v1/health
       # 健康检查（检查 PostgreSQL + ChromaDB + AI + Redis 连通性）
```
