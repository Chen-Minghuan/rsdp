import type { ManualFurnitureItem, ManualLayoutRect, ManualLayoutRoom } from '@/types/floorPlan'
import { manualRectContainedByUnion } from '@/utils/manualLayoutGeometry'

export type FurnitureKind = 'desk' | 'l-desk' | 'rounded-l-desk' | 'u-desk' | 'chair' | 'round-table' | 'oval-table' |
  'table' | 'cabinet' | 'sofa' | 'counter' | 'device' | 'plant' | 'wall'

export interface FurnitureTemplate {
  id: string
  name: string
  category: '职员桌' | '主管桌' | '高管桌' | '前台' | '座椅' | '会议与洽谈' | '收纳' | '接待休闲' | '设备辅助'
  kind: FurnitureKind
  widthMm: number
  depthMm: number
  /** 参考图列出的常用占地规格；仍允许手动输入其他尺寸。 */
  sizeOptions?: Array<[number, number]>
  /** 仅柜体高度分型使用，旧实例缺失时按模板默认值显示。 */
  heightMm?: number
  heightMinMm?: number
  heightMaxMm?: number
  /** 从新增目录隐藏，但保留模板以显示、编辑旧布局中的实例。 */
  catalogHidden?: true
}

/** 参数化 CAD 式俯视图例；已有 ID 保持不变以兼容旧布局。 */
export const MANUAL_FURNITURE_TEMPLATES: FurnitureTemplate[] = [
  { id: 'desk-straight', name: '职员直线桌', category: '职员桌', kind: 'desk', widthMm: 1200, depthMm: 600,
    sizeOptions: [[1200, 600], [1400, 600], [1400, 700], [1600, 700]] },
  { id: 'desk-l', name: '职员 L 桌', category: '职员桌', kind: 'l-desk', widthMm: 1400, depthMm: 1400,
    sizeOptions: [[1200, 1200], [1400, 1400], [1600, 1400]] },
  { id: 'desk-l-rounded', name: '职员圆角 L 桌', category: '职员桌', kind: 'rounded-l-desk', widthMm: 1400, depthMm: 1400,
    sizeOptions: [[1400, 1400], [1500, 1500], [1600, 1600]] },
  { id: 'desk-u', name: 'U 形办公桌', category: '职员桌', kind: 'u-desk', widthMm: 2000, depthMm: 1600, catalogHidden: true },
  { id: 'desk-manager', name: '主管直线桌', category: '主管桌', kind: 'desk', widthMm: 1400, depthMm: 600,
    sizeOptions: [[1400, 600], [1400, 700], [1600, 750], [1600, 800]] },
  { id: 'desk-manager-double', name: '主管双侧桌', category: '主管桌', kind: 'desk', widthMm: 1600, depthMm: 700,
    sizeOptions: [[1600, 700], [1600, 750], [1600, 800]] },
  { id: 'desk-manager-l', name: '主管 L 桌', category: '主管桌', kind: 'l-desk', widthMm: 1800, depthMm: 1600,
    sizeOptions: [[1600, 1200], [1600, 1400], [1600, 1600], [1800, 1200], [1800, 1400], [1800, 1600], [2000, 1600], [2200, 1600]] },
  { id: 'desk-manager-l-plain', name: '主管简式 L 桌', category: '主管桌', kind: 'l-desk', widthMm: 1800, depthMm: 1600,
    sizeOptions: [[1600, 1200], [1800, 1400], [1800, 1600], [2000, 1600]] },
  { id: 'desk-executive', name: '高管直线桌', category: '高管桌', kind: 'desk', widthMm: 2000, depthMm: 800,
    sizeOptions: [[2000, 800], [2400, 800], [2800, 850], [3200, 900]] },
  { id: 'desk-executive-l', name: '高管 L 桌', category: '高管桌', kind: 'l-desk', widthMm: 2000, depthMm: 1600,
    sizeOptions: [[2000, 1600], [2400, 1600], [2800, 1600], [3200, 1600]] },
  { id: 'desk-executive-l-double', name: '高管双侧 L 桌', category: '高管桌', kind: 'l-desk', widthMm: 2800, depthMm: 1600,
    sizeOptions: [[2800, 1600], [3200, 1600]] },
  { id: 'desk-training', name: '培训桌', category: '职员桌', kind: 'desk', widthMm: 1200, depthMm: 500, catalogHidden: true },
  { id: 'desk-standing', name: '站立工作台', category: '职员桌', kind: 'desk', widthMm: 1600, depthMm: 700, catalogHidden: true },
  { id: 'counter-straight', name: '直线前台', category: '前台', kind: 'counter', widthMm: 2200, depthMm: 700 },
  { id: 'counter-l', name: 'L 形前台', category: '前台', kind: 'l-desk', widthMm: 2200, depthMm: 1500 },
  { id: 'chair-office', name: '办公转椅', category: '座椅', kind: 'chair', widthMm: 650, depthMm: 650 },
  { id: 'chair-meeting', name: '会议椅', category: '座椅', kind: 'chair', widthMm: 550, depthMm: 580 },
  { id: 'chair-visitor', name: '访客椅', category: '座椅', kind: 'chair', widthMm: 620, depthMm: 620 },
  { id: 'chair-lounge', name: '休闲单椅', category: '座椅', kind: 'chair', widthMm: 850, depthMm: 850 },
  { id: 'stool-high', name: '高脚椅', category: '座椅', kind: 'round-table', widthMm: 450, depthMm: 450 },
  { id: 'table-conference-rect', name: '长方形会议桌', category: '会议与洽谈', kind: 'table', widthMm: 2800, depthMm: 1200 },
  { id: 'table-conference-oval', name: '椭圆形会议桌', category: '会议与洽谈', kind: 'oval-table', widthMm: 2800, depthMm: 1200 },
  { id: 'table-meeting-round', name: '圆形洽谈桌', category: '会议与洽谈', kind: 'round-table', widthMm: 1000, depthMm: 1000 },
  { id: 'table-meeting-square', name: '方形洽谈桌', category: '会议与洽谈', kind: 'table', widthMm: 900, depthMm: 900 },
  { id: 'cabinet-low', name: '底抽柜', category: '收纳', kind: 'cabinet', widthMm: 1200, depthMm: 450,
    heightMm: 400, heightMinMm: 100, heightMaxMm: 400 },
  { id: 'cabinet-short', name: '矮柜', category: '收纳', kind: 'cabinet', widthMm: 1200, depthMm: 450,
    heightMm: 900, heightMinMm: 401, heightMaxMm: 1500 },
  { id: 'cabinet-high', name: '高柜', category: '收纳', kind: 'cabinet', widthMm: 1200, depthMm: 450,
    heightMm: 1800, heightMinMm: 1500, heightMaxMm: 3000 },
  { id: 'cabinet-mobile', name: '活动柜', category: '收纳', kind: 'cabinet', widthMm: 420, depthMm: 500 },
  { id: 'shelf-open', name: '开放书架', category: '收纳', kind: 'cabinet', widthMm: 1200, depthMm: 400 },
  { id: 'locker-staff', name: '员工储物柜', category: '收纳', kind: 'cabinet', widthMm: 900, depthMm: 500 },
  { id: 'sofa-single', name: '单人沙发', category: '接待休闲', kind: 'sofa', widthMm: 900, depthMm: 850 },
  { id: 'sofa-double', name: '双人沙发', category: '接待休闲', kind: 'sofa', widthMm: 1600, depthMm: 850 },
  { id: 'sofa-triple', name: '三人沙发', category: '接待休闲', kind: 'sofa', widthMm: 2100, depthMm: 850 },
  { id: 'coffee-rect', name: '长方形茶几', category: '接待休闲', kind: 'table', widthMm: 1100, depthMm: 550 },
  { id: 'coffee-round', name: '圆形茶几', category: '接待休闲', kind: 'round-table', widthMm: 700, depthMm: 700 },
  { id: 'printer-copier', name: '打印机／复印机', category: '设备辅助', kind: 'device', widthMm: 650, depthMm: 650 },
  { id: 'water-dispenser', name: '饮水机', category: '设备辅助', kind: 'device', widthMm: 400, depthMm: 420 },
  { id: 'plant', name: '绿植', category: '设备辅助', kind: 'plant', widthMm: 500, depthMm: 500 },
  { id: 'whiteboard', name: '白板', category: '设备辅助', kind: 'wall', widthMm: 1800, depthMm: 100 },
  { id: 'display-screen', name: '显示屏／电视', category: '设备辅助', kind: 'wall', widthMm: 1400, depthMm: 100 }
]

