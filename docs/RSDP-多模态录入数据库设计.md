# RSDP 多模态录入数据库设计

> 聚焦"多模态数据录入 + AI 识别分类"阶段的数据库底座，为后续全链条工厂→销售报价打好基础。
> 部署目标：Windows 本地服务器/工作站（推荐 NVIDIA RTX 独立显卡）。

---

## 一、总体 ER 关系

```
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
│  rspu_master    │ 1:N   │  rsku_supply    │ N:1   │  factory_master │
│  款式主档案      │◀─────▶│  工厂供应子表    │◀─────▶│  工厂档案        │
└────────┬────────┘       └─────────────────┘       └─────────────────┘
         │
         │ 1:N
         ▼
┌─────────────────┐
│  image_assets   │
│  图片资源表      │
└─────────────────┘
         │
         │ 1:N
         ▼
┌─────────────────┐
│ ai_recognition  │
│ AI 识别记录表    │
└─────────────────┘
```

**辅助表**

- `category_dict` — 品类/风格/材质字典
- `async_task` — 异步任务
- `audit_log` — 审计日志
- `user_operator` — 操作员/复核员（可选，可复用业务系统用户表）

---

## 二、核心表详细设计

### 2.1 rspu_master — 款式主档案（RSPU）

**一条记录 = 一个款式概念**，不含任何工厂、价格、SKU 信息。

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `rspu_id` | TEXT | PRIMARY KEY | 双层编码，如 `FS-MC-001-M` |
| `category_code` | TEXT | NOT NULL | 类别 2 位：`FS`/`SF`/`TB`/`BS`/`OF` 等 |
| `category_path` | TEXT | NOT NULL | JSON：`["家具","座椅","休闲椅","单椅"]` |
| `positioning_label` | TEXT | NOT NULL | 家装填风格，办公填职级，如 `中古风` / `总裁级` |
| `six_dim_tags` | TEXT | | JSON：`{"A":"A字架形","B":"编织镂空",...}` |
| `style_vector` | TEXT | | JSON：512 维向量（备用副本，主向量存 ChromaDB；由 SpringBoot 调用 Ollama `/api/embeddings` 生成）|
| `color_primary_name` | TEXT | | 主色名称，如 `焦糖棕` |
| `color_primary_hsv` | TEXT | | JSON：`[30, 0.6, 0.4]` |
| `color_secondary` | TEXT | | JSON：`["米白","原木"]` |
| `material_tags` | TEXT | | JSON：`["柚木","真藤"]` |
| `scene_tags` | TEXT | | JSON：`["客厅","书房","咖啡厅"]` |
| `product_dimensions` | TEXT | | JSON：`{"w":560,"d":580,"h":780,"unit":"mm"}` |
| `occupancy_static` | TEXT | | JSON：静态占位尺寸 |
| `occupancy_dynamic` | TEXT | | JSON：含操作空间的动态占位 |
| `reference_price_band` | TEXT | | `low` / `mid` / `high` |
| `budget_range` | TEXT | | JSON：`[800, 3500]` |
| `warranty_years` | INTEGER | | 典型质保年限 |
| `key_specs` | TEXT | | JSON：框架材质、海绵密度等 |
| `status` | TEXT | DEFAULT 'active' | `active` / `discontinued` / `draft` |
| `review_status` | TEXT | DEFAULT '待复核' | `待复核` / `已确认` / `存疑` |
| `aesthetics_confidence` | TEXT | | `high` / `mid` / `low` |
| `source_agent_version` | TEXT | | Ollama 模型版本，如 `qwen2.5-vl:7b` / `nomic-embed-text` |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |
| `updated_at` | TIMESTAMP | | |
| `deleted_at` | TIMESTAMP | | 软删除时间，NULL 表示未删除 |

**设计要点**

- 所有 JSON 字段在 SQLite 中用 `TEXT` 存储，Java 侧用 Jackson 序列化/反序列化。
- `style_vector` 只作为冗余备份，主向量检索仍走 ChromaDB；由 SpringBoot 调用 Ollama `/api/embeddings` 生成。
- `positioning_label` 替代原 `style_label_human`，兼容家装"风格"和办公"职级"两种语义。
- `review_status` 是人工复核状态，每个 RSPU 最终都必须经过确认。

---

