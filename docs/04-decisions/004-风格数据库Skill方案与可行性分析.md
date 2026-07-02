# 004 - 风格数据库 Skill 方案与可行性分析

> **状态**：已确认（待实施）  
> **决策项**：在 RSDP 中新增一个可复用的「风格数据库 Skill」，把成功/失败设计案例沉淀为结构化元素与搭配公式，用于产品归类与搭配推荐。  
> **相关文档**：
> - `docs/02-architecture/数据库设计.md`
> - `docs/02-architecture/API设计.md`
> - `docs/03-guides/风格数据库导入数据格式.md`
> - `docs/06-reference/双层编码体系.md`

---

## 1. 背景与痛点

当前 RSDP 的 AI 识别流程是**单产品视角**：

```
上传图片 → AI 提取 {风格、六维标签、颜色、材质、场景} → 写入 rspu_master
```

这套流程能回答「这是什么」，但很难回答**「为什么这样搭」**和**「不该怎么搭」**。后续的搭配推荐如果只靠向量相似度，会存在几个问题：

| 问题 | 说明 |
|:-----|:-----|
| **不可解释** | 推荐结果只能给相似度分，说不出「为什么这把椅子配这张沙发」。 |
| **缺乏边界** | 不知道哪些元素组合是行业公认的「雷区」，容易推荐出风格打架的方案。 |
| **知识不可积累** | 设计师的经验、学术论文结论、成功案例的搭配逻辑没有落到数据库里。 |
| **推荐同质化** | 只看视觉相似，容易推荐「长得很像」而不是「搭得很好」的产品。 |

本方案的目标就是建立一个**风格数据库 Skill**，把设计案例、搭配原则、失败教训都变成结构化数据，让 RSDP 从「产品台账」升级为「设计知识库」。

---

## 2. 核心概念定义

```
┌─────────────────────────────────────────────────────────────┐
│                      风格数据库 Skill                        │
├─────────────┬─────────────┬─────────────────────────────────┤
│  案例库     │   元素库    │           搭配公式库             │
│  style_case │ style_element│    style_matching_formula       │
├─────────────┼─────────────┼─────────────────────────────────┤
│ 成功案例    │ 风格        │  if 场景 + 风格 + 主品类        │
│ 失败案例    │ 品类        │  then 推荐/避免 哪些元素        │
│ 来源/图片   │ 颜色/材质   │  with 权重 + 空间约束 + 原因    │
│ 结论标签    │ 六维标签    │                                 │
│             │ 情绪/预算   │                                 │
└─────────────┴─────────────┴─────────────────────────────────┘
```

### 2.1 元素（Element）

从产品图或设计案例中提取、并映射到受控词表的标准化因子。元素类型可复用并扩展 RSDP 已有的分类体系：

| 元素类型 | 示例 | 映射到现有表/字段 |
|:---------|:-----|:------------------|
| `style` | 中古风、侘寂、意式极简 | `category_dict` + `rspu_style` |
| `category` | 休闲椅、三人沙发、茶几 | `category_dict` + `rspu_master.category_code` |
| `six_dim_A` ~ `six_dim_F` | A字架形、编织镂空、八字外扩 | `rspu_master.six_dim_tags` |
| `color` | 焦糖棕、米白、原木色 | `rspu_master.color_primary_name` |
| `material` | 柚木、真藤、亚麻、皮革 | `category_dict` + `rspu_variant.material_mix` |
| `scene` | 客厅、书房、咖啡厅、总裁办公室 | `category_dict` + `rspu_scene` |
| `mood` | 温暖、沉稳、冷峻、戏谑 | 新增元素类型 |
| `budget_band` | low / mid / high | `rspu_master.reference_price_band` |
| `function` | 可旋转、可堆叠、升降 | `rspu_variant` 扩展 |

### 2.2 案例（Case）

一条案例 = 一张/一组设计图片 + 场景描述 + 结论（成功/失败）+ 提取出的元素集合。

| 字段 | 说明 |
|:-----|:-----|
| `case_id` | 唯一编号，如 `CASE-MC-001` |
| `case_name` | 案例名称，如「中古风客厅三人位方案」 |
| `style_code` | 主风格，如 `MC` |
| `room_type` | 空间类型，如 `living_room` |
| `is_success` | `true` 成功范例 / `false` 反面教材 |
| `source_type` | 设计杂志 / 品牌画册 / 学术论文 / 内部项目 |
| `source_url` | 来源链接或文献 DOI |
| `description` | 案例文字说明 |
| `image_url` | 图片在 MinIO 中的地址 |
| `ai_raw_output` | AI 网页端提取的原始 JSON |
| `review_status` | 待复核 / 已确认 / 存疑 |

