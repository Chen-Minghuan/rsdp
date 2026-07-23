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

POST   /api/v1/auth/register
       # 公开注册（免登录，可携带邀请码归因）
       # Request:  { username, password, nickname?, inviteCode? }
       # Response: { userId, username, nickname, inviteCode }
       # 说明：默认 VIEWER 角色（rooom TOURIST 映射）；inviteCode 有效时绑定
       #      invited_by 并写 invite_record，无效码/自邀请返回业务错误

GET    /api/v1/auth/me
       # 获取当前登录用户信息（需认证）
       # Response: { tokenType, userId, username, nickname, role, roles, permissions,
       #            viewFullCatalog, factoryCodes, inviteCode, certifiedDesigner, companyId }
       # 说明：token 为空；roles/permissions 用于前端权限控制；
       #      历史账号无邀请码时本接口懒生成 inviteCode

PUT    /api/v1/auth/me/profile
       # 更新当前登录用户资料（需认证）
       # Request: { nickname }
       # Response: UserResponse

PUT    /api/v1/auth/me/password
       # 修改当前登录用户密码（需认证）
       # Request: { oldPassword, newPassword }（新密码 6-64 位）
       # Response: void
       # 说明：校验原密码，成功后递增 token_version，旧 token 失效需重新登录

PUT    /api/v1/auth/me/preferences
       # 更新当前登录用户偏好设置（需认证）
       # Request: { viewFullCatalog: boolean }
       # Response: UserResponse
       # 说明：当前仅支持「显示全产品库（去重）」开关；工厂管理员可在账号设置页自更新
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
       # 更新产品元数据（产品名称、定位标签、颜色、材质、场景、六维标签、价格带、保修年限等，已实现）
       # Request: JSON Body（只传要更新的字段；定位标签/风格、场景会同步更新 rspu_style / rspu_scene 关联表）
       # 说明：工厂管理员/业务员只能更新本厂已录入 RSKU 的 RSPU；非本厂产品返回 403
       # 2026-07-22：新增 productName 字段（≤256，空串视为清空）；产品列表摘要响应同步返回 productName

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

POST   /api/v1/products/scene-import
       # 场景图拆分录入（2026-07-22 已实现）：一张室内场景照片 → AI 检测家具单品 → 逐件裁剪建档
       # FormData: file（场景照片，≤10MB）、categoryHint?（品类提示；AI 检测品类优先，其次提示，兜底 FS）
       # 权限：product:create；检测上限 12 件（rsdp.scene-import.max-products）
       # 每件独立创建 RSPU + 异步 AI 识别任务；单件失败不阻断其他件
       # Response: { batchId, totalProducts, successCount, failedCount,
       #             products: [{ bbox, categoryCode, label, status, rspuId, taskId, imageId, error }] }

POST   /api/v1/products/document-import
       # PDF 产品目录批量导入（已实现）
       # Request: multipart/form-data
       #   file: File (必填, PDF, ≤50MB, ≤200 页)
       #   categoryHint: string (可选, 品类提示如 SF/TB/FC)
       # Response: {
       #   batchId: string,
       #   totalPages: number,
       #   productPages: number,
       #   totalProducts: number,
       #   successCount: number,
       #   failedCount: number,
       #   taskIds: string[],
       #   rspuIds: string[],
       #   failures: [{ pageIndex, reason }]
       # }
       # 说明：
       #   - 后端将 PDF 渲染为图片，通过 AI 检测产品页和每个产品的位置框（bbox）
       #   - 按 bbox 裁剪出单产品图后，为每个产品创建 RSPU 草稿并触发异步 AI 识别
       #   - 前端通过 taskIds 轮询每个产品的识别进度

