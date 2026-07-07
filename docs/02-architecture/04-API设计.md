> 本文档描述 RSDP 的目标 API 契约。当前 MVP 已实现产品录入、产品库、复核、图片访问、工厂管理与 RSKU 报价录入接口。

## 通用约定

- 业务 API 基地址：`http://localhost:8081/api/v1`
- 统一响应：`{ code, message, data }`
- 分页响应：`{ total, page, rows }`

## 认证

```
POST   /api/v1/auth/login
       # 用户登录
       # Request:  { username, password }
       # Response: { token, tokenType, userId, username, nickname, role, roles, permissions }
       # 说明：登录成功后，前端应将 token 存入 localStorage，
       #      并在后续请求头中携带 Authorization: Bearer <token>

GET    /api/v1/auth/me
       # 获取当前登录用户信息（需认证）
       # Response: { tokenType, userId, username, nickname, role, roles, permissions }
       # 说明：token 为空；roles/permissions 用于前端权限控制
```

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

POST   /api/v1/products/factory-entry
       # 工厂单条录入（原子创建 RSPU + 默认变体 + 图片 + 首条 RSKU）
       # Request: multipart/form-data
       #   images: File[] (必填, 第一张为主图, 单张 ≤20MB)
       #   factoryCode: string (必填, 当前用户所属工厂编码)
       #   categoryCode: string (必填, 如 FS/DT/CB)
       #   styleCode: string (必填, 如 MC/IT)
       #   materialCode: string (必填, 如 WO/PE)
       #   variantName?: string (变体名, 默认"")
       #   price?: number (出厂价)
       #   moq?: number (最小起订量)
       #   leadTimeDays?: number (交期天数)
       # Response: { rspuId, variantId, rskuId, imageIds: string[] }
       # 说明：
       #   - 仅 `FACTORY_ADMIN` 或拥有 `product:create` 权限的用户可调用
       #   - 必须属于 `factoryCode` 指定工厂，否则返回 403
       #   - 同一张图片同时作为主图写入 `image_assets`，并用于创建首条 RSKU

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
       #        keyword（搜 category_path 或 rspu_id）,
       #        viewMode: "own" | "full" (可选, 默认 own),
       #        factoryCode: string (可选, 工厂管理员可指定本厂编码)
       # Response: { total, page, size, rows: [ProductSummary...] }
       # 说明：
       #   - positioningLabel / sceneCode / materialTag 均按字典码精确查询
       #   - own：仅返回当前用户所属工厂已录入 RSKU 的 RSPU（工厂管理员/业务员）
       #   - full：平台全量 RSPU，按 `factory_product_capability` 能力覆盖去重，
       #     对已被本厂能力覆盖且本厂未报价的 RSPU 进行折叠隐藏；
       #     仅当用户拥有 `view_full_catalog=true` 或对应权限时可用

GET    /api/v1/products/{rspuId}
       # 产品详情（含图片、AI 识别记录、官方搭配与适配来源，已实现）
       # Response: {
       #   rspu: RspuMaster,
       #   images: [ImageAssets...],
       #   recognitions: [AiRecognition...],
       #   officialMatches: [RspuRelationResponse...],  // 本产品搭配了谁
       #   matchedBy: [RspuRelationResponse...]          // 谁把本产品作为搭配
       # }

GET    /api/v1/products/{rspuId}/relations
       # 查询某产品作为锚点的搭配关系列表（已实现）
       # Response: [RspuRelationResponse...]

POST   /api/v1/products/{rspuId}/relations
       # 为某产品创建搭配关系（已实现）
       # Request: { relatedRspuId, relationType?: "official"|"ai_verified"|"exclude", reason?, sortOrder? }
       # Response: void

PUT    /api/v1/products/{rspuId}/relations/{relationId}
       # 更新搭配关系（已实现）
       # Request: { relationType?, reason?, sortOrder?, status? }
       # Response: void

DELETE /api/v1/products/{rspuId}/relations/{relationId}
       # 删除搭配关系（软删除，已实现）
       # Response: void