export const furnitureById = new Map(MANUAL_FURNITURE_TEMPLATES.map(template => [template.id, template]))
export const furnitureCatalogTemplates = MANUAL_FURNITURE_TEMPLATES.filter(template => !template.catalogHidden)
export const furnitureCategories: FurnitureTemplate['category'][] = [
  '职员桌', '主管桌', '高管桌', '前台', '座椅', '会议与洽谈', '收纳', '接待休闲', '设备辅助'
]

/** 家具按中心点定位；90° 旋转后占地宽深互换。 */
export function furnitureBounds(item: ManualFurnitureItem): ManualLayoutRect {
  const turned = item.rotationDeg === 90 || item.rotationDeg === 270
  const w = turned ? item.depthMm : item.widthMm
  const h = turned ? item.widthMm : item.depthMm
  return { x: item.xMm - w / 2, y: item.yMm - h / 2, w, h }
}

/** 首版用保守外包矩形检查 L/U 形；不把缺口误认为可占用空间。 */
export function furnitureInsideRoom(item: ManualFurnitureItem, room: ManualLayoutRoom): boolean {
  const bounds = furnitureBounds(item)
  return manualRectContainedByUnion(bounds, room.parts)
}

/** 两件家具的占地外包矩形发生正面积重叠；仅作提醒。 */
export function furnitureOverlap(a: ManualFurnitureItem, b: ManualFurnitureItem): boolean {
  const first = furnitureBounds(a)
  const second = furnitureBounds(b)
  return Math.max(first.x, second.x) < Math.min(first.x + first.w, second.x + second.w) &&
    Math.max(first.y, second.y) < Math.min(first.y + first.h, second.y + second.h)
}