### 2.3 搭配公式（Matching Formula）

把案例背后的搭配原则抽象成**可执行、可解释**的规则。一条公式可对应多个成功案例和若干失败案例。

```json
{
  "formula_id": "FORM-MC-LIVING-001",
  "name": "中古风客厅：实木+藤编主椅 × 亚麻/皮革辅椅",
  "context": {
    "room_type": "living_room",
    "style_code": "MC",
    "anchor_category": "FS"
  },
  "must_have": [
    { "type": "material", "values": ["实木", "藤编"], "role": "primary" }
  ],
  "compatible": [
    { "type": "style", "values": ["MC", "WJ"], "weight": 0.30, "reason": "同属自然复古谱系" },
    { "type": "material", "values": ["亚麻", "皮革"], "weight": 0.25, "reason": "与中古木藤形成质感对比" },
    { "type": "color", "relation": "analogous", "values": ["焦糖棕", "米白", "原木色"], "weight": 0.20, "reason": "大地色系安全区" },
    { "type": "scene", "values": ["客厅", "书房"], "weight": 0.10 }
  ],
  "avoid": [
    { "type": "material", "values": ["塑料", "亚克力"], "reason": "破坏中古质感" },
    { "type": "color", "values": ["荧光色", "高饱和紫"], "reason": "与中古低饱和调性冲突" }
  ],
  "spatial_hint": {
    "seat_height_delta_cm": [25, 30],
    "scale_ratio": [0.8, 1.2],
    "note": "主椅与辅椅座高差控制在 25-30cm"
  },
  "source_case_ids": ["CASE-MC-001", "CASE-MC-005"],
  "negative_case_ids": ["CASE-MC-FAIL-002"],
  "success_count": 12,
  "fail_count": 3,
  "status": "active"
}
```

### 2.4 产品-风格匹配结果（Product Style Match）

当产品录入后，系统用元素库和公式库计算该产品与每条公式的匹配度，结果存入数据库，供推荐时直接使用。

| 字段 | 说明 |
|:-----|:-----|
| `rspu_id` | 款式 ID |
| `style_code` | 主风格 |
| `element_match` | JSON：命中了哪些元素 |
| `formula_scores` | JSON：各公式得分 |
| `overall_score` | 综合得分 |
| `confidence` | high / mid / low |

---

## 3. 总体数据流

```
阶段一：知识采集（在 AI 网页端完成）
    │
    ▼
设计师/运营上传案例图 + 参考文字
    │
    ▼
Kimi / ChatGPT / Claude（多模态网页版）
    │ 按统一 Prompt 输出：元素 + 公式假设 + 成功/失败结论
    ▼
人工复核并映射到 RSDP 受控词表
    │
    ▼
导出为 JSON / Excel 导入文档

阶段二：知识入库（在 RSDP 系统中完成）
    │
    ▼
按 `docs/03-guides/风格数据库导入数据格式.md` 整理为 JSON / Excel
    │
    ▼
POST /api/v1/style-knowledge/import
    │
    ▼
写入 style_case / style_element / style_matching_formula

阶段三：产品匹配（产品录入时自动触发）
    │
    ▼
AI 识别产品 → 得到 {风格、六维标签、颜色、材质、场景}
    │
    ▼
StyleMatchingService.match(rspu_id)
    │  1. 把产品标签映射为元素
    │  2. 用元素库和公式库打分
    │  3. 写入 product_style_match
    ▼
人工复核确认

阶段四：搭配推荐（用户选品时触发）
    │
    ▼
GET /api/v1/matching/recommend?rspu_id=FS-MC-001-M&room_type=living_room
    │
    ▼
召回候选 → 公式打分 → 向量相似度加权 → 空间/预算过滤
    │
    ▼
返回带理由的推荐清单
```

---

## 4. 数据模型扩展（建议新增表）

以下表复用现有 `category_dict` 作为受控词表，不引入新的枚举维护成本。