POST   /api/v1/products/excel-ai-import/preview
       # Excel AI 辅助字段映射预览（已实现）
       # Request: multipart/form-data
       #   file: File (必填, Excel .xlsx/.xls, ≤200MB)
       #   sheetIndex: int (可选, 默认 0；多 Sheet 文件逐一导入时指定工作表)
       # Response: {
       #   batchId: string,
       #   headers: string[],
       #   previewRows: { [header: string]: string }[],
       #   suggestedMapping: { [header: string]: string },
       #   priceColumns: [{ header: string, materialName: string, suggestedField: string,
       #                    role: "factory"|"sales" }],   // 出厂价/工厂价/EXW→factory；销售价/含税价/零售价/市场价→sales；其余默认 factory
       #   categoryMappings: [{ rawValue: string, suggestedCode: string, source: "dict"|"alias"|"ai"|"none" }],
       #   categoryGuess: string,
       #   notes: string,
       #   sheetIndex: number,                            // 回显本次解析的工作表
       #   sheets: [{ index: number, name: string, rowCount: number }]  // 工作簿全部工作表（rowCount 为近似行数）
       # }
       # 说明：
       #   - 支持单行/多行表头：先清洗表头（去掉英文备注、括号单位），再合并父子表头
       #   - 表头定位：关键词密度扫描（型号/价格/图片/序号/类别/ITEM/NO 等 ≥2 个）定位真表头行，
       #     其前低密度行视为公司标题行跳过（如曼柯行1-3标题、行5表头）
       #   - 英文对照副表头行（如 SERIAL/PICTURE/SORT/ITEM NO.）：整行 ASCII 为主且与中文表头列对齐时
       #     判定为副表头跳过——中文行作为唯一表头（不做父子合并），英文行不进数据区；
       #     中文材质子表头（A级布/半皮）仍走父子合并（价格-A级布）
       #   - AI 根据清洗后的表头和样例数据推荐字段映射；value 为标准字段名
       #   - 复合表头「型号品名」会映射为 "externalCode,productName"，导入时按空格/斜杠拆分
       #   - 交期列（交期/货期/生产周期/LEAD TIME 等）映射为标准字段 leadTimeDays
       #   - 长文本描述列（材质解析/功能配置/配置说明等）映射为标准字段 description → rspu_master.description
       #   - 零售参考价列（含税价/零售价/市场价等）映射为标准字段 retailPrice → rspu_master.retail_price
       #   - 多材质价格列（如「价格-A级布」「价格-AA级布」）映射为特殊字段 "__PRICE__:材质名"，
       #     并在 priceColumns 中独立列出（含 role），供前端勾选与指定角色
       #   - 返回的 mapping key 为合并后的原始表头，对应后续确认导入接口的字段名
       #   - 品类中文名归一（方案三：分层解析 + 用户确认 + 别名自学习）：品类列 distinct 值（≤50 个）
       #     逐层解析——字典码 / 字典中文名精确匹配（source=dict）、dict_alias 别名库（source=alias）；
       #     未命中词批量一次 AI 归一（source=ai，失败降级 source=none，不阻断预览，prompt 注入工作表名线索），
       #     结果经 categoryMappings 返回供用户在确认页编辑
       #   - 原始 Excel 文件会持久化到 storage，用于确认阶段重新读取和内嵌图片提取
       #   - 文件大小上限 200MB；单次导入行数上限 500 行；单张内嵌/URL 图片上限 20MB
       #   - 200MB 大文件内含大量图片时，内嵌图片提取可能占用较多内存，建议优先使用图片 URL

