# RSDP 出厂价权限治理与 AI 搭配预算口径修正 · 实施方案

> 本文档是可直接执行的实施规格书。执行前请先阅读 `AGENTS.md`（项目级指令）并遵守其中的全部约定
> （测试门槛、实体-DB 对账、文档同步、迁移三处同步等）。
>
> 涉及两个批次，均为 P0：
> - **批次 1：出厂价泄露封堵**（掩码缺失修复）
> - **批次 2：AI 搭配预算口径从出厂价改为销售价**（顺带消除出厂价外泄给第三方 LLM 的问题）
>
> 批次 3（权限点化与审计）为 P1 可选项，本次不实施，仅在文末记录。

---

## 一、背景与问题定义

### 1.1 既定策略（保持不变）

`DataScopeHelper.canViewFactoryPrice()` 定义的出厂价可见性策略**不变**：

| 角色 | 出厂价可见性 |
|------|-------------|
| ADMIN / EDITOR（平台运营） | 全量可见 |
| FACTORY_ADMIN（工厂管理员） | 仅本厂可见 |
| DESIGNER / VIEWER / USER / 其他 | 一律不可见（掩码为 null） |
| 官网匿名访客 | 不可见（仅零售参考价/销售价） |
| 外部 LLM（DashScope 等） | **不得注入 prompt**（本次新增红线） |

### 1.2 当前已正确的部分（不要改动）

- 报价单 `QuoteService.buildItem`（cost/sale 双口径 + 掩码 + 毛利仅内部）
- 订单、价格历史 `PriceHistoryController`（已改 `canViewFactoryPrice` 门控）
- RSKU 详情 `RskuService.toResponse` 掩码
- 定价试算 `PricingPreviewService` 权限掩码
- 方案报价 priceChanges 掩码（`SchemeService` 两处）
- 出厂价 AES-256-GCM 落库加密、审计快照递归加密
- 官网 `PublicAiMatchService` 脱敏（无 factory*/totalPrice/rskuId 等字段，有序列化断言测试）

### 1.3 待修复问题清单

| # | 问题 | 代码位置 | 影响 |
|---|------|---------|------|
| L1 | 产品库列表「最低出厂价」无掩码 | `server/src/main/java/com/rsdp/service/ProductQueryService.java`：`toSummary()` 约 1217 行无条件 `summary.setMinFactoryPrice(...)`；`batchMinFactoryPrices()` 约 615-640 行 | DESIGNER/USER/VIEWER 打开产品库即可看到全库每个产品的最低出厂价 |
| L2 | 搭配关系推荐透出出厂价 | `server/src/main/java/com/rsdp/service/RspuRelationService.java`：约 258 行按 `canAccessRskuFactory` 过滤（DESIGNER 的 DataScope=ALL 直接放行）、约 299 行 `response.setTargetMinPrice(...)` 无掩码 | 设计师在产品详情「搭配关系」Tab 可见出厂价 |
| L3 | AI 空间搭配预算按出厂价比较，且出厂价注入 LLM prompt | `server/src/main/java/com/rsdp/service/AiMatchingService.java`：`buildPrompt()` 约 271 行「预算上限」+ 约 278/287 行候选产品「最低报价」取自 `batchMinPrices()`（约 358-376 行，`RskuSupply::getFactoryPrice`） | ① 客户预算是售价预算，按成本筛选导致方案按售价必然大幅超预算；② 出厂价明文随 prompt 发送给第三方 DashScope，商业机密外泄 |
| L4 | 锚点推荐 prompt 同样注入出厂价 | `AiMatchingService.java` 约 455-475 行（`batchMinPrices` + 「最低报价」文案） | 同 L3 |
| L5 | `RoomSchemeResponse.totalPrice` 按出厂价求和 | `AiMatchingService.buildResponse()` 约 407/438/444 行 | 对设计师掩码为 null，前端 `RoomSchemeView.vue` 只剩空「出厂价」列，体验断裂；应补售价口径总价 |
| L6 | 户型图搭配管理端链路同源 | `FloorPlanMatchingService` 复用 `AiMatchingService.generateRoomScheme` | 随 L3 修复自动覆盖，需验证 LLM 兜底分支 |