### 2.2 rsku_supply — 工厂供应子表（RSKU）

**一条记录 = 一个工厂对一个 RSPU 的一种材质报价**。

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `rsku_id` | TEXT | PRIMARY KEY | 如 `FS-MC-001-M-A004-PE` |
| `rspu_id` | TEXT | NOT NULL, FK | 关联 RSPU |
| `factory_code` | TEXT | NOT NULL, FK | 工厂代码 |
| `material_code` | TEXT | NOT NULL | 材质版本码：`PE`/`LE`/`TN` 等 |
| `factory_sku` | TEXT | | 工厂原始编码，如 `bf001` |
| `factory_price` | REAL | | 出厂价（**建议 AES 加密存储**）|
| `price_band` | TEXT | | `low` / `mid` / `high` |
| `material_description` | TEXT | | 工厂提供的详细材质说明 |
| `lead_time_days` | INTEGER | | 交期 |
| `moq` | INTEGER | | 最小起订量 |
| `structure_strength_rating` | TEXT | | 家用结构 / 商用结构 / 需验证 |
| `flame_retardant_capability` | TEXT | | 可做有案例 / 可做无案例 / 不可做 |
| `factory_photo_path` | TEXT | | 该厂实拍图在 MinIO 的路径 |
| `factory_credit_score` | INTEGER | | 履约评分 0-100 |
| `on_time_rate` | REAL | | 准时率 0-1 |
| `quality_return_rate` | REAL | | 退货率 0-1 |
| `diff_notes` | TEXT | | 差异备注：旋转底座、加宽 2cm、可定制 5 色等 |
| `quote_confidence` | TEXT | | `high` / `mid` / `low` |
| `review_status` | TEXT | DEFAULT '待复核' | `待复核` / `已确认` / `存疑` |
| `price_updated` | DATE | | 价格更新日期 |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |
| `updated_at` | TIMESTAMP | | |

**设计要点**

- `rspu_id` + `factory_code` + `material_code` 应唯一，需加唯一索引。
- `factory_price` 敏感，本地部署也建议加密，密钥单独管理。
- `diff_notes` 是处理"同款不同细节"的关键字段。

---

### 2.3 factory_master — 工厂档案

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `factory_code` | TEXT | PRIMARY KEY | 如 `S001` / `A004` / `B007` |
| `factory_name` | TEXT | NOT NULL | 工厂全称 |
| `factory_level` | TEXT | NOT NULL | `S` / `A` / `B` / `C` |
| `home_commercial_tag` | TEXT | | 家用级 / 商用级 |
| `certification` | TEXT | | JSON：`["BSCI","ISO9001"]` |
| `engineering_cases` | TEXT | | JSON：工程案例清单 |
| `region` | TEXT | | 佛山龙江 / 东莞厚街 / 安吉等 |
| `address` | TEXT | | 详细地址 |
| `contact_person` | TEXT | | 联系人 |
| `contact_phone` | TEXT | | 电话 |
| `first_audit_date` | DATE | | 首次验厂日期 |
| `next_visit_date` | DATE | | 下次回访日期 |
| `notes` | TEXT | | 备注 |
| `status` | TEXT | DEFAULT 'active' | `active` / `suspended` / `blacklisted` |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |
| `updated_at` | TIMESTAMP | | |

---

### 2.4 image_assets — 图片资源表（多模态核心）

多模态录入的核心是图片管理，不建议把图片路径散在 `rspu_master` 里。

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `image_id` | TEXT | PRIMARY KEY | UUID |
| `rspu_id` | TEXT | FK | 关联 RSPU |
| `rsku_id` | TEXT | FK | 可选，关联具体工厂报价 |
| `image_type` | TEXT | NOT NULL | `white_bg` / `factory_photo` / `detail` / `scene` / `original` |
| `storage_path` | TEXT | NOT NULL | MinIO 路径或本地文件路径 |
| `storage_url` | TEXT | | 访问 URL |
| `file_size` | INTEGER | | 文件大小（字节）|
| `width` | INTEGER | | 图片宽度 |
| `height` | INTEGER | | 图片高度 |
| `format` | TEXT | | `jpg` / `png` / `webp` |
| `is_primary` | BOOLEAN | DEFAULT 0 | 是否主图 |
| `ai_processed` | BOOLEAN | DEFAULT 0 | 是否已跑 AI |
| `quality_score` | REAL | | 图片质量评分（模糊度、白底检测等）|
| `uploaded_by` | TEXT | | 上传人 |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |

**设计要点**

- 一个 RSPU 可以有多张 `image_assets`。
- 只有 `image_type='white_bg'` 且质量合格的图才进入 AI 识别。
- `is_primary=1` 的图片用于列表展示和后续产品册输出。

---

### 2.5 ai_recognition — AI 识别记录表

记录每次 AI 调用的输入输出，是后续优化 AI 准确率最重要的数据资产。

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `recognition_id` | TEXT | PRIMARY KEY | UUID |
| `image_id` | TEXT | FK | 关联图片 |
| `rspu_id` | TEXT | FK | 关联 RSPU |
| `task_id` | TEXT | FK | 关联 async_task |
| `model_name` | TEXT | | Ollama 模型名，如 `qwen2.5-vl:7b` / `nomic-embed-text` |
| `recognition_type` | TEXT | | `encode` / `label` / `judge` |
| `endpoint` | TEXT | | 调用的 AI 端点，如 `http://localhost:11434/api/generate`，便于后续切云端 API 时排查 |
| `input_data` | TEXT | | JSON：Ollama 请求参数摘要 |
| `output_data` | TEXT | | JSON：Ollama 原始响应 |
| `parsed_style` | TEXT | | 解析后的风格/定位 |
| `parsed_six_dim` | TEXT | | JSON：解析后的六维标签 |
| `parsed_color_hsv` | TEXT | | JSON：解析后的颜色 HSV |
| `parsed_scene_tags` | TEXT | | JSON：解析后的场景标签 |
| `parsed_ocr` | TEXT | | JSON：OCR 结果 |
| `confidence` | TEXT | | `high` / `mid` / `low` |
| `processing_time_ms` | INTEGER | | 处理耗时（毫秒）|
| `status` | TEXT | | `success` / `failed` / `timeout` |
| `error_message` | TEXT | | 错误信息 |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |

**设计要点**

- 每次 AI 调用都留痕，便于对比不同模型、不同 prompt 的效果。
- `output_data` 存 Ollama 原始响应，`parsed_*` 存解析后的结构化字段。
- `endpoint` 字段让系统后续可以从本地 Ollama 平滑切换到云端 API（如 Kimi / 通义千问），数据库层无需改动。

---

### 2.6 async_task — 异步任务表

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `task_id` | TEXT | PRIMARY KEY | UUID |
| `task_type` | TEXT | NOT NULL | `product_entry` / `batch_import` / `anchor_encode` |
| `status` | TEXT | DEFAULT 'pending' | `pending` / `processing` / `done` / `failed` |
| `progress` | INTEGER | DEFAULT 0 | 0-100 |
| `input_data` | TEXT | | JSON：任务输入参数 |
| `result_data` | TEXT | | JSON：任务结果 |
| `error_message` | TEXT | | |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |
| `completed_at` | TIMESTAMP | | |

---

### 2.7 audit_log — 审计日志表

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | |
| `table_name` | TEXT | NOT NULL | 操作的表 |
| `record_id` | TEXT | NOT NULL | 记录 ID |
| `action` | TEXT | NOT NULL | `INSERT` / `UPDATE` / `DELETE` |
| `old_value` | TEXT | | JSON：旧值 |
| `new_value` | TEXT | | JSON：新值 |
| `operator` | TEXT | | 操作人 |
| `ip_address` | TEXT | | |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | |

---

## 三、字典表

### 3.1 category_dict — 品类/风格/材质字典

| 字段 | 类型 | 约束 | 说明 |
|:-----|:-----|:-----|:-----|
| `dict_type` | TEXT | NOT NULL | `category` / `style` / `material` / `six_dim_A` / `six_dim_B` / ... |
| `dict_code` | TEXT | NOT NULL | 编码 |
| `dict_name` | TEXT | NOT NULL | 名称 |
| `dict_name_en` | TEXT | | 英文名称 |
| `parent_code` | TEXT | | 父级编码 |
| `sort_order` | INTEGER | | 排序 |
| `status` | TEXT | DEFAULT 'active' | `active` / `disabled` |

**作用**