POST   /api/v1/products/excel-ai-import/import
       # Excel AI 辅助确认导入（已实现）
       # Request: JSON Body
       #   {
       #     batchId: string,
       #     mapping: { [header: string]: string },
       #     categoryHint: string,          // 品类提示，如 FS；Excel 无品类列时必填
       #     selectedPriceColumns: string[], // 要导入的价格列 header 列表；缺省/null 导入全部，显式空数组 [] 表示不选任何价格列（只建 RSPU/变体/图片，不建 RSKU）；旧契约兼容，全部视为 factory 角色
       #     priceColumnSelections: [{ header: string, role: "factory"|"sales" }], // 价格列选择+角色；与 selectedPriceColumns 同时存在时以本字段为准
       #                                            // role=factory 建变体+RSKU；role=sales 不建变体/RSKU，价格写 RSPU retail_price（只补空缺）
       #     defaultFactoryCode: string,    // 默认工厂编码，用于生成 RSKU 与 RSPU-工厂映射
       #     shippingWarehouseId: string,   // 默认发货仓库 ID（关联 factory_warehouse）
       #     defaultShippingFrom: string,   // 默认发货地（冗余显示字段）
       #     defaultLeadTimeDays: number,   // 默认基础交期天数；优先级：Excel 行级交期列 > 工厂交期规则 > 本默认值
       #     defaultMoq: number             // 默认最小起订量
       #     updateIfExists: boolean        // externalCode 已存在时：true 复用并更新已有 RSPU，false（默认）跳过该行
       #     categoryMapping: { [rawValue: string]: string }  // 用户确认的品类映射（原始值 → 字典码），行级解析最高优先；导入后写回别名库
       #   }
       # Response: {
       #   batchId: string,
       #   totalRows: number,
       #   successCount: number,
       #   failedCount: number,
       #   skippedCount: number,             // 说明行/重复表头行/已存在跳过 的行数
       #   taskIds: string[],
       #   rspuIds: string[],
       #   tasks: [{ taskId, rspuId }],      // 任务与 RSPU 成对关联（仅有任务的行，不错位）
       #   failures: [{ rowIndex, reason }]  // rowIndex 为 Excel 物理行号；含行内部分失败（如某价格列报价失败）
       # }
       # 说明：
       #   - 按 mapping 读取 Excel 每一行，逐行独立事务写入 RSPU / 变体 / 图片 / RSKU
       #   - 并发防重：导入前原子抢占批次状态（pending/done → importing），抢占失败抛业务异常；
       #     done 批次允许重新抢占（「以更新模式重新导入」），抢占时重置 success/failed/failures 结果字段，
       #     excel_import_row 旧记录整体删除后按同一批物理行号重建；importing 中拒绝
       #   - 批次归属：confirm/getStatus/listRows 均校验 createdBy == 当前用户（平台 ADMIN 放行）
       #   - 「规格/模块」列映射为标准字段 variantDisplayName，作为变体显示名称
       #   - 品类分层解析（normalizeCategoryCode）：用户确认 categoryMapping > 字典码 > 字典中文名 > dict_alias 别名库；
       #     未命中保留原值由 validateRow 报错；导入完成后 categoryMapping 词条经 saveAlias 写回别名库自学习
       #   - 品类兜底链（行无品类值时）：工作表名归一（字典码 > 字典中文名 > 别名库，无品类列的多 Sheet 文件
       #     常按品类分 sheet）> categoryHint 用户品类提示 > 预览时 AI 的 categoryGuess
       #   - 多 Sheet 导入：confirm 按批次 sheet_index 重新解析对应工作表（物理布局 + 内嵌图片
       #     均按 "sheetIndex,物理行" key 取本工作表），修复硬编码 sheet 0 的问题
       #   - 同 RSPU 下尺寸/颜色/材质组合相同的变体直接复用（如归组的连续模块行），不重复创建
       #   - RSKU/变体创建失败不静默：记入 failures（如「工厂报价失败：xxx」），成功 RSKU 回填 excel_import_row
       #   - EMF/WMF/TIFF 等非 web 内嵌图片格式跳过不入库，记入 failures「不支持的图片格式」
       #   - 产品归组：型号/品名列按纵向合并单元格语义向下填充后，同型号连续行归入同一 RSPU
       #     （共享主图与 AI 识别任务），每个规格模块行 + 每个选中的价格列创建 1 个变体 + 1 条 RSKU
       #   - 内嵌图片按「物理行号 + 物理列索引」精确关联（重解析原始文件重建布局，不受 jsonb 键序影响）：
       #     产品图样列的图 = 主图候选；其他数据列（如规格/模块列）的图 = 模块样式图，
       #     挂本行变体（image_assets.variant_id）且永不当主图；数据区域外的图（logo/二维码）忽略；
       #     严格主图模式：表格有图片列时，行内无产品图样图则该产品无主图，模块图不兜底升主图
       #   - 容错：空价格列跳过不报错；文件中间重复的表头行自动识别跳过；
       #     「更新日期/产品下单说明/注意事项」等说明尾注行自动跳过
       #   - 交期：Excel 行级交期列（映射 leadTimeDays，容忍「30天」「25-30天」写法）优先，
       #     其次按 factory_lead_time_rule 动态计算，最后回退 defaultLeadTimeDays
       #   - 主数据创建成功后为每个 RSPU 触发异步 AI 识别任务，前端通过 taskIds 轮询
       #   - 失败行不影响其他行，失败原因写入返回结果
       #   - 当指定 defaultFactoryCode 时，会为每个 RSPU 创建 RSPU-工厂关联（rspu_factory_mapping），
       #     并标记为主供工厂；同时每个价格列生成的 RSKU 会写入工厂报价、发货地、MOQ、动态交期
       #   - 每行 Excel 数据会写入 excel_import_row，记录原始值、处理阶段、生成实体 ID 与失败原因

