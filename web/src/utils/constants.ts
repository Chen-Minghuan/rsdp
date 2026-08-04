/** 图片加载失败时的统一占位图（灰色块 SVG data URI）。 */
export const IMAGE_FALLBACK_SRC =
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='80' height='80'><rect width='100%25' height='100%25' fill='%23f0f0f0'/></svg>"

// 角色常量
export const ROLES = {
  ADMIN: 'ADMIN',
  EDITOR: 'EDITOR',
  VIEWER: 'VIEWER',
  USER: 'USER',
  FACTORY_ADMIN: 'FACTORY_ADMIN',
  DESIGNER: 'DESIGNER'
} as const

// 权限常量（与后端 Permissions.java 保持一致）
export const PERMISSIONS = {
  PRODUCT_READ: 'product:read',
  PRODUCT_CREATE: 'product:create',
  PRODUCT_UPDATE: 'product:update',
  PRODUCT_DELETE: 'product:delete',
  PRODUCT_REVIEW: 'product:review',
  PRODUCT_IMPORT: 'product:import',

  FACTORY_READ: 'factory:read',
  FACTORY_CREATE: 'factory:create',
  FACTORY_UPDATE: 'factory:update',
  FACTORY_DELETE: 'factory:delete',

  RSKU_READ: 'rsku:read',
  RSKU_CREATE: 'rsku:create',
  RSKU_UPDATE: 'rsku:update',
  RSKU_DELETE: 'rsku:delete',
  RSKU_IMPORT: 'rsku:import',

  QUOTE_READ: 'quote:read',
  QUOTE_GENERATE: 'quote:generate',
  QUOTE_EXPORT: 'quote:export',

  SCHEME_READ: 'scheme:read',
  SCHEME_CREATE: 'scheme:create',
  SCHEME_UPDATE: 'scheme:update',
  SCHEME_DELETE: 'scheme:delete',

  DICT_CREATE: 'dict:create',
  DICT_UPDATE: 'dict:update',

  USER_READ: 'user:read',
  USER_CREATE: 'user:create',
  USER_UPDATE: 'user:update',
  USER_DELETE: 'user:delete',
  USER_RESET_PASSWORD: 'user:reset-password',

  COLLECTION_READ: 'collection:read',
  COLLECTION_CREATE: 'collection:create',
  COLLECTION_UPDATE: 'collection:update',
  COLLECTION_DELETE: 'collection:delete',

  CAPABILITY_READ: 'capability:read',
  CAPABILITY_CREATE: 'capability:create',
  CAPABILITY_UPDATE: 'capability:update',
  CAPABILITY_DELETE: 'capability:delete',

  DESIGNER_PROFILE_READ: 'designer:profile:read',
  DESIGNER_PROFILE_UPDATE: 'designer:profile:update',

  RECOMMENDATION_SCORE_CONFIG_READ: 'recommendation:score:config:read',
  RECOMMENDATION_SCORE_CONFIG_UPDATE: 'recommendation:score:config:update',

  SCHEME_CANDIDATE_READ: 'scheme:candidate:read',
  SCHEME_CANDIDATE_CREATE: 'scheme:candidate:create',
  SCHEME_CANDIDATE_UPDATE: 'scheme:candidate:update',
  SCHEME_CANDIDATE_DELETE: 'scheme:candidate:delete',
  PROJECT_READ: 'project:read',
  PROJECT_CREATE: 'project:create',
  PROJECT_UPDATE: 'project:update',
  PROJECT_DELETE: 'project:delete',

  ORDER_READ: 'order:read',
  ORDER_CREATE: 'order:create',
  ORDER_UPDATE: 'order:update',
  ORDER_DELETE: 'order:delete',

  FAVORITE_READ: 'favorite:read',
  FAVORITE_WRITE: 'favorite:write',

  ADMIN_ASYNC_METRICS: 'admin:async-metrics',
  ADMIN_VECTOR_BACKFILL: 'admin:vector-backfill'
} as const

// ========== 字典类型元数据（字典管理中心） ==========

/**
 * 字典类型元数据。readonly=true 表示被系统逻辑引用，页面仅供查看。
 */
export interface DictTypeMeta {
  dictType: string
  label: string
  readonly?: boolean
  /** 类型用途说明（选中后展示在右栏顶部说明区） */
  description?: string
  /** 消费方标签（如 AI识别候选 / 下拉选项 / 系统引用） */
  usages?: string[]
}

/**
 * 字典类型分组。数组顺序即页面展示顺序；
 * 后端返回配置外的类型时，页面兜底归入"其他"组（只读）。
 */
export interface DictTypeGroup {
  key: string
  label: string
  /** 分组说明（左栏分组标题下小字） */
  description?: string
  types: DictTypeMeta[]
}

/**
 * 字典类型四分组配置（顺序即展示顺序）。
 */