- 前端下拉框统一从这里取值。
- 防止 AI 输出同义词混乱，如"藤编"、"真藤"、"天然藤"被映射到统一编码 `TN`。
- 六维标签每个维度的取值范围也在这里维护。

---

## 四、完整建表 SQL（SQLite）

```sql
-- ============================================
-- RSPU 设计原型主表（款式主档案）
-- 绝对不含工厂、价格、SKU 信息
-- ============================================
CREATE TABLE rspu_master (
    rspu_id TEXT PRIMARY KEY,
    category_code TEXT NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label TEXT NOT NULL,
    six_dim_tags TEXT,
    style_vector TEXT,                     -- 512维向量备份：由 SpringBoot 调用 Ollama /api/embeddings 生成
    color_primary_name TEXT,
    color_primary_hsv TEXT,
    color_secondary TEXT,
    material_tags TEXT,
    scene_tags TEXT,
    product_dimensions TEXT,
    occupancy_static TEXT,
    occupancy_dynamic TEXT,
    reference_price_band TEXT,
    budget_range TEXT,
    warranty_years INTEGER,
    key_specs TEXT,
    status TEXT DEFAULT 'active',
    review_status TEXT DEFAULT '待复核',
    aesthetics_confidence TEXT,
    source_agent_version TEXT,               -- Ollama 模型版本，如 qwen2.5-vl:7b / nomic-embed-text
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- RSKU 供应单元子表（工厂报价子表）
CREATE TABLE rsku_supply (
    rsku_id TEXT PRIMARY KEY,
    rspu_id TEXT NOT NULL,
    factory_code TEXT NOT NULL,
    material_code TEXT NOT NULL,
    factory_sku TEXT,
    factory_price REAL,
    price_band TEXT,
    material_description TEXT,
    lead_time_days INTEGER,
    moq INTEGER,
    structure_strength_rating TEXT,
    flame_retardant_capability TEXT,
    factory_photo_path TEXT,
    factory_credit_score INTEGER,
    on_time_rate REAL,
    quality_return_rate REAL,
    diff_notes TEXT,
    quote_confidence TEXT,
    review_status TEXT DEFAULT '待复核',
    price_updated DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 工厂档案表
CREATE TABLE factory_master (
    factory_code TEXT PRIMARY KEY,
    factory_name TEXT NOT NULL,
    factory_level TEXT NOT NULL,
    home_commercial_tag TEXT,
    certification TEXT,
    engineering_cases TEXT,
    region TEXT,
    address TEXT,
    contact_person TEXT,
    contact_phone TEXT,
    first_audit_date DATE,
    next_visit_date DATE,
    notes TEXT,
    status TEXT DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 图片资源表
CREATE TABLE image_assets (
    image_id TEXT PRIMARY KEY,
    rspu_id TEXT,
    rsku_id TEXT,
    image_type TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    storage_url TEXT,
    file_size INTEGER,
    width INTEGER,
    height INTEGER,
    format TEXT,
    is_primary BOOLEAN DEFAULT 0,
    ai_processed BOOLEAN DEFAULT 0,
    quality_score REAL,
    uploaded_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- AI 识别记录表
CREATE TABLE ai_recognition (
    recognition_id TEXT PRIMARY KEY,
    image_id TEXT,
    rspu_id TEXT,
    task_id TEXT,
    model_name TEXT,                         -- Ollama 模型名，如 qwen2.5-vl:7b / nomic-embed-text
    recognition_type TEXT,                   -- encode / label / judge
    endpoint TEXT,                           -- 调用端点，如 http://localhost:11434/api/generate
    input_data TEXT,                         -- Ollama 请求参数摘要
    output_data TEXT,                        -- Ollama 原始响应
    parsed_style TEXT,
    parsed_six_dim TEXT,
    parsed_color_hsv TEXT,
    parsed_scene_tags TEXT,
    parsed_ocr TEXT,
    confidence TEXT,
    processing_time_ms INTEGER,
    status TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 异步任务表
CREATE TABLE async_task (
    task_id TEXT PRIMARY KEY,
    task_type TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data TEXT,
    result_data TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- 审计日志表
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name TEXT NOT NULL,
    record_id TEXT NOT NULL,
    action TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    operator TEXT,
    ip_address TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 字典表
CREATE TABLE category_dict (
    dict_type TEXT NOT NULL,
    dict_code TEXT NOT NULL,
    dict_name TEXT NOT NULL,
    dict_name_en TEXT,
    parent_code TEXT,
    sort_order INTEGER,
    status TEXT DEFAULT 'active',
    PRIMARY KEY (dict_type, dict_code)
);
```