GET    /api/v1/products/excel-ai-import/{batchId}
       # 查询 Excel AI 导入批次状态（已实现）
       # Response: {
       #   batchId: string,
       #   status: "pending"|"importing"|"done"|"failed",
       #   totalRows: number,
       #   successCount: number,
       #   failedCount: number,
       #   skippedCount: number,             // 说明行/重复表头行/已存在跳过 的行数
       #   tasks: [{ taskId, rspuId }],      // 从 excel_import_row 聚合，供前端超时恢复后重建识别轮询
       #   failures: [{ rowIndex, reason }]  // rowIndex 为 Excel 物理行号；rowIndex=0 为批次级问题（如图片提取截断）
       # }

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
       # 更新工厂基本信息（部分字段更新，已实现）
       # Request: {
       #   factoryName?, homeCommercialTag?, region?, address?,
       #   contactPerson?, contactPhone?, notes?,
       #   certification?（JSON 字符串）, engineeringCases?（JSON 字符串）,
       #   factoryArea?, employeeCount?, monthlyCapacity?, foundedYear?,
       #   equipmentList?（JSON 数组字符串）, frameWood?, spongeSupplier?,
       #   leatherFabricSource?, hardwareSupplier?,
       #   qcItems?（JSON 数组字符串）, qcStaffCount?,
       #   shippingFrom?, logisticsMethods?（JSON 数组字符串）,
       #   defaultPackaging?（JSON 数组字符串）, auditorSignature?, factoryImages?（JSON 数组字符串）
       # }
       # Response: void
       # 说明：仅更新请求中非 null 字段；需要 `factory:update` 权限

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

GET    /api/v1/factories/{code}/lead-time-rules
       # 查询某工厂的所有生效交期规则（已实现）
       # Response: [FactoryLeadTimeRuleResponse...]

POST   /api/v1/factories/{code}/lead-time-rules
       # 创建/更新工厂交期规则（已实现）
       # Request: {
       #   ruleId?,                 // 为空则创建，否则更新
       #   categoryCode?,           // 品类码，如 FS；为空表示通配
       #   materialGradeCode?,      // 材质等级码，如 FABRIC_A；为空表示通配
       #   processType?,            // 工艺类型，默认 standard
       #   baseDays: number,        // 基础交期天数（必填）
       #   batchSizeThreshold?,     // 批量阈值，超过则加 batchExtraDays
       #   batchExtraDays?,         // 批量额外天数
       #   materialSwitchExtraDays?,// 换材质额外天数（预留）
       #   priority?,               // 优先级，数字越小越优先，默认 100
       #   status?,                 // 默认 active
       #   notes?
       # }
       # Response: { ruleId: number }

DELETE /api/v1/factories/{code}/lead-time-rules/{ruleId}
       # 删除交期规则（已实现）

GET    /api/v1/factories/{code}/lead-time-rules/calculate
       # 动态计算交期（已实现）
       # Query: categoryCode?, materialGradeCode?, processType?（默认 standard）, quantity?（默认 1）
       # Response: { leadTimeDays: number | null }
       # 说明：按 精确匹配 → 忽略材质 → 忽略工艺 → 工厂默认 的优先级返回交期；无规则返回 null
```

### RSPU-工厂关联

```
GET    /api/v1/products/{rspuId}/factories
       # 查询某 RSPU 的所有工厂关联（已实现）
       # Query: province?（发货省份，如"广东"）
       # Response: [RspuFactoryMappingResponse...]
       # 说明：返回字段含 factoryName、factoryLevel、warehouseName、province、city、moq、baseLeadTimeDays

POST   /api/v1/products/{rspuId}/factories
       # 创建/更新 RSPU-工厂关联（已实现）
       # Request: {
       #   mappingId?,              // 为空则创建，否则更新
       #   factoryCode: string,     // 工厂编码（必填）
       #   isPrimary?: boolean,     // 是否主供工厂；为 true 时自动取消该 RSPU 其他主供
       #   shippingWarehouseId?: string, // 发货仓库 ID，必须属于 factoryCode 指定工厂
       #   moq?: number,
       #   baseLeadTimeDays?: number,
       #   status?: string,         // 默认 active
       #   notes?: string
       # }
       # Response: { mappingId: number }