### 1.4 预算口径的业务依据

价格体系 P1~P3 落地后，订单按**标准售价**计价（`PricingService.resolveSalePrice`：`rspu_master.retail_price` 建议销售价 → 成本 × 品类倍率 `pricing_rule` → 成本 × 全局倍率 `pricing.markup.global` 兜底），折扣率乘的也是标准售价。客户输入的预算天然是「我最多愿意付多少钱」= 售价预算。当前 AI 搭配按出厂价（成本）比较，按默认 2.5 倍率估算，3 万元售价预算的客户会被按 3 万成本选品，实际售价方案约 7.5 万，超预算 150%。

---

## 二、批次 1：出厂价泄露封堵

### 2.1 产品列表最低出厂价掩码（L1）

**后端 `ProductQueryService.java`**：

1. `toSummary(...)` 中 `setMinFactoryPrice` 加门控：

```java
// 最低出厂价仅平台运营人员可见。
// 注意：该值是跨厂聚合的最低价，无法归属单一工厂做 canViewFactoryPrice 逐厂判断，
// 且 FACTORY_ADMIN 看到的可能是友商价格，因此对工厂角色同样掩码；
// 工厂查看本厂报价走 RSKU 详情（已有按厂掩码）。
summary.setMinFactoryPrice(
    com.rsdp.security.SecurityOperatorContext.isPlatformStaff()
        ? minPriceMap.get(rspu.getRspuId())
        : null);
```

2. `batchMinFactoryPrices(...)` 方法入口加短路，非平台员工直接返回空 Map（省一次查询）：

```java
if (!com.rsdp.security.SecurityOperatorContext.isPlatformStaff()) {
    return Map.of();
}
```

3. 检查 `ProductQueryService` 中所有调用 `batchMinFactoryPrices` / `toSummary` 的路径（列表、回收站、全库视图等），确认掩码语义一致生效。

**前端 `web/src/views/ProductListView.vue`**：

4. `minFactoryPrice` 列（约 526 行）加角色显隐：`v-if` 按 user store 中角色判定（仅 ADMIN/EDITOR 显示）。项目已有按权限/角色控制列与按钮的既有模式，复用之。后端掩码为兜底层，前端隐藏为体验层，两层都要。

5. 检查「添加产品」弹窗（`QuoteBuilderView.vue`，列「最低出厂价」）若存在同名字段展示，同样处理：非平台员工隐藏该列（确认其数据来源接口是否也透出了 minFactoryPrice，若是，同步在后端对应 DTO 掩码）。

### 2.2 搭配关系 targetMinPrice 掩码（L2）

**后端 `RspuRelationService.java`**：

1. `toResponse(...)` 约 299 行 `setTargetMinPrice` 同样改为仅平台员工透出：

```java
response.setTargetMinPrice(
    com.rsdp.security.SecurityOperatorContext.isPlatformStaff()
        ? minPriceMap.get(targetRspuId)
        : null);
```

2. 约 258 行的 `canAccessRskuFactory` 数据范围过滤**保留不动**（它控制的是"能不能看到这个工厂的 RSKU 存在"，与价格可见性是两件事）。

**前端**：产品详情「搭配关系」Tab（`web/src/components/product/RelationTab.vue` 或对应组件）最低价列对非平台员工隐藏。

### 2.3 批次 1 测试要求

- `ProductQueryServiceTest`：新增用例——DESIGNER/USER 视角 `minFactoryPrice == null`；ADMIN 视角有值；`batchMinFactoryPrices` 非平台员工不发起查询（可用 mock verify 断言）。
- `RspuRelationServiceTest`：同口径断言 `targetMinPrice` 掩码。
- 全量 `mvn test` 零失败；`web` 端 `type-check + lint + vitest + build` 全过。
- 实体-DB 对账零差异（本批次无 DB 变更，对账应天然通过）。

---

## 三、批次 2：AI 搭配预算口径修正（出厂价 → 销售价）

### 3.1 候选价格改销售价（L3/L4，核心）