---

## 五、索引设计

```sql
-- RSPU 索引
CREATE INDEX idx_rspu_category ON rspu_master(category_code, status);
CREATE INDEX idx_rspu_positioning ON rspu_master(positioning_label, category_code);
CREATE INDEX idx_rspu_review ON rspu_master(review_status);
CREATE INDEX idx_rspu_meta ON rspu_master(category_code, positioning_label, status) WHERE deleted_at IS NULL;

-- RSKU 索引
CREATE INDEX idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX idx_rsku_material ON rsku_supply(material_code);
CREATE UNIQUE INDEX idx_rsku_unique ON rsku_supply(rspu_id, factory_code, material_code);

-- 图片索引
CREATE INDEX idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX idx_image_primary ON image_assets(rspu_id, is_primary);

-- AI 识别索引
CREATE INDEX idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX idx_ai_rspu ON ai_recognition(rspu_id, created_at);

-- 审计日志索引
CREATE INDEX idx_audit_record ON audit_log(table_name, record_id, created_at);

-- 异步任务索引
CREATE INDEX idx_task_status ON async_task(status, created_at);
```

---

## 六、多模态数据流转

```
1. 员工上传图片
        ↓
2. image_assets 写入记录（ai_processed=0）
        ↓
3. 异步任务 async_task 启动
        ↓
4. SpringBoot 异步调用本地 Ollama：
   ├─ POST /api/embeddings → 向量编码 → 写入 ChromaDB
   │                          └─ 512 维向量备份到 rspu_master.style_vector
   ├─ POST /api/generate   → Qwen 标签生成 → 写入 ai_recognition
   │                          └─ 解析到 rspu_master（风格、六维、颜色、场景等）
   └─ POST /api/generate   → 多图比对终审 → 写入 ai_recognition
        ↓
5. SpringBoot 汇总结果，更新 rspu_master.review_status = '待复核'
        ↓
6. 员工在 Web 界面复核：
   ├─ 确认 → review_status = '已确认'
   ├─ 修正 → 更新字段 + 写入 audit_log
   └─ 存疑 → review_status = '存疑'
```

---

## 七、关键设计决策

| 决策 | 说明 |
|:-----|:-----|
| **RSPU 与 RSKU 严格分离** | RSPU 管款式概念，RSKU 管工厂报价，为后续全链条报价打好基础 |
| **图片独立成表** | 一个款式有多张图（白底图、实拍图、细节图），不能用一个字段散放 |
| **AI 识别记录留痕** | 每次 AI 调用都记录原始输出和解析结果，是后续优化 prompt 和模型的依据 |
| **向量主存 ChromaDB，SQLite 备份** | 检索性能交给专用向量库，关系库只保留可读的 JSON 副本 |
| **`positioning_label` 替代 `style_label_human`** | 兼容家装"风格"和办公"职级"两种分类语义 |
| **`factory_price` 建议加密** | 价格敏感，即使本地部署也不建议明文裸存 |
| **字典表统一标签取值** | 防止 AI 输出同义词，保证前后端下拉框一致 |
| **数据库层与 AI 运行时解耦** | AI 调用方从 Python FastAPI 改为 SpringBoot → Ollama，但 `ai_recognition`、`rspu_master` 等表结构不变；后续切换云端 API 只需改 endpoint，无需改库 |

---

## 八、与第二阶段（工厂→销售报价）的衔接

当前数据库已经为后续报价系统预留了关键字段：

| 当前表/字段 | 第二阶段用途 |
|:------------|:-------------|
| `rspu_master.reference_price_band` | 销售端预算筛选 |
| `rspu_master.budget_range` | 客户预期价格区间 |
| `rsku_supply.factory_price` | 成本价基础 |
| `rsku_supply.lead_time_days` | 报价单交期 |
| `rsku_supply.moq` | 起订量校验 |
| `factory_master.factory_level` | 工厂分级定价策略 |
| `image_assets.is_primary` | 产品册/报价单主图 |

后续报价阶段只需新增：客户档案、项目档案、报价单头/行表、价格策略表即可。