PUT    /api/v1/products/{rspuId}/review
       # 人工复核确认/存疑（已实现）
       # Request: { reviewStatus: "已确认"|"存疑", reviewComment? }

PUT    /api/v1/products/{rspuId}
       # 更新产品元数据（定位标签、颜色、材质、场景、六维标签、价格带、保修年限等，已实现）
       # Request: JSON Body（只传要更新的字段；定位标签/风格、场景会同步更新 rspu_style / rspu_scene 关联表）
       # 说明：工厂管理员/业务员只能更新本厂已录入 RSKU 的 RSPU；非本厂产品返回 403

DELETE /api/v1/products/{rspuId}
       # 软删除（已实现）
       # Response: void
       # 说明：仅设置 rspu_master.deleted_at，数据库中保留数据；已关联的 RSKU / 变体 / 关系不级联删除

GET    /api/v1/products/import-template
       # 下载产品批量导入 Excel 模板（已实现）
       # Response: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet

POST   /api/v1/products/import
       # 产品（RSPU）批量导入（已实现）
       # Request: multipart/form-data
       #   file: File (必填, Excel .xlsx/.xls 或 CSV, ≤10MB, 单次 ≤500 行)
       #   updateIfExists: boolean (可选, 默认 false, true=存在时更新, false=跳过)
       # Response: {
       #   totalRows: number,
       #   successCount: number,
       #   failedCount: number,
       #   failures: [{ rowIndex, externalCode?, rspuId?, reason }]
       # }
       # 说明：
       #   - 一行对应一个 RSPU 及其可选默认变体
       #   - 按 RSPU ID → 外部编码顺序匹配已有产品
       #   - 图片 URL 仅支持 http/https，下载失败只记录失败明细，不影响产品数据写入

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
       # Request: { factoryCode, variantId（必填）, factorySku?, factoryPrice, materialCode?, materialDescription?,
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
       # 软删除报价（该厂不再供应，已实现）
       # Response: void
       # 说明：仅设置 rsku_supply.deleted_at，数据库中保留数据

GET    /api/v1/sku/compare/{rspuId}
       # 同款多厂比价列表（产品详情页 RSKU 列表已覆盖该能力，独立接口待评估）
```

### 搭配推荐

```
POST   /api/v1/matching/room-scheme
       # 按空间类型一键生成搭配方案（已实现，接入 DashScope qwen3-vl-plus）
       # Request: { roomType: "LIVING_ROOM", budgetLimit: 30000, stylePreference?: "MC" }
       # Response: { roomType, budgetLimit, totalPrice, itemCount, reasoning, items: [SchemeItem...] }

POST   /api/v1/matching/recommend
       # 以某个产品为锚点推荐搭配产品（已实现）
       # Request: { existingRspuId, targetCategoryCode }
       # Response: { existingRspuId, targetCategoryCode, reasoning, items: [SchemeItem...] }
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
       # Response: [FactoryResponse...]
       # FactoryResponse 增加 capableLevels: 可承接的所有等级

POST   /api/v1/factories
       # 新增工厂（已实现）
       # Request: {
       #   factoryCode, factoryName, factoryLevel（主等级）,
       #   capableLevels?（兼做等级列表，默认自动包含主等级）,
       #   contactPerson?, contactPhone?, address?, region?, notes?
       # }

PUT    /api/v1/factories/{code}
       # 更新工厂（已实现）

PUT    /api/v1/factories/{code}/level
       # 更新工厂主等级，工厂代码保持不变（已实现）
       # Request: { factoryLevel: "S"|"A"|"B"|"C" }
       # Response: void
       # 说明：修改主等级后会同步更新 factory_level_capability 表中的 is_primary 标记

PUT    /api/v1/factories/{code}/capable-levels
       # 更新工厂兼做等级列表（已实现）
       # Request: { capableLevels: ["S", "A", ...] }
       # Response: void
       # 说明：列表必须包含当前主等级，后端会自动确保主等级存在

GET    /api/v1/factories/{code}
       # 工厂详情（已实现）
       # Response: FactoryResponse

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