DELETE /api/v1/products/{rspuId}/factories/{mappingId}
       # 删除 RSPU-工厂关联（已实现）
```

### Excel 导入行记录

```
GET    /api/v1/products/excel-ai-import/{batchId}/rows
       # 查询某导入批次下所有行级记录（已实现）
       # Response: [ExcelImportRow...]
       # 说明：用于导入后逐行核对成功/失败/跳过状态、原始值、生成实体与失败原因
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
       # 分页查询搭配方案列表（已实现）
       # Query: isTemplate? (true 仅查模板), tag? (模板标签筛选),
       #        page? (默认 1), size? (默认 10，上限 100)
       # Response: PageResult<SchemeSummary> { total, page, size, rows }（行含 isTemplate / templateTags）
       # 说明：模板选择弹窗等需要全量小列表的场景传 size=100

GET    /api/v1/schemes/{schemeId}
       # 查询搭配方案详情（已实现）
       # Response: SchemeResponse（含 projectId / isTemplate / templateTags）

PUT    /api/v1/schemes/{schemeId}
       # 更新搭配方案（已实现）
       # Request: {
       #   schemeName (max 128 字符),
       #   roomType?,
       #   projectId?（非空时校验项目归属并更新关联）,
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

PUT    /api/v1/schemes/{schemeId}/template
       # 设为/取消方案模板（已实现，需 scheme:update + 方案归属）
       # Request: { isTemplate, templateTags?: string[] }
       # Response: SchemeResponse
       # 说明：取消模板时自动清空 templateTags

POST   /api/v1/schemes/{schemeId}/copy-from-template
       # 套用模板创建新方案（已实现，需 scheme:create）
       # Request: { projectId, schemeName? }
       # Response: { scheme: SchemeResponse, priceChanges: [...], skippedRskuIds: [...] }
       # 说明：复制模板方案项，价格取 RSKU 当前最新价；与模板保存价的差异列入
       #      priceChanges；已失效 RSKU 跳过并列入 skippedRskuIds；模板自身不修改
```

### 设计项目

```
GET    /api/v1/projects
       # 分页查询项目列表（需 project:read；非 ADMIN 仅可见自己的项目）
       # Query: keyword?, scope? (all=全部，仅 ADMIN 生效；mine=仅自己的), page=1, size=10
       # Response: PageResult<ProjectResponse>
       #   { projectId, projectName, projectType, companyName, ownerId, status,
       #     remark, schemeCount, totalPrice, createdAt, updatedAt }

POST   /api/v1/projects
       # 创建设计项目（需 project:create）
       # 说明：companyName 留空时默认取当前登录用户的企业名称（sys_user.company_name）
       # Request: { projectName, projectType?, companyName?, remark? }
       # Response: ProjectResponse

GET    /api/v1/projects/{projectId}
       # 查询项目详情（需 project:read + 归属或 ADMIN）
       # Response: ProjectDetailResponse（含 schemes: [SchemeSummary...]）

PUT    /api/v1/projects/{projectId}
       # 更新设计项目（需 project:update + 归属或 ADMIN）
       # Request: { projectName, projectType?, companyName?, remark? }
       # Response: ProjectResponse

DELETE /api/v1/projects/{projectId}
       # 软删除设计项目（需 project:delete + 归属或 ADMIN）
       # 说明：项目下方案保留，project_id 置空
```

### 运营统计

```
GET    /api/v1/statistics/overview
       # 统计总览（需 scheme:read；非 ADMIN 仅统计自己的方案/项目）
       # Response: { schemeCount, totalAmount, projectCount, avgSchemeAmount, monthNewSchemes }

GET    /api/v1/statistics/trends
       # 按月趋势（需 scheme:read；缺失月份补零）
       # Query: months=6 (1~24)
       # Response: [{ month, schemeCount, totalAmount }]

GET    /api/v1/statistics/factories
       # 工厂维度方案金额 TOP20（需 scheme:read；按方案项出厂价×数量聚合）
       # Query: months=12 (1~24，默认 12；统计最近 N 个月的活跃方案)
       # Response: [{ factoryCode, factoryName, totalAmount, itemCount }]
