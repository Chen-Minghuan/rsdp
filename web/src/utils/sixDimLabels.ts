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
  DT: {
    categoryName: '餐桌',
    dims: {
      A: { label: '造型', description: '餐桌整体俯视轮廓，如长桌、圆桌、方桌、跑道形、岛台一体桌' },
      B: { label: '台面形态', description: '台面厚度与构造，如平板薄面、厚台面、悬浮台面、转盘台面' },
      C: { label: '边缘/结构', description: '台面边缘工艺与附属结构，如直边、马肚边、瀑布边、裙边/立水' },
      D: { label: '桌腿/底座', description: '支撑形态，如四直腿、外八腿、喇叭/郁金香底座、落地箱式' },
      E: { label: '表面材质', description: '岩板、实木、玻璃、大理石等台面/框架材质' },
      F: { label: '功能/展开方式', description: '固定式、伸缩、折叠、旋转展开、升降、储物等' }
    }
  },
  BD: {
    categoryName: '床',
    dims: {
      A: { label: '整体造型', description: '床体整体形态，如齐边床、内嵌床、地台床、箱体床、上下床' },
      B: { label: '床头', description: '床头/床屏形态（识别置信度最高维度），如软包大靠包、拉扣床头、平板薄床头、拱形床头' },
      C: { label: '床尾/床边', description: '床尾屏板与床沿形态，如齐边无床尾、高床尾屏、宽边床沿' },
      D: { label: '床脚/底座', description: '支脚与底座形态，如实木脚、金属脚、落地无脚、悬浮底座' },
      E: { label: '表面材质', description: '真皮、布艺、实木、绒布等床体表面材质' },
      F: { label: '储物/功能', description: '气压上掀储物、床体抽屉、床头功能区、床底灯带等' }
    }
  },
  LT: {
    categoryName: '灯具',
    dims: {
      A: { label: '灯体造型', description: '灯体整体剪影，如球形、长条形、枝形、分子式' },
      B: { label: '灯罩/出光', description: '灯罩形态与出光方式，如玻璃罩、布艺罩、无罩裸光源' },
      C: { label: '灯臂/连接', description: '灯体与安装面的连接结构，如吊线吊杆、弧形悬臂、折叠摇臂' },
      D: { label: '安装/底座', description: '安装方式与底座形态，如吊挂式、吸顶式、落地立式、壁挂式' },
      E: { label: '表面材质', description: '金属、玻璃、亚克力、藤竹、布艺等灯体材质' },
      F: { label: '装饰元素', description: '附加装饰构件，如水晶挂饰、流苏穗边、彩色玻璃' }
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