POST   /api/v1/dicts
       # 创建新的字典项（当前仅允许扩展 material / scene 两类业务标签）
       # Request:  { dictType: "material" | "scene", dictCode, dictName, dictNameEn? }
       # Response: { dictCode, dictName, dictNameEn, parentCode, sortOrder }
       # 注意：
       # 1. dictCode 仅支持字母和数字，服务端自动归一化为大写。
       # 2. 同一类型下 dictCode 重复会返回 400 "字典项已存在"。
       # 3. 创建成功后自动清除 dicts 缓存，前端可立即看到新选项。
       # 4. 新增标签不会被 AI 自动识别，只能人工或导入时赋值。
```

### 报价单

```
POST   /api/v1/quotes/generate
       # 根据选中的 RSKU 及数量列表生成报价单（已实现）
       # Request: { items: [{ rskuId, quantity }, ...] }
       # Response: {
       #   items: [QuoteItem...],           # QuoteItem 包含 quantity、subtotal
       #   summary: { totalPrice, itemCount, totalQuantity, factoryCount, maxLeadTimeDays }
       # }

POST   /api/v1/quotes/export
       # 根据选中的 RSKU 及数量列表导出 Excel 报价单（已实现）
       # Request: { items: [{ rskuId, quantity }, ...] }
       # Response: application/octet-stream，Content-Disposition: attachment; filename="quote_<timestamp>.xlsx"
       # 说明：工作簿包含两个 sheet："报价明细"（逐项报价，含数量/小计）和"汇总"（总价、项数、总数量、工厂数、最大交期、价格变动提示）
```

### 搭配方案

```
POST   /api/v1/schemes
       # 创建搭配方案（已实现）
       # Request: {
       #   schemeName (max 128 字符),
       #   roomType?,
       #   budgetLimit?,
       #   items: [{ rspuId, rskuId, quantity?, sortOrder? }] (1..50 项)
       # }
       # Response: SchemeResponse

GET    /api/v1/schemes
       # 查询搭配方案列表（已实现）
       # Response: [SchemeSummary...]

GET    /api/v1/schemes/{schemeId}
       # 查询搭配方案详情（已实现）
       # Response: SchemeResponse

PUT    /api/v1/schemes/{schemeId}
       # 更新搭配方案（已实现）
       # Request: {
       #   schemeName (max 128 字符),
       #   roomType?,
       #   budgetLimit?,
       #   items: [{ rspuId, rskuId, quantity?, sortOrder? }] (1..50 项)
       # }
       # Response: SchemeResponse
       # 说明：会物理删除旧子项并重新写入，保证幂等

DELETE /api/v1/schemes/{schemeId}
       # 删除搭配方案（已实现）

POST   /api/v1/schemes/{schemeId}/quote
       # 根据搭配方案生成报价单（已实现）
       # Response: QuoteResponse
       # 说明：采用快照模式，scheme_item 保存创建/更新时的 factory_price。
       #      重新生成报价单时，报价单按 RSKU 最新价格计算；若与快照不一致，
       #      会在 response.priceChanges 中列出变动项：
       #      [{ rspuId, rspuName, rskuId, oldPrice, newPrice }]
```

### 视觉/语义检索

```
POST   /api/v1/retrieval/similar
       # 以图搜图 / 以文搜图（已实现）
       # Request: multipart/form-data
       #   image: File (可选，与 text 二选一)
       #   text: string (可选，与 image 二选一)
       #   categoryCode: string (可选，按类别过滤)
       #   positioningLabel: string (可选，按风格/定位过滤)
       #   topK: number (可选，默认 20)
       # Response: [{ rspuId, categoryCode, positioningLabel, mainImageUrl, vectorScore, finalScore, matchReasons }]
       # 说明：三层检索：① DashScope 多模态 embedding 生成查询向量；
       #      ② ChromaDB 向量召回 + metadata 过滤；③ 按 RSPU 聚合 + 规则重排。
```

### 管理后台

```
POST   /api/v1/admin/vectors/backfill
       # 存量图片向量回填（已实现）
       # Query: batchSize (默认 100，最大 1000)
       # Response: { successCount, failedCount }
       # 说明：为已 AI 识别但缺少向量的存量图片生成 embedding，并写入 ChromaDB。