```

### 设计订单

```
GET    /api/v1/orders
       # 分页查询订单列表（需 order:read；非 ADMIN 仅可见自己创建的订单）
       # Query: status? (PENDING/CONFIRMED/PRODUCING/COMPLETED/CANCELLED), page=1, size=10
       # Response: { total, page, rows: [OrderResponse...], statusCounts: { 状态: 数量 } }

POST   /api/v1/orders
       # 由方案生成订单（需 order:create + 方案归属或 ADMIN）
       # 说明：价格快照 = RSKU 当前出厂价 × 全局折扣率 price_rate（保留两位），生成后价格不可变
       # Request: { schemeId, projectId?, receiverName?, receiverPhone?, receiverArea?,
       #            receiverAddress?, remark? }
       # Response: OrderDetailResponse

GET    /api/v1/orders/{orderId}
       # 查询订单详情（需 order:read + 归属或 ADMIN）
       # Response: OrderDetailResponse（含 items: [{ id, rspuId, rskuId, productName, model,
       #   imageId, quantity, originalPrice, finalPrice, factoryCode, subtotal }]）

PUT    /api/v1/orders/{orderId}
       # 更新收件信息与备注（需 order:update + 归属；仅 PENDING 可改）
       # Request: { receiverName?, receiverPhone?, receiverArea?, receiverAddress?, remark? }

PUT    /api/v1/orders/{orderId}/status
       # 状态机迁移（需 order:update + 归属）
       # PENDING→CONFIRMED/CANCELLED，CONFIRMED→PRODUCING/CANCELLED，PRODUCING→COMPLETED
       # Request: { status }

POST   /api/v1/orders/{orderId}/invite
       # 生成客户邀请链接 token（需 order:update + 归属；重新生成后旧链接立即失效）
       # Response: { token, expireAt }（默认 7 天有效，库里只存 token 的 SHA-256 哈希）

GET    /api/v1/orders/contract-template
       # 下载采购合同 docx 模板（需 order:read）

GET    /api/v1/orders/statistics
       # 订单统计（需 order:read；排除 CANCELLED；非 ADMIN 仅统计自己创建的订单）
       # 说明：到手价为 AES 加密列，实体级解密后内存聚合；商品名/图取订单明细快照
       # Query: dim=product|factory（必填）, from?, to?（yyyy-MM-dd，含当日，可空）
       #       时间窗默认为最近 90 天，最大不得超过 365 天；结果按 totalAmount 降序取前 50 条
       # Response(dim=product):  [{ rspuId, productName, imageId, totalQuantity, totalAmount }]
       # Response(dim=factory):  [{ factoryCode, factoryName, orderCount, totalQuantity, totalAmount }]
       # 均按 totalAmount 降序
```

### 订单邀请公开接口（免登录）

```
GET    /api/v1/public/orders/invite/{token}
       # 查看邀请页订单视图（HMAC-SHA256 签名 + 过期 + 库存哈希三重校验）
       # Response: { orderNo, status, receiverArea, finalTotalPrice, itemCount,
       #   expectedLeadTime, expireAt, confirmed, confirmedAt,
       #   items: [{ productName, model, imageId, quantity, finalPrice, subtotal }] }
       # 安全约束：绝不返回 originalPrice/factoryCode/rskuId 等敏感字段

POST   /api/v1/public/orders/invite/{token}/confirm
       # 客户确认订单（PENDING→CONFIRMED，一次性，确认后链接不可再次确认）
```

### 系统配置

```
GET    /api/v1/configs/{key}
       # 读取配置（price_rate 需 order:read）

PUT    /api/v1/configs/{key}
       # 更新配置（仅 ADMIN），如 price_rate 全局折扣率
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
       # Query: page, size, keyword（搜 username/nickname/company_name/group_name）
       # Response: { total, page, size, rows: [UserAdminResponse...] }
       # UserAdminResponse: { userId, username, nickname, companyName?, groupName?,
       #                      roleCode, roleName, status, viewFullCatalog,
       #                      factoryCodes: string[], lastLoginAt, createdAt, updatedAt }

POST   /api/v1/admin/users
       # 创建用户
       # Request: { username, nickname?, companyName?, groupName?, password, roleCode,
       #            factoryCodes?: string[], viewFullCatalog? }
       # Response: UserAdminResponse