**`AiMatchingService.java`**：

1. 注入 `PricingService`（构造器注入，与项目既有模式一致）。

2. 新增方法 `batchMinSalePrices(List<String> rspuIds)`，语义：
   - 复用现有 `selectCapableByRspuIds` + `canAccessFactory` 数据范围过滤拿到候选 RSKU；
   - 对每个 RSPU 取其候选 RSKU 经 `pricingService.resolveSalePrice(rspu, rsku)` 解析出的**最低销售价**（不是最低成本对应的售价，而是所有候选 RSKU 售价中的最小值；若实现上为简化取"最低成本 RSKU 的售价"也可接受，需在 JavaDoc 注明口径）；
   - `resolveSalePrice` 返回 null（三级链都解析不出）时该 RSPU 不进 Map。

3. `buildPrompt()`（约 271-287 行）与锚点推荐 prompt（约 455-475 行）：
   - 候选行价格字段从 `batchMinPrices`（出厂价）改为 `batchMinSalePrices`（销售价）；
   - 文案「最低报价」改为「**参考售价**」；
   - 无售价的候选标注「价格待定」，**不阻止入选**（搭配方案是推荐性质；硬性拦截发生在报价单/订单环节，该处已有"未定价整单拦截"逻辑，保持不变）；
   - 「预算上限」文案补充说明为售价口径，例如：`预算上限：{budgetLimit} 元（指客户应付的销售价总额，请确保所选产品参考售价之和不超过预算）`。

4. **红线确认**：改造完成后，prompt 全文中不得出现任何出厂价数值或「出厂价/成本」字样。grep 自检 + 单测断言双重确认。

5. 旧的 `batchMinPrices`（出厂价版）若不再有任何调用方，删除；若 `buildResponse` 等仍需成本数据（见 3.2），保留但改名/注释明确"仅内部成本口径，禁止进 prompt"。

### 3.2 响应总价补销售价口径（L5）

**`AiMatchingService.buildResponse()`（约 390-448 行）**：

1. 新增 `totalSalePrice`：对所选产品按 `batchMinSalePrices` 的售价求和（未定价产品跳过求和；若存在跳过项，可考虑加 `boolean hasUnpricedItems` 提示字段，非强制）。
2. `RoomSchemeResponse` 新增字段 `totalSalePrice`（BigDecimal），**对所有可见角色返回**——设计师和官网都需要这个数字。
3. 既有 `totalPrice`（出厂价口径，掩码逻辑不变）保留兼容；在 JavaDoc 标注"成本口径，仅平台/本厂可见，前端新代码应使用 totalSalePrice"。
4. 各 item 的 `factoryPrice`/`subtotal` 掩码逻辑**不动**。

**前端 `RoomSchemeView.vue`**：

5. 「出厂价」列（约 51 行）改为「参考售价」列，取值改为 item 级销售价（若 item 级售价未透出，则在 `SchemeItemResponse` 或 RoomScheme item 上补 `salePrice` 字段——同样全角色可见）。
6. 总价展示（约 166 行 `scheme.totalPrice`）改为 `totalSalePrice`，对所有角色可见。
7. `web/src/types/` 与 `api/` 相关类型同步补 `totalSalePrice` / `salePrice`。

### 3.3 同源链路核查（L6）

1. `FloorPlanMatchingService`：走 `AiMatchingService.generateRoomScheme`，3.1 自动覆盖；检查其 LLM 异常时的规则兜底分支（`ruleFallbackScheme` 相关），若兜底结果组装中透出价格，同样改销售价口径。
2. 官网 `PublicAiMatchService`：当前用零售参考价（`retailPrice`）求和 `totalRetailPrice`。建议统一改走 `PricingService.resolveSalePrice`，消除「零售参考价」与「标准售价」两个口径并存的漂移风险；**脱敏红线不变**（序列化断言测试保持全绿）。
3. `RoomDimensionRules` R5「有效报价」判定（有未软删且有出厂价的 RSKU）保持不变——它是数据质量门槛，不是价格透出。

### 3.4 批次 2 测试要求