GET    /api/v1/admin/async/metrics
       # 异步任务线程池运行时指标（已实现）
       # Response: { corePoolSize, maxPoolSize, queueCapacity, activeCount, queueSize,
       #             completedTaskCount, taskCount, rejectedPolicy }
       # 说明：用于监控 AI 识别等后台任务的线程池状态；rejectedPolicy 取值见
       #      rsdp.async.rejected-policy 配置（abort / discard / discard-oldest / caller-runs）。
```

### 风格数据库（规划中）

```
POST   /api/v1/style-knowledge/import
       # 批量导入风格案例、元素、搭配公式
       # Request: multipart/form-data
       #   file: JSON 或 Excel（格式见 docs/03-guides/04-风格数据库导入数据格式.md）
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

### 用户管理（仅 ADMIN）

```
GET    /api/v1/admin/users
       # 用户列表（分页+关键字搜索）
       # Query: page, size, keyword（搜 username/nickname）
       # Response: { total, page, size, rows: [UserAdminResponse...] }
       # UserAdminResponse: { userId, username, nickname, roleCode, roleName, status,
       #                      factoryCodes: string[], lastLoginAt, createdAt, updatedAt }

POST   /api/v1/admin/users
       # 创建用户
       # Request: { username, nickname?, password, roleCode, factoryCodes?: string[] }
       # Response: UserAdminResponse

PUT    /api/v1/admin/users/{userId}
       # 编辑用户
       # Request: { nickname?, roleCode, factoryCodes?: string[] }
       # Response: UserAdminResponse

PUT    /api/v1/admin/users/{userId}/reset-password
       # 重置密码
       # Request: { newPassword }
       # Response: void

PUT    /api/v1/admin/users/{userId}/status
       # 启用/禁用用户
       # Query: status (active|disabled)
       # Response: void
```

### 系统

```
GET    /api/v1/enums
       # 前端下拉选项数据

GET    /api/v1/health
       # 健康检查（检查 PostgreSQL + ChromaDB + AI + Redis 连通性）
```

### 工厂产品能力档案

```
GET    /api/v1/factories/{factoryCode}/capabilities
       # 查询指定工厂的能力档案列表（需 capability:read）
       # Response: [FactoryProductCapabilityResponse...]

GET    /api/v1/factories/{factoryCode}/capabilities/{id}
       # 查询单条能力档案（需 capability:read）
       # Response: FactoryProductCapabilityResponse

POST   /api/v1/factories/{factoryCode}/capabilities
       # 手工创建能力档案（需 capability:create）
       # Request: { categoryCode, styleCode?, materialCode? }
       # Response: FactoryProductCapabilityResponse

PUT    /api/v1/factories/{factoryCode}/capabilities/{id}
       # 更新能力档案（需 capability:update）
       # Request: { categoryCode, styleCode?, materialCode? }
       # Response: FactoryProductCapabilityResponse

DELETE /api/v1/factories/{factoryCode}/capabilities/{id}
       # 删除能力档案（需 capability:delete）
       # Response: void

POST   /api/v1/factories/{factoryCode}/capabilities/sync
       # 根据当前有效 RSKU 重新同步能力档案（需 capability:create）
       # Response: [FactoryProductCapabilityResponse...]
```

### 产品集

```
GET    /api/v1/collections
       # 查询产品集列表（需 collection:read）
       # Query: status? (ACTIVE|INACTIVE)
       # Response: [ProductCollectionResponse...]

GET    /api/v1/collections/{collectionId}
       # 查询产品集详情（需 collection:read）
       # Response: ProductCollectionResponse（含 items）

POST   /api/v1/collections
       # 创建产品集（需 collection:create）
       # Request: { collectionCode?, name, description?, categoryCodes?: string[],
       #            styleCodes?: string[], targetSegments?: string[],
       #            isFeatured?: boolean, sortOrder?: int, rspuIds?: string[] }
       # Response: ProductCollectionResponse

PUT    /api/v1/collections/{collectionId}
       # 更新产品集（需 collection:update）
       # Request: 同创建，字段可选；rspuIds 存在时覆盖原有项
       # Response: ProductCollectionResponse

DELETE /api/v1/collections/{collectionId}
       # 删除产品集（需 collection:delete）
       # Response: void
```