PUT    /api/v1/admin/users/{userId}
       # 编辑用户（字段缺省/null 时不覆盖原值）
       # Request: { nickname?, companyName?, groupName?, roleCode, factoryCodes?: string[],
       #            viewFullCatalog? }
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

### 用户中心-企业团队（登录用户自服务）

> 所有端点仅需登录；写操作由 Service 层按「企业管理员（owner）或平台 ADMIN」校验。
> 企业 ≠ 角色：`sys_user.company_id` 非空即企业账号。

```
PUT    /api/v1/member/certified-designer
       # 认证设计师（当前用户一键升级）
       # Response: void
       # 说明：挂 certified_designer 标记；VIEWER/USER 角色补 DESIGNER 角色并
       #      递增 token_version（需重新登录）；已是 DESIGNER/ADMIN/EDITOR 只挂标记

GET    /api/v1/member/company
       # 查询当前用户的企业（无企业时 data 为 null）
       # Response: { companyId, companyName, logoImageId?, priceRatio, ownerId,
       #            ownerNickname, status, memberCount, createdAt, updatedAt }

POST   /api/v1/member/company
       # 创建企业（当前用户成为管理员；已归属企业则拒绝）
       # Request: { companyName, logoImageId?, priceRatio? }（priceRatio ∈ [0,1]，默认 1）

PUT    /api/v1/member/company
       # 更新企业（名称/Logo/折扣率，仅管理员/ADMIN）
       # Request: { companyName?, logoImageId?, priceRatio? }

PUT    /api/v1/member/company/owner
       # 变更企业管理员（新管理员必须是本企业成员）
       # Request: { newOwnerId }

DELETE /api/v1/member/company
       # 软删除企业（成员归属清空，分组级联软删）

GET    /api/v1/member/groups
       # 企业分组列表（含成员数）
       # Response: [{ groupId, companyId, groupName, enabled, memberCount, createdAt }]

POST   /api/v1/member/groups
       # 创建分组（同企业重名拒绝）
       # Request: { groupName, enabled? }

PUT    /api/v1/member/groups/{groupId}
       # 更新分组（改名/启停）
       # Request: { groupName?, enabled? }

DELETE /api/v1/member/groups/{groupId}
       # 软删除分组（成员 group_id 置空）

GET    /api/v1/member/members
       # 企业成员列表（企业成员均可查看）
       # Query: groupId?（按分组过滤）
       # Response: [{ userId, username, nickname, groupId?, groupName?, status,
       #             roleCode?, certifiedDesigner, owner, createdAt }]

GET    /api/v1/member/members/search
       # 搜索可邀请用户（仅管理员/ADMIN；按用户名/昵称，仅未归属企业的启用账号，上限 20）
       # Query: keyword
       # Response: [{ userId, username, nickname, status }]

POST   /api/v1/member/members
       # 邀请用户加入企业
       # Request: { userId, groupId? }

DELETE /api/v1/member/members/{userId}
       # 移出成员（企业管理员需先变更管理员才能被移出）

PUT    /api/v1/member/members/{userId}/group
       # 调整成员分组（groupId 为空表示移出分组）
       # Request: { groupId? }

GET    /api/v1/member/invites
       # 我的邀请记录（邀请裂变）
       # Response: [{ id, inviteeId, inviteeUsername, inviteeNickname, createdAt }]
```

**订单计价折扣率优先级**：当前用户归属企业时使用企业 `price_ratio`，否则回退全局
`order.price_rate`（详见「设计订单」与「系统配置」）。

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

### 收藏夹

```
GET    /api/v1/favorites
       # 查询当前用户的收藏列表（需登录，数据按用户隔离）
       # Query: group? 分组筛选
       # Response: [FavoriteResponse...]
       #   { favoriteId, rspuId, groupName?, productName?, primaryImageUrl?, createdAt }

POST   /api/v1/favorites
       # 收藏产品（需登录；产品不存在 404，重复收藏报错）
       # Request: { rspuId, groupName? (max 64) }
       # Response: FavoriteResponse

DELETE /api/v1/favorites/{rspuId}
       # 取消收藏（需登录；未收藏返回 404）
       # Response: void

GET    /api/v1/favorites/check
       # 批量检查收藏状态（需登录）
       # Query: rspuIds（可重复传参）
       # Response: string[]（其中已收藏的 rspuId 列表）
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