/** 使用模板默认参数创建一个可独立修改的家具实例。 */
export function newFurnitureItem(template: FurnitureTemplate, roomClientId: string, xMm: number, yMm: number): ManualFurnitureItem {
  return {
    id: `furniture-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    templateId: template.id,
    roomClientId,
    xMm,
    yMm,
    widthMm: template.widthMm,
    depthMm: template.depthMm,
    rotationDeg: 0,
    ...(template.kind === 'l-desk' || template.kind === 'rounded-l-desk' || template.kind === 'u-desk'
      ? { wingWidthMm: 550 } : {}),
    ...(template.heightMm ? { heightMm: template.heightMm } : {})
  }
}

export type FurnitureArrangement = 'pair' | 'island' | 'meeting' | 'reception'

export const FURNITURE_ARRANGEMENT_COUNTS: Record<FurnitureArrangement, number> = {
  pair: 2,
  island: 4,
  meeting: 6,
  reception: 4
}

/** 按选择顺序排布已存在的家具；只计算新中心点，不改变实例 ID、图例、规格或朝向。 */
export function arrangeFurnitureItems(
  items: ManualFurnitureItem[], arrangement: FurnitureArrangement, gridMm: number
): ManualFurnitureItem[] {
  if (items.length !== FURNITURE_ARRANGEMENT_COUNTS[arrangement] || gridMm <= 0) return []
  const slots: Array<[number, number]> = arrangement === 'pair' ? [[0, 0], [0, 1]]
    : arrangement === 'island' ? [[0, 0], [1, 0], [0, 1], [1, 1]]
      : arrangement === 'meeting' ? [[0, 0], [1, 0], [2, 0], [0, 1], [1, 1], [2, 1]]
        : [[1, 0], [0, 1], [2, 1], [1, 2]]
  const bounds = items.map(furnitureBounds)
  const columns = Math.max(...slots.map(([column]) => column)) + 1
  const rows = Math.max(...slots.map(([, row]) => row)) + 1
  const columnWidths = Array.from({ length: columns }, () => 0)
  const rowHeights = Array.from({ length: rows }, () => 0)
  slots.forEach(([column, row], index) => {
    columnWidths[column] = Math.max(columnWidths[column]!, bounds[index]!.w)
    rowHeights[row] = Math.max(rowHeights[row]!, bounds[index]!.h)
  })
  const gapX = Math.max(gridMm * 2, arrangement === 'island' ? 200 : 350)
  const gapY = Math.max(gridMm * 2, arrangement === 'meeting' ? 1400 : arrangement === 'pair' ? 700 : 400)
  const totalWidth = columnWidths.reduce((sum, width) => sum + width, 0) + gapX * (columns - 1)
  const totalHeight = rowHeights.reduce((sum, height) => sum + height, 0) + gapY * (rows - 1)
  const centerX = (Math.min(...bounds.map(bound => bound.x)) +
    Math.max(...bounds.map(bound => bound.x + bound.w))) / 2
  const centerY = (Math.min(...bounds.map(bound => bound.y)) +
    Math.max(...bounds.map(bound => bound.y + bound.h))) / 2
  const columnCenters = columnWidths.map((width, index) =>
    columnWidths.slice(0, index).reduce((sum, previous) => sum + previous, 0) + gapX * index + width / 2)
  const rowCenters = rowHeights.map((height, index) =>
    rowHeights.slice(0, index).reduce((sum, previous) => sum + previous, 0) + gapY * index + height / 2)
  return items.map((item, index) => {
    const [column, row] = slots[index]!
    return {
      ...item,
      xMm: Math.round((centerX - totalWidth / 2 + columnCenters[column]!) / gridMm) * gridMm,
      yMm: Math.round((centerY - totalHeight / 2 + rowCenters[row]!) / gridMm) * gridMm
    }
  })
}