```sql
-- 案例库：成功/失败的设计案例
CREATE TABLE IF NOT EXISTS style_case (
    case_id VARCHAR(64) PRIMARY KEY,
    case_name VARCHAR(128) NOT NULL,
    style_code VARCHAR(32) NOT NULL,
    room_type VARCHAR(32),
    is_success BOOLEAN NOT NULL DEFAULT TRUE,
    source_type VARCHAR(32),
    source_url TEXT,
    description TEXT,
    image_url TEXT,
    ai_raw_output JSONB,
    review_status VARCHAR(16) DEFAULT '待复核',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 元素库：从案例中拆解出的标准化元素
CREATE TABLE IF NOT EXISTS style_element (
    element_id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    element_type VARCHAR(32) NOT NULL,   -- style/category/color/material/scene/mood/...
    element_value VARCHAR(128) NOT NULL, -- 人类可读值
    normalized_code VARCHAR(64),         -- 受控词表 code，如 MC / TN
    is_primary BOOLEAN DEFAULT FALSE,    -- 是否为主元素
    confidence VARCHAR(16),              -- high/mid/low
    notes TEXT,
    FOREIGN KEY (case_id) REFERENCES style_case(case_id)
);

-- 搭配公式库：可解释的搭配规则
CREATE TABLE IF NOT EXISTS style_matching_formula (
    formula_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    style_code VARCHAR(32) NOT NULL,
    room_type VARCHAR(32),
    priority INTEGER DEFAULT 0,
    formula_json JSONB NOT NULL,         -- 完整公式结构
    source_case_ids JSONB,               -- 成功案例 ID 列表
    negative_case_ids JSONB,             -- 反面教材 ID 列表
    success_count INTEGER DEFAULT 0,
    fail_count INTEGER DEFAULT 0,
    status VARCHAR(16) DEFAULT 'active', -- active/disabled/draft
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 产品-风格匹配结果：产品录入后自动计算
CREATE TABLE IF NOT EXISTS product_style_match (
    match_id SERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    style_code VARCHAR(32) NOT NULL,
    element_match JSONB,
    formula_scores JSONB,
    overall_score DECIMAL(5,4),
    confidence VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (rspu_id, style_code)
);

-- 推荐反馈：用于后续优化公式
CREATE TABLE IF NOT EXISTS matching_feedback (
    feedback_id SERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    recommended_rspu_id VARCHAR(64) NOT NULL,
    formula_id VARCHAR(64),
    score DECIMAL(5,4),
    feedback VARCHAR(16),  -- accepted/rejected/ordered
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_style_case_style ON style_case(style_code, is_success);
CREATE INDEX IF NOT EXISTS idx_style_element_case ON style_element(case_id);
CREATE INDEX IF NOT EXISTS idx_style_element_type ON style_element(element_type, normalized_code);
CREATE INDEX IF NOT EXISTS idx_formula_style_room ON style_matching_formula(style_code, room_type, status);
CREATE INDEX IF NOT EXISTS idx_product_match_rspu ON product_style_match(rspu_id);
CREATE INDEX IF NOT EXISTS idx_product_match_score ON product_style_match(overall_score DESC);
```

---

## 5. AI 网页端提取方案

### 5.1 为什么先在网页端做？

| 优点 | 说明 |
|:-----|:-----|
| **零代码启动** | 不需要立刻改后端，先用 ChatGPT/Kimi 网页版验证 Prompt 和数据质量。 |
| **人工强兜底** | 每批案例提取完可以人工复核、修正、映射到受控词表。 |
| **低成本试错** | 公式和元素 taxonomy 未定稿前，不需要写导入接口。 |
| **可复制 SOP** | 把 Prompt 和复核清单固化成操作手册，后续运营人员可独立执行。 |

### 5.2 网页端输入

1. **成功案例图**：1 张整体场景图 + 2-4 张单品图。
2. **失败案例图**：风格冲突、材质打架、比例失调等反面案例。
3. **参考文字**：学术论文摘要、设计评论、品牌介绍（可选，用于增强上下文）。

### 5.3 推荐 Prompt 模板与输出格式

> 具体导入 JSON Schema、Excel 模板和字段校验规则见 `docs/03-guides/风格数据库导入数据格式.md`。

