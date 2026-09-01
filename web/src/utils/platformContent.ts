/**
 * 官网平台内容（platform_content / platform_banner）内置编码与位置元数据。
 *
 * 管理端「官网内容 - 首页文案配置」用此表把机器 code 映射为官网区块中文说明，
 * 并提供新增内容时的预设模板；未命中映射的 code 视为自定义内容（官网未使用）。
 * 官网消费方见 website/pages/index.vue（/api/v1/public/content/{code}）。
 */

/** 内容形态：json_list = JSON 数组文案（官网首页区块消费）；rich_text = 富文本/HTML */
export type PlatformContentKind = 'json_list' | 'rich_text'

/** 内置内容编码元数据 */
export interface PlatformContentMeta {
  /** 内容编码（platform_content.code） */
  code: string
  /** 中文名 */
  name: string
  /** 官网前台位置说明（管理端列表「前台位置」列展示） */
  placement: string
  /** 内容形态 */
  kind: PlatformContentKind
  /** json_list 时条目数上限（与官网组件 slice 保持一致） */
  maxItems?: number
  /** 补充说明（如官网兜底行为） */
  note?: string
}

/** 内置内容编码元数据表（新增内置编码时同步维护） */
export const PLATFORM_CONTENT_METAS: readonly PlatformContentMeta[] = [
  {
    code: 'home_trio_cards',
    name: '必逛好物',
    placement: '官网首页区块 4「必逛好物」',
    kind: 'json_list',
    maxItems: 3,
    note: '官网最多展示前 3 条；无数据或 JSON 解析失败时显示静态兜底'
  },
  {
    code: 'home_service_cards',
    name: '服务卡',
    placement: '官网首页区块 5「服务卡」',
    kind: 'json_list',
    maxItems: 4,
    note: '官网最多展示前 4 条；无数据或 JSON 解析失败时显示静态兜底'
  },
  {
    code: 'platform_user_agreement',
    name: '服务协议',
    placement: '官网暂未启用入口',
    kind: 'rich_text',
    note: '种子占位文案；官网前端当前无任何入口读取该编码'
  },
  {
    code: 'platform_consulting_service',
    name: '客服咨询',
    placement: '官网暂未启用入口',
    kind: 'rich_text',
    note: '种子占位文案；官网前端当前无任何入口读取该编码'
  }
] as const

const metaMap = new Map(PLATFORM_CONTENT_METAS.map((m) => [m.code, m]))

/**
 * 按编码查询内置元数据。
 *
 * @param code 内容编码
 * @returns 命中返回元数据，未命中（自定义内容）返回 null
 */
export function getPlatformContentMeta(code: string): PlatformContentMeta | null {
  return metaMap.get(code) ?? null
}

/**
 * 管理端列表「前台位置」列展示文案（含未知 code 兜底）。
 *
 * @param code 内容编码
 * @returns 位置说明文案
 */
export function platformContentPlacement(code: string): string {
  return metaMap.get(code)?.placement ?? '自定义 · 官网未使用'
}

/**
 * 是否内置编码（内置编码禁删、禁改 code，删除会导致官网对应区块 404）。
 *
 * @param code 内容编码
 */
export function isBuiltinPlatformContent(code: string): boolean {
  return metaMap.has(code)
}

/**
 * 是否 JSON 数组类内容（决定编辑时用结构化条目编辑器还是 textarea）。
 *
 * @param code 内容编码
 */
export function isJsonListPlatformContent(code: string): boolean {
  return metaMap.get(code)?.kind === 'json_list'
}

/** 新增内容预设模板（示例内容与 database/V37 种子、website 首页静态兜底一致） */
export interface PlatformContentPreset {
  /** 预设编码（选择后锁定不可改） */
  code: string
  /** 下拉展示名 */
  label: string
  /** 预填示例内容（JSON 数组字符串） */
  content: string
}

/** 可选预设模板列表；调用方需过滤掉库中已存在的 code 防重复创建 */
export const PLATFORM_CONTENT_PRESETS: readonly PlatformContentPreset[] = [
  {
    code: 'home_trio_cards',
    label: '必逛好物（首页区块 4）',
    content:
      '[{"title":"大减价","desc":"百余款商品 5 折起 · 即日至 8 月 31 日"},{"title":"当季新品","desc":"秋冬系列全新上市 · 探索新材质"},{"title":"更低价格","desc":"同样的设计 · 更可持续的价格"}]'
  },
  {
    code: 'home_service_cards',
    label: '服务卡（首页区块 5）',
    content:
      '[{"title":"送货服务","desc":"珠三角 48 小时达，全国物流可追踪"},{"title":"安装服务","desc":"专业师傅上门，安装完毕清理现场"},{"title":"退换保障","desc":"30 天无理由退换（定制款除外）"},{"title":"免费设计","desc":"AI 户型搭配 + 设计师 1v1 复核"}]'
  }
] as const

/** Banner 位置选项（platform_banner.position；官网当前仅 home_top 生效） */
export const BANNER_POSITIONS = [{ value: 'home_top', label: '首页顶部轮播（Hero）' }] as const

/**
 * Banner 位置中文名（未知位置原样返回便于排查）。
 *
 * @param position 位置编码
 */
export function bannerPositionLabel(position: string): string {
  return BANNER_POSITIONS.find((p) => p.value === position)?.label ?? position
}