- `AiMatchingServiceTest`：
  - 断言生成的 prompt 文本中包含销售价数值与「参考售价」字样；
  - 断言 prompt 中**不出现**出厂价数值与「出厂价」字样；
  - `buildResponse`：DESIGNER 视角 `totalSalePrice` 非空、`factoryPrice`/`subtotal`/`totalPrice` 为 null；ADMIN 视角两者皆有；
  - 新增 DESIGNER 视角 `RoomSchemeResponse` 序列化断言：JSON 中不含 `factoryPrice`/`costPrice` 非空值（复用 `PublicAiMatchServiceTest` 已有的序列化断言范式）。
- `FloorPlanMatchingServiceTest`、`PublicAiMatchServiceTest`：适配新口径。
- 全量 `mvn test` 零失败；`web` 端 `type-check + lint + vitest + build` 全过；`website` 若涉及则 `pnpm build` 通过。
- 实体-DB 对账零差异（本批次无 DB 变更）。

### 3.5 实测验证（合并前必做）

prompt 变更会影响 LLM 选品行为。合并前在开发环境用真实数据验证：
1. 管理端跑 1-2 个真实空间搭配（户型图搭配 + 锚点推荐各一），确认选品总价按售价口径落在预算内；
2. 确认未定价产品标注「价格待定」且不阻断生成；
3. 记录实测结果到 `docs/05-status/当前进度.md` 对应条目。

---

## 四、文档同步要求（AGENTS.md 约定）

完成后同步更新：

- `docs/02-architecture/04-API设计.md`：产品列表 `minFactoryPrice` 可见性说明、搭配关系 `targetMinPrice` 可见性说明、AI 搭配/锚点推荐/户型图搭配接口的预算口径（售价）与 `totalSalePrice`/`salePrice` 新字段；
- `docs/05-status/当前进度.md`：新增本次完成的两个批次条目（格式仿照既有条目，含测试数量与验证结果）；
- `docs/05-status/待办事项.md`：如有遗留项则登记；
- 本批次无数据库迁移，无需动 AGENTS.md 迁移清单。

---

## 五、明确不做的范围（范围控制）

- 不改 `DataScopeHelper.canViewFactoryPrice` 的策略定义本身；
- 不改报价单/订单/价格历史/RSKU 详情/定价试算的既有掩码逻辑（已正确）；
- 不新增数据库迁移、不改表结构；
- 不做批次 3（`price:factory:read` 权限点化、看出厂价审计日志）——如需做另行发起；
- 不改官网脱敏字段集与公开接口限流；
- 不引入本地 Ollama 切换（与本方案无关，且即使切换，预算口径修正依然必要）。

---

## 六、批次 3 备忘（本次不实施）

1. 新增 `price:factory:read` 权限码（V43 迁移 + V1/reset 三处同步），种子授给 ADMIN/EDITOR/FACTORY_ADMIN；`canViewFactoryPrice` 改为权限点 + 工厂范围二次校验，便于未来新增角色零改码。
2. 出厂价解密返回操作记 audit_log（谁、何时、看了哪个厂的价）。
3. 横断测试守卫：核心 DTO 在非平台员工视角的序列化结果断言不含出厂价，纳入 CI。

---

## 七、验收清单

- [ ] L1 产品列表最低出厂价：DESIGNER/USER/VIEWER/FACTORY_ADMIN 视角为 null，前端列隐藏
- [ ] L2 搭配关系 targetMinPrice：同上掩码
- [ ] L3/L4 AI 搭配与锚点推荐 prompt：价格为销售价、文案为「参考售价」、无出厂价数值与字样
- [ ] L5 `RoomSchemeResponse.totalSalePrice` 全角色可见；设计师搭配页价格信息完整可用
- [ ] L6 户型图搭配管理端链路同源修复验证；官网链路统一 `resolveSalePrice` 且脱敏断言全绿
- [ ] 全量后端测试零失败 + 前端四件套全过 + 实体-DB 对账零差异
- [ ] 真实数据实测记录入库（当前进度.md）
- [ ] API 设计文档与进度文档同步
