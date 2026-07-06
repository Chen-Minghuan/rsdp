/**
 * 六维标签按产品类别的维度名称映射。
 *
 * 后端 VisionService 已按品类输出对应维度的 AI 识别结果，
 * 前端展示时使用本映射渲染为可读标签。
 */

export interface SixDimDefinition {
  label: string
  description?: string
}

export interface SixDimSchema {
  categoryName: string
  dims: Record<string, SixDimDefinition>
}

const schemas: Record<string, SixDimSchema> = {
  FS: {
    categoryName: '座椅/沙发',
    dims: {
      A: { label: '轮廓形态', description: '整体造型，如弧形、方盒形、蛋形、模块化组合' },
      B: { label: '靠背/背部特征', description: '靠背高度、包裹性、编织镂空等' },
      C: { label: '扶手特征', description: '扶手形态，如无扶手、环形扶手、实木扶手' },
      D: { label: '腿部/底座特征', description: '腿部形态，如细腿、落地底座、金属框架' },
      E: { label: '表面材质', description: '实木、皮革、布艺、金属等表面材质' },
      F: { label: '软包填充形态', description: '软包饱满度、绗缝、拉扣等填充形态' }
    }
  },
  SF: {
    categoryName: '沙发',
    dims: {
      A: { label: '轮廓形态', description: '整体造型，如L型、弧形、一字型、模块化组合' },
      B: { label: '靠背/背部特征', description: '靠背高度、倾斜角度、包裹性' },
      C: { label: '扶手特征', description: '扶手形态，如无扶手、低扶手、宽厚扶手' },
      D: { label: '腿部/底座特征', description: '落地式、细腿、金属脚、悬浮底座' },
      E: { label: '表面材质', description: '皮革、布艺、羊羔绒、天鹅绒等' },
      F: { label: '软包填充形态', description: '坐垫/靠背填充饱满度、绗缝、拉扣' }
    }
  },
  TB: {
    categoryName: '茶几',
    dims: {
      A: { label: '整体造型/轮廓', description: '茶几整体形态，如圆形、方形、异形、组合式' },
      B: { label: '台面形态', description: '台面形状、厚度、悬浮/内嵌设计' },
      C: { label: '台面边缘/连接部', description: '台面边缘处理、与支撑结构的连接方式' },
      D: { label: '桌腿/底座', description: '桌腿形态，如细腿、敦实柱腿、金属框架、悬浮底座' },
      E: { label: '表面材质', description: '大理石、玻璃、实木、金属等台面/框架材质' },
      F: { label: '收纳/功能件', description: '抽屉、层板、旋转功能件等附加功能' }
    }
  },
  FC: {
    categoryName: '柜类',
    dims: {
      A: { label: '整体造型/轮廓', description: '柜体整体形态，如高柜、矮柜、组合柜、悬浮柜' },
      B: { label: '门板/抽屉特征', description: '门板分割方式、抽屉排列、开放格/封闭格比例' },
      C: { label: '拉手/五金特征', description: '拉手形态，如无拉手、明装拉手、隐藏拉手、金属拉手' },
      D: { label: '底座/支脚', description: '落地式、高脚、金属支脚、悬浮挂墙' },
      E: { label: '表面材质', description: '实木、板材、岩板、藤编、烤漆等表面材质' },
      F: { label: '内部结构/功能分区', description: '内部隔层、抽屉、灯带、视听设备位等功能分区' }
    }
  },
  BS: {
    categoryName: '吧椅',
    dims: {
      A: { label: '座面轮廓', description: '座面形状，如圆形、方形、马蹄形' },
      B: { label: '靠背/背部特征', description: '靠背高度、包裹性，无靠背/低靠背/高靠背' },
      C: { label: '扶手特征', description: '扶手形态，如无扶手、小扶手、环形扶手' },
      D: { label: '底座/升降杆', description: '固定底座、三脚/四脚底座、气压升降杆' },
      E: { label: '表面材质', description: '皮革、金属、实木、塑料等' },
      F: { label: '软包填充形态', description: '座面/靠背软包形态、厚度、绗缝' }
    }
  },
  OF: {
    categoryName: '办公家具',
    dims: {
      A: { label: '整体造型/轮廓', description: '家具整体形态，如班台、职员桌、会议桌、文件柜' },
      B: { label: '工作面/背部特征', description: '台面/工作面形态，或柜类背板/门板特征' },
      C: { label: '侧部/连接部', description: '侧板、挡板、线槽、扶手/侧翼结构' },
      D: { label: '支撑/底座', description: '桌腿、桌架、柜脚、人体工学底盘' },
      E: { label: '表面材质', description: '实木皮、板材、金属、网布、皮革等' },
      F: { label: '功能件/软包', description: '抽屉、线槽、升降机构、坐垫软包等功能件' }
    }
  },
  GENERIC: {
    categoryName: '通用',
    dims: {
      A: { label: '整体造型/轮廓', description: '产品整体外观形态' },
      B: { label: '上部/背部特征', description: '座椅靠背、柜类背板/门板、桌类台面' },
      C: { label: '侧部/连接部', description: '扶手、侧板、台面边缘、连接结构' },
      D: { label: '支撑/底座', description: '腿部、底座、支脚、底盘' },
      E: { label: '表面材质', description: '主要表面材质与纹理' },
      F: { label: '功能/填充件', description: '软包填充、抽屉、层板等功能件' }
    }
  }
}

export function getSixDimSchema(categoryCode?: string): SixDimSchema {
  if (!categoryCode) return schemas.GENERIC
  return schemas[categoryCode.toUpperCase()] ?? schemas.GENERIC
}

export function getSixDimLabel(categoryCode: string | undefined, dimKey: string): string {
  const schema = getSixDimSchema(categoryCode)
  return schema.dims[dimKey]?.label ?? `维度 ${dimKey}`
}