### 设计师画像

```
GET    /api/v1/designer-profiles/me
       # 查询当前登录用户的设计师画像（需 designer:profile:read）
       # Response: DesignerProfileResponse

GET    /api/v1/designer-profiles
       # 查询公开的的设计师画像列表（需 designer:profile:read）
       # Response: [DesignerProfileResponse...]

GET    /api/v1/designer-profiles/{userId}
       # 根据用户 ID 查询设计师画像（需 designer:profile:read）
       # Response: DesignerProfileResponse

POST   /api/v1/designer-profiles/me
PUT    /api/v1/designer-profiles/me
       # 保存/更新当前登录用户的设计师画像（需 designer:profile:update）
       # Request: { realName?, avatarUrl?, specialties?: string[],
       #            preferredStyles?: string[], preferredCategories?: string[],
       #            priceSensitivity?, location?, companyName?, contactPhone?,
       #            bio?, defaultBudgetMin?, defaultBudgetMax?, isPublic? }
       # Response: DesignerProfileResponse
```

### 推荐打分配置

```
GET    /api/v1/recommendation-score-configs
       # 查询所有配置（需 recommendation:score:config:read）
       # Response: [RecommendationScoreConfigResponse...]

GET    /api/v1/recommendation-score-configs/default
       # 查询默认配置（需 recommendation:score:config:read）
       # Response: RecommendationScoreConfigResponse

GET    /api/v1/recommendation-score-configs/{configId}
       # 查询配置详情（需 recommendation:score:config:read）
       # Response: RecommendationScoreConfigResponse

POST   /api/v1/recommendation-score-configs
       # 创建配置（需 recommendation:score:config:update）
       # Request: { configKey, name, description?, weights: Map<string, number>,
       #            isDefault?: boolean, isActive?: boolean }
       # Response: RecommendationScoreConfigResponse

PUT    /api/v1/recommendation-score-configs/{configId}
       # 更新配置（需 recommendation:score:config:update）
       # Request: { name?, description?, weights?, isDefault?, isActive? }
       # Response: RecommendationScoreConfigResponse

DELETE /api/v1/recommendation-score-configs/{configId}
       # 删除配置（需 recommendation:score:config:update）
       # Response: void
```

### AI 推荐候选清单

```
GET    /api/v1/scheme-candidates
       # 根据推荐请求 ID 查询候选清单（需 scheme:candidate:read）
       # Query: recommendRequestId (UUID)
       # Response: [SchemeCandidateResponse...]

GET    /api/v1/scheme-candidates/mine
       # 查询当前用户的候选清单（需 scheme:candidate:read）
       # Response: [SchemeCandidateResponse...]

GET    /api/v1/scheme-candidates/{candidateId}
       # 查询候选详情（需 scheme:candidate:read）
       # Response: SchemeCandidateResponse

POST   /api/v1/scheme-candidates
       # 创建单个候选（需 scheme:candidate:create）
       # Request: { recommendRequestId, rspuId, rskuId?, score, aiReason?, matchFactors? }
       # Response: SchemeCandidateResponse

POST   /api/v1/scheme-candidates/batch
       # 批量创建候选（需 scheme:candidate:create）
       # Request: { recommendRequestId, candidates: [SchemeCandidateCreateRequest...] }
       # Response: [SchemeCandidateResponse...]

PUT    /api/v1/scheme-candidates/{candidateId}
       # 更新候选（需 scheme:candidate:update）
       # Request: { rskuId?, score?, aiReason?, matchFactors?, status? (pending|accepted|rejected) }
       # Response: SchemeCandidateResponse

DELETE /api/v1/scheme-candidates/{candidateId}
       # 删除候选（需 scheme:candidate:delete）
       # Response: void
```