export const DICT_TYPE_GROUPS: DictTypeGroup[] = [
  {
    key: 'product-attr',
    label: '产品属性',
    description: 'AI 识别候选词与产品档案下拉选项的统一来源',
    types: [
      { dictType: 'category', label: '品类', description: '产品品类（RSPU 编码首段）。AI 识别品类枚举、六维维度定义与枚举均按品类码隔离', usages: ['AI识别候选', '编码体系'] },
      { dictType: 'style', label: '风格', description: '产品风格标签（如现代简约/奶油风），用于风格匹配评分与产品筛选', usages: ['AI识别候选', '下拉选项'] },
      { dictType: 'scene', label: '场景', description: '适用空间场景标签（如客厅/卧室），可多选', usages: ['AI识别候选', '下拉选项'] },
      { dictType: 'material', label: '材质', description: '表面材质标签；同时作为六维 E 维度（表面材质）的统一取值来源', usages: ['AI识别候选', '下拉选项'] },
      { dictType: 'fabric', label: '面料', description: '软包面料标签（如科技布/头层牛皮）', usages: ['AI识别候选', '下拉选项'] },
      { dictType: 'color', label: '颜色', description: '产品主色标签', usages: ['AI识别候选', '下拉选项'] },
      { dictType: 'size', label: '尺寸', description: '尺寸规格标签（RSPU 编码尺寸段参考）', usages: ['下拉选项', '编码体系'] },
      { dictType: 'wood_type', label: '木材', description: '木材种类标签（如白蜡木/胡桃木）', usages: ['下拉选项'] }
    ]
  },
  {
    key: 'six-dim',
    label: '六维标签',
    description: '产品形态特征枚举（A~F 六维），驱动 AI 识别、同义词归一与形态筛选；按品类隔离维护',
    types: [
      { dictType: 'six_dim_schema', label: '维度定义', description: '各品类 A~F 维度的语义定义（标签+取值说明），直接进入 AI 识别 prompt 与前端展示；新增品类定义由数据脚本维护', usages: ['AI prompt', '前端展示'] },
      { dictType: 'six_dim_A', label: '六维-A', description: 'A 维度枚举（多数品类为整体造型/轮廓，办公家具为二级品类细分）。编码规则：{品类码}-{中文名}，如 SF-宽厚扶手', usages: ['AI识别候选', '形态筛选'] },
      { dictType: 'six_dim_B', label: '六维-B', description: 'B 维度枚举（坐具为靠背，桌几为台面，床为床头，灯具为灯罩/出光）。编码规则：{品类码}-{中文名}', usages: ['AI识别候选', '形态筛选'] },
      { dictType: 'six_dim_C', label: '六维-C', description: 'C 维度枚举（坐具为扶手，桌几为边缘工艺，柜类为拉手，灯具为灯臂/连接）。编码规则：{品类码}-{中文名}', usages: ['AI识别候选', '形态筛选'] },
      { dictType: 'six_dim_D', label: '六维-D', description: 'D 维度枚举（各品类统一为腿部/底座/安装方式）。编码规则：{品类码}-{中文名}', usages: ['AI识别候选', '形态筛选'] },
      { dictType: 'six_dim_E', label: '六维-E', description: 'E 维度（表面材质）不设独立枚举，统一引用「材质 / 面料」字典，避免同源属性两处维护', usages: ['引用材质/面料'] },
      { dictType: 'six_dim_F', label: '六维-F', description: 'F 维度枚举（坐具为软包工艺，桌/柜/床为功能件，灯具为装饰元素）。编码规则：{品类码}-{中文名}', usages: ['AI识别候选', '形态筛选'] }
    ]
  },
  {
    key: 'supply-chain',
    label: '工厂与供应链',
    description: 'RSKU 供应侧档案选项（工厂能力 / 工艺 / 物流等）',
    types: [
      { dictType: 'factory_level', label: '工厂等级', description: '工厂分级（如 A/B/C 级），用于供应商评估与筛选', usages: ['下拉选项'] },
      { dictType: 'factory_source_type', label: '工厂来源', description: '工厂合作来源类型（如自有/外协/战略）', usages: ['下拉选项'] },
      { dictType: 'equipment_type', label: '设备类型', description: '工厂生产设备类型（如 CNC/封边机），用于产能档案', usages: ['下拉选项'] },
      { dictType: 'process_type', label: '工艺类型', description: '工厂可承接的工艺类型（如扪皮/油漆）', usages: ['下拉选项'] },
      { dictType: 'material_grade', label: '材质等级', description: 'RSKU 材质版本等级（编码第 6 段参考）', usages: ['下拉选项', '编码体系'] },
      { dictType: 'packaging_type', label: '包装方式', description: '出厂包装方式（如纸箱/木架）', usages: ['下拉选项'] },
      { dictType: 'logistics_method', label: '物流方式', description: '发货物流方式（如专线/快递/整车）', usages: ['下拉选项'] }
    ]
  },
  {
    key: 'business-status',
    label: '业务状态',
    description: '被系统流程与状态机引用，变更会破坏业务逻辑，仅供查看',
    types: [
      { dictType: 'design_order_status', label: '订单状态', readonly: true, description: '设计订单状态机取值，由代码流程驱动', usages: ['系统引用'] },
      { dictType: 'import_row_status', label: '导入行状态', readonly: true, description: 'Excel 导入单行处理状态', usages: ['系统引用'] },
      { dictType: 'import_row_type', label: '导入行类型', readonly: true, description: 'Excel 导入行业务类型（RSPU/RSKU 等）', usages: ['系统引用'] },
      { dictType: 'mapping_status', label: '映射状态', readonly: true, description: '外部数据映射状态', usages: ['系统引用'] },
      { dictType: 'quote_confidence', label: '报价置信度', readonly: true, description: '报价结果置信度分级（V11）', usages: ['系统引用'] },
      { dictType: 'review_status', label: '复核状态', readonly: true, description: 'AI 识别结果人工复核状态', usages: ['系统引用'] },
      { dictType: 'grade', label: '职级', readonly: true, description: '办公家具职级（RSPU 编码第 2 段，EX/MG/ST/PU/CO）', usages: ['系统引用', '编码体系'] },
      { dictType: 'project_type', label: '项目类型', readonly: true, description: '项目分类取值', usages: ['系统引用'] },
      { dictType: 'room_type', label: '房间类型', readonly: true, description: '空间校验与方案中的房间类型', usages: ['系统引用'] }
    ]
  }
]