```markdown
你是资深家具/室内设计师，擅长从设计案例中提炼风格元素和搭配原则。
请严格按以下 JSON 格式输出，不要添加任何额外解释。

任务：
1. 分析上传的图片和文字，识别案例中的家具元素。
2. 判断这是「成功案例」还是「失败案例」。
3. 如果是成功案例，总结其搭配公式；如果是失败案例，说明问题所在。

受控词表（必须从这些值中选择，不要创造新词）：
- 风格：中古风(MC)、奶油风(CR)、意式极简(IT)、法式(FR)、侘寂(WJ)、新中式(NC)、包豪斯(BA)、工业风(IN)、孟菲斯(MP)
- 家装品类：休闲椅(FS)、沙发(SF)、桌几(TB)、柜类(FC)、软装(HD)
- 颜色：焦糖棕、米白、原木色、深胡桃、黑色、白色、灰色
- 材质：实木、藤编、PE仿藤、皮革、亚麻、羊羔绒、金属、亚克力、塑料

输出 JSON 格式：
{
  "case_name": "案例名称",
  "style_code": "MC",
  "room_type": "living_room",
  "is_success": true,
  "source_type": "设计杂志",
  "source_url": "",
  "description": "简短描述",
  "elements": [
    { "type": "category", "value": "休闲椅", "normalized_code": "FS", "is_primary": true, "confidence": "high" },
    { "type": "material", "value": "柚木", "normalized_code": "WO", "is_primary": true, "confidence": "high" },
    { "type": "material", "value": "真藤", "normalized_code": "TN", "is_primary": false, "confidence": "mid" },
    { "type": "color", "value": "焦糖棕", "is_primary": true, "confidence": "high" },
    { "type": "scene", "value": "客厅", "normalized_code": "living_room", "is_primary": true, "confidence": "high" }
  ],
  "formula": {
    "name": "中古风客厅实木藤编椅搭配公式",
    "must_have": [{ "type": "material", "values": ["实木", "藤编"], "role": "primary" }],
    "compatible": [
      { "type": "style", "values": ["MC", "WJ"], "weight": 0.30, "reason": "同属自然复古谱系" },
      { "type": "material", "values": ["亚麻", "皮革"], "weight": 0.25, "reason": "质感对比" },
      { "type": "color", "relation": "analogous", "values": ["焦糖棕", "米白", "原木色"], "weight": 0.20, "reason": "大地色系" }
    ],
    "avoid": [
      { "type": "material", "values": ["塑料", "亚克力"], "reason": "破坏中古质感" },
      { "type": "color", "values": ["荧光色"], "reason": "与中古低饱和调性冲突" }
    ],
    "spatial_hint": { "seat_height_delta_cm": [25, 30], "scale_ratio": [0.8, 1.2] }
  },
  "negative_lesson": "",
  "review_notes": ""
}
```

### 5.4 批量提取 SOP

1. 准备 10-20 张同风格案例图。
2. 把 Prompt + 受控词表一次性粘贴给 AI，上传图片。
3. 让 AI 逐条输出 JSON，人工复制到统一文件中。
4. 复核：`normalized_code` 是否都在 `category_dict` 里？`weight` 之和是否合理？
5. 导出为 `style_knowledge_import.json` 或 Excel。

---

## 6. 与现有 RSDP 能力的结合

### 6.1 产品录入时自动归类

```
AI 识别产品 → 得到六维标签/颜色/材质/场景
              ↓
        StyleMatchingService
              ↓
    1. 把 AI 标签 → style_element 元素
    2. 匹配 style_matching_formula
    3. 计算 overall_score
              ↓
    写入 product_style_match
              ↓
    前端展示：
    - 该产品属于哪些风格知识？
    - 命中了哪些元素？
    - 置信度高/中/低？
```

### 6.2 搭配推荐时引入公式

现有 `POST /api/v1/matching/recommend` 可以升级为三层召回：

| 召回层 | 依据 | 作用 |
|:-------|:-----|:-----|
| **第一层：公式召回** | 根据当前 RSPU 的风格/品类，找匹配的 `style_matching_formula` | 保证推荐有设计依据 |
| **第二层：向量精排** | ChromaDB 中 512 维向量相似度 | 保证视觉协调 |
| **第三层：空间/预算过滤** | 尺寸约束、价格带、可用 RSKU | 保证方案可落地 |

推荐结果示例：

```json
{
  "anchor_rspu_id": "FS-MC-001-M",
  "room_type": "living_room",
  "candidates": [
    {
      "rspu_id": "SF-MC-003-L",
      "score": 0.92,
      "formula_id": "FORM-MC-LIVING-001",
      "reasons": [
        "风格同为中古风（公式权重 0.30）",
        "材质呼应：沙发为皮革，与主椅实木藤编形成质感对比（公式权重 0.25）",
        "颜色在「焦糖棕、米白、原木色」大地色系内（公式权重 0.20）"
      ],
      "vector_similarity": 0.78,
      "formula_score": 0.95
    }
  ]
}
```

### 6.3 失败案例用于「避雷提示」

当用户选择的组合触发某条公式的 `avoid` 规则时，系统可以给出警告：

> ⚠️ 该组合与「中古风客厅公式」冲突：亚克力材质会破坏中古质感。

---

## 7. 可行性分析

### 7.1 技术可行性：高 ✅

- 新增表全部是标准 PostgreSQL JSONB，无新技术。
- 复用现有 `category_dict`、`rspu_master`、`ChromaDB`、MinIO。
- AI 提取阶段在网页端完成，不需要改 AI 调用层。
- 搭配打分可用纯 Java 实现，无需额外模型。

### 7.2 数据可行性：中 ⚠️

- **成功案例**：设计杂志、品牌画册、 Pinterest、小红书、学术论文配图较易获取。
- **失败案例**：需要设计师复盘或项目实拍，来源相对少。
- **LLM 提取质量**：多模态大模型能识别风格和材质，但对「为什么搭得好」的抽象能力有限，需要人工复核。
- **受控词表一致性**：必须先把 `category_dict` 完善，否则 AI 会输出未定义的词。

### 7.3 业务价值：高 ✅

- 把「设计师经验」变成可查询、可复用的数据资产。
- 推荐结果带解释，提升用户信任度。
- 形成差异化能力：不是 generic 的相似图推荐，而是「RSDP 自己的设计知识」。
- 支持后续扩展：按项目风格一键生成方案、培训新人设计师。

### 7.4 主要风险与缓解

| 风险 | 影响 | 缓解措施 |
|:-----|:-----|:---------|
| **LLM 提取不一致** | 元素/公式质量参差 | 提供严格受控词表 + Prompt 强制 JSON + 人工复核 |
| **受控词表覆盖不全** | AI 输出未定义值 | 先完善 `category_dict`，建立「发现新词 → 申请入库」流程 |
| **成功案例版权** | 使用外部图片可能侵权 | 仅内部使用、标注来源、优先使用自有项目/可商用图库 |
| **公式过拟合** | 案例少导致推荐局限 | 每条公式记录 success_count/fail_count，低于阈值时标记为「实验性」 |
| **维护成本高** | 风格潮流变化快 | 公式版本化 + 反馈闭环，定期根据 `matching_feedback` 淘汰低效公式 |

---

## 8. 实施路线

### Phase 0：方案与词表（1 周）

- 评审通过本 ADR。
- 完善 `category_dict`：补充 `mood`、`function`、`room_type` 等字典类型。
- 确定首批 3-5 个重点风格（如中古、侘寂、意式极简）作为试点。

### Phase 1：数据库 + 导入接口（1 周）

- 新增 `style_case`、`style_element`、`style_matching_formula`、`product_style_match`、`matching_feedback` 表。
- 实现 `POST /api/v1/style-knowledge/import`（支持 JSON / Excel）。
- 实现后台管理页面：案例列表、公式列表、导入页面。

### Phase 2：Kimi 网页端提取与试点数据（1-2 周）

- 固化网页端 Prompt 和复核清单。
- 收集 50-100 个成功案例 + 20-30 个失败案例。
- 按 `docs/03-guides/风格数据库导入数据格式.md` 整理成 JSON/Excel。
- 人工复核后导入 RSDP。

### Phase 3：产品匹配与推荐集成（1-2 周）

- 在产品录入流程中调用 `StyleMatchingService.match(rspu_id)`。
- 改造 `POST /api/v1/matching/recommend`，引入公式召回层。
- 前端展示推荐理由和避雷提示。

### Phase 4：反馈闭环（持续）

- 前端记录用户对推荐结果的接受/拒绝/下单。
- 定期跑报表，淘汰低分公式，补充新案例。

---

## 9. 立即可做的 3 件事

1. **评审本 ADR** — 确认是否按此方向推进。
2. **选定试点风格** — 建议首批做「中古风」和「侘寂风」，案例多、标签边界较清晰。
3. **跑一轮网页端提取验证** — 用 10 张中古风客厅案例图 + 上述 Prompt，输出 JSON 看质量是否可用。

---

## 10. 一句话总结

> **风格数据库 Skill = 把成功/失败设计案例喂给 AI 提取「元素 + 搭配公式」，人工复核后导入 RSDP；产品录入时自动匹配归类，用户选品时按公式推荐并给出可解释的理由。它让 RSDP 从「产品档案」升级为「设计知识库」。**
