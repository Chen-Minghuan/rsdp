// 品类元数据、补充产品类型与产品属性定义的唯一数据源。
// 由 generate_product_attribute_seed.js 生成 baseline/reset/V16 种子，禁止在 SQL 中手工维护副本。

const option = (code, name) => ({ code, name });

const productType = (
  typeCode,
  typeName,
  categoryCode,
  aliases,
  roomTags,
  description,
  parentTypeCode = null,
) => ({
  typeCode,
  typeName,
  businessCategoryCode: categoryCode,
  schemaCategoryCode: categoryCode,
  parentTypeCode,
  aliases,
  roomTags,
  description,
});

const attribute = (
  scopeCode,
  attributeCode,
  attributeName,
  valueLayer,
  valueType,
  unit,
  enumOptions,
  aliases,
  description,
  aiExtractable = false,
  requiredLevel = 'optional',
) => ({
  attributeId: `ATTR-${scopeCode}-${attributeCode}`,
  attributeCode,
  attributeName,
  categoryCode: scopeCode === 'GENERIC' ? null : scopeCode,
  productTypeCode: null,
  valueLayer,
  valueType,
  unit,
  enumOptions,
  aliases,
  description,
  aiExtractable,
  requiredLevel,
});

const LEGACY_CATEGORY_METADATA = [
  {
    code: 'FS',
    aliases: ['椅子', '凳子', '单椅', '餐椅', '靠背椅', '休闲椅', '扶手椅', '长凳', '床尾凳', '脚踏', '坐墩', '矮凳'],
    remark: '供坐卧但不具备典型多人沙发结构的座具；排除吧椅、办公职级椅和床。凳子、单人沙发等歧义词需结合座高、体量和结构复核。',
  },
  {
    code: 'SF',
    aliases: ['沙发', '沙发椅', '单人沙发', '多人沙发', '双人沙发', '三人沙发', '转角沙发', '组合沙发', '模块沙发', '沙发床', '贵妃榻'],
    remark: '以连续软包坐面、多人尺度或沙发式模块为主要特征；单人沙发与扶手椅边界需结合整体体量、连续底座和软包结构判定。',
  },
  {
    code: 'TB',
    aliases: ['茶几', '咖啡桌', '客厅几', '边几', '角几', '套几', '子母几', '沙发边桌', '玄关台'],
    remark: '客厅或休闲区使用的低桌和辅助桌；排除餐桌、书桌、办公桌及以收纳为主的柜类。',
  },
  {
    code: 'FC',
    aliases: ['柜类', '柜子', '收纳柜', '电视柜', '餐边柜', '床头柜', '斗柜', '衣柜', '书柜', '鞋柜', '展示柜'],
    remark: '以封闭或半封闭收纳、陈列为主要功能的柜体；排除只有开放工作台面的桌台类。',
  },
  {
    code: 'BS',
    aliases: ['吧椅', '吧凳', '高脚椅', '高脚凳', '中岛凳', '升降吧椅'],
    remark: '明显高于普通座椅、面向吧台或厨房中岛使用的座具；需结合座高与脚踏结构区别于 FS 普通座椅。',
  },
  {
    code: 'OF',
    aliases: ['办公家具', '办公桌', '办公椅', '班台', '班椅', '工位', '会议桌', '培训桌', '文件柜'],
    remark: '具有明确办公场景、工位系统或职级语义的家具；家庭书房桌优先归入 DK，通用餐桌归入 DT。',
  },
  {
    code: 'DT',
    aliases: ['餐桌', '饭桌', '圆餐桌', '长餐桌', '伸缩餐桌', '宴会桌', '吧台桌', '岛台餐桌'],
    remark: '以用餐高度和多人围坐为主要特征的桌台；排除茶几、书桌、办公会议桌和独立厨房岛柜。',
  },
  {
    code: 'BD',
    aliases: ['床', '床架', '软包床', '平台床', '储物床', '上下床', '高架床', '儿童床'],
    remark: '指床架和完整床体，不包含独立床垫；独立床垫归入 MT，日间坐卧家具需结合是否具有正式睡眠承托结构判断。',
  },
  {
    code: 'LT',
    aliases: ['灯具', '灯饰', '吊灯', '吸顶灯', '壁灯', '落地灯', '台灯', '轨道灯', '筒灯', '射灯'],
    remark: '具有独立照明功能及灯体、光源或安装结构的产品；排除仅发光但主要用途不是照明的装饰物。',
  },
];

const SUPPLEMENTAL_PRODUCT_TYPES = [
  productType('DINING_BENCH', '餐凳', 'FS', ['餐桌长凳', '餐厅长凳'], ['DINING_ROOM'], '与餐桌配套的多人长条座具', 'BENCH'),
  productType('SHOE_BENCH', '换鞋凳', 'FS', ['玄关凳', '鞋柜凳'], ['ENTRYWAY'], '供入户换鞋使用的凳类', 'BENCH'),
  productType('WINDOW_BENCH', '窗边凳', 'FS', ['飘窗凳', '窗台凳'], ['LIVING_ROOM', 'BEDROOM'], '靠窗或飘窗区域使用的长凳', 'BENCH'),
  productType('VANITY_STOOL', '梳妆凳', 'FS', ['化妆凳', '妆凳'], ['BEDROOM'], '与梳妆台配套的低凳', 'STOOL'),

  productType('BATHROOM_VANITY', '浴室柜', 'FC', ['洗手台柜', '卫浴柜'], ['BATHROOM'], '与洗手盆组合的浴室收纳柜'),
  productType('LINEN_CABINET', '布草柜', 'FC', ['毛巾柜', '床品柜'], ['BATHROOM', 'BEDROOM'], '用于收纳毛巾、床品等布草的柜体'),
  productType('KIDS_STORAGE_CABINET', '儿童收纳柜', 'FC', ['玩具柜', '儿童柜'], ['KIDS_ROOM'], '适合儿童高度和安全要求的收纳柜'),

  productType('CRIB', '婴儿床', 'BD', ['宝宝床', '围栏婴儿床'], ['KIDS_ROOM'], '四周具有防护围栏的婴幼儿睡眠床'),
  productType('MURPHY_BED', '壁床/墨菲床', 'BD', ['翻板床', '隐藏床'], ['BEDROOM', 'GUEST_ROOM'], '可向墙柜方向翻起收纳的床'),
  productType('ADJUSTABLE_BED', '电动调节床', 'BD', ['护理床', '升降床'], ['BEDROOM'], '床面角度或高度可电动调节的床'),

  productType('VANITY_LIGHT', '镜前灯', 'LT', ['浴室镜灯', '化妆镜灯'], ['BATHROOM', 'BEDROOM'], '安装于镜面周边或上方的局部照明灯'),
  productType('CABINET_LIGHT', '柜内灯', 'LT', ['橱柜灯', '层板灯'], ['KITCHEN', 'CLOAKROOM'], '安装于柜内或层板下方的辅助灯具'),
  productType('OUTDOOR_LIGHT', '户外灯', 'LT', ['户外壁灯', '防水灯'], ['OUTDOOR', 'BALCONY'], '具备户外耐候用途的照明灯具'),
  productType('GARDEN_LIGHT', '庭院灯', 'LT', ['草坪灯', '景观灯'], ['OUTDOOR'], '用于庭院、步道或草坪的景观照明'),

  productType('DRESSING_TABLE', '梳妆台', 'DK', ['化妆台', '妆台'], ['BEDROOM'], '以梳妆、化妆和小件收纳为主的桌台'),
  productType('DRAFTING_TABLE', '绘图桌', 'DK', ['制图桌', '画图桌'], ['STUDY', 'STUDIO'], '台面角度可调或适合绘图制图的工作桌'),
  productType('GAMING_DESK', '游戏桌', 'DK', ['电竞桌', '电竞电脑桌'], ['STUDY', 'BEDROOM'], '面向电脑游戏设备、走线和外设布置的桌台'),
  productType('KIDS_STUDY_DESK', '儿童学习桌', 'DK', ['学生桌', '成长书桌'], ['KIDS_ROOM', 'STUDY'], '适合儿童身高并常带高度调节的学习桌'),

  productType('MATTRESS_TOPPER', '床垫薄垫/保护层', 'MT', ['床褥', '床垫加垫'], ['BEDROOM'], '铺设于主床垫上方的较薄舒适层'),
  productType('AIR_MATTRESS', '充气床垫', 'MT', ['气垫床', '充气床'], ['GUEST_ROOM', 'OUTDOOR'], '通过充气形成承托结构的便携床垫'),
  productType('KIDS_MATTRESS', '儿童床垫', 'MT', ['婴儿床垫', '青少年床垫'], ['KIDS_ROOM'], '适配婴童或儿童床规格的床垫'),

  productType('TABLE_MIRROR', '台镜', 'MR', ['桌面镜', '折叠台镜'], ['BEDROOM', 'BATHROOM'], '依靠底座放置于桌面的可移动镜子'),
  productType('LIGHTED_MIRROR', '灯光镜', 'MR', ['发光镜', 'LED镜'], ['BATHROOM', 'BEDROOM'], '镜面或边框集成照明的镜子'),

  productType('IRREGULAR_RUG', '异形地毯', 'RG', ['不规则地毯', '造型毯'], ['LIVING_ROOM', 'BEDROOM'], '外轮廓为自由曲线或非规则几何形的区域毯', 'AREA_RUG'),
  productType('CARPET_TILE', '拼块地毯', 'RG', ['方块地毯', '地毯砖'], ['OFFICE', 'COMMERCIAL'], '由标准化小块拼铺的地毯系统'),
  productType('WALL_TO_WALL_CARPET', '满铺地毯', 'RG', ['工程地毯', '整屋地毯'], ['BEDROOM', 'OFFICE'], '按房间边界连续铺设的地毯'),
  productType('HIDE_RUG', '皮毛毯', 'RG', ['牛皮毯', '羊皮毯'], ['LIVING_ROOM', 'BEDROOM'], '保留动物皮张或仿皮毛自然轮廓的地毯'),

  productType('HANGING_DIVIDER', '悬挂隔断', 'PD', ['吊挂隔断', '吊屏'], ['LIVING_ROOM', 'COMMERCIAL'], '由顶部吊挂、底部不落地的空间隔断'),
  productType('GRILLE_PARTITION', '格栅隔断', 'PD', ['木格栅', '竖条隔断'], ['LIVING_ROOM', 'ENTRYWAY'], '由重复条杆构成的半通透隔断'),
  productType('FIXED_SCREEN', '固定屏风', 'PD', ['固定隔断', '落地固定屏'], ['LIVING_ROOM', 'OFFICE'], '固定于地面、墙面或顶面的非活动屏风'),

  productType('VERTICAL_BLIND', '垂直帘', 'CW', ['竖百叶', '垂直百叶'], ['OFFICE', 'LIVING_ROOM'], '由多片竖向叶片并排悬挂的窗饰'),
  productType('ZEBRA_BLIND', '斑马帘/柔纱帘', 'CW', ['柔纱帘', '调光帘'], ['LIVING_ROOM', 'STUDY'], '透纱和遮光条带交替错位调光的卷帘'),
  productType('BAMBOO_BLIND', '竹帘', 'CW', ['竹卷帘', '木织帘'], ['STUDY', 'BALCONY'], '以竹木条片或天然纤维编织的窗饰'),
];

const PRODUCT_ATTRIBUTES = [
  attribute('GENERIC', 'INDOOR_OUTDOOR', '使用环境', 'RSPU', 'enum', null,
    [option('INDOOR', '室内'), option('OUTDOOR', '户外'), option('BOTH', '室内外通用')],
    ['室内外', '户外适用', '使用环境'], '产品设计适用的环境范围，不以单张场景图推断', true, 'recommended'),
  attribute('GENERIC', 'TARGET_USERS', '适用人群', 'RSPU', 'multi_enum', null,
    [option('ADULT', '成人'), option('KIDS', '儿童'), option('ELDERLY', '适老'), option('ACCESSIBLE', '无障碍')],
    ['适用对象', '人群'], '产品设计明确面向的人群，可多选', false),
  attribute('GENERIC', 'INSTALL_METHOD', '安装方式', 'RSPU', 'multi_enum', null,
    [option('FLOOR', '落地'), option('WALL', '壁挂'), option('CEILING', '吊装'), option('RECESSED', '嵌入'), option('HANGING', '悬挂')],
    ['固定方式', '安装类型'], '产品设计要求的主要安装或放置方式', true, 'recommended'),
  attribute('GENERIC', 'MODULARITY', '组合方式', 'RSPU', 'enum', null,
    [option('FIXED', '固定单体'), option('MODULAR', '模块化'), option('SET', '成套组合')],
    ['模块化', '组合形式'], '款式是固定单体、可重组模块还是成套销售组合', true),
  attribute('GENERIC', 'FUNCTION_TAGS', '功能标签', 'RSPU', 'multi_enum', null,
    [option('STORAGE', '储物'), option('FOLDING', '折叠'), option('EXTENDABLE', '伸缩'), option('HEIGHT_ADJUSTABLE', '升降'), option('SWIVEL', '旋转'), option('RECLINING', '躺倒'), option('MOBILE', '移动')],
    ['功能', '特性'], '跨品类的稳定功能集合，只有图片或文字明确时记录', true),
  attribute('GENERIC', 'CARE_METHOD', '清洁维护方式', 'RSPU', 'multi_enum', null,
    [option('WIPE_CLEAN', '擦拭清洁'), option('DRY_CLEAN', '干洗'), option('HAND_WASH', '手洗'), option('MACHINE_WASH', '机洗'), option('PROFESSIONAL', '专业维护')],
    ['洗护', '保养方式'], '商品说明明确给出的清洁维护要求', false),
  attribute('GENERIC', 'ASSEMBLY_MODE', '交付组装方式', 'RSKU', 'enum', null,
    [option('ASSEMBLED', '整装'), option('PARTIAL', '部分组装'), option('KNOCK_DOWN', '拆装包装'), option('PROFESSIONAL', '专业安装')],
    ['安装服务', '组装要求'], '具体供应单元的包装交付和安装要求', false),
  attribute('GENERIC', 'CERTIFICATION_TAGS', '认证标签', 'RSKU', 'multi_enum', null,
    [option('FSC', 'FSC'), option('BSCI', 'BSCI'), option('GREENGUARD', 'GREENGUARD'), option('CE', 'CE'), option('ROHS', 'RoHS'), option('OTHER', '其他')],
    ['资质', '认证'], '工厂或具体供应单元可提供证明材料的认证', false),

  attribute('FS', 'SEAT_COUNT', '座位数', 'RSPU', 'integer', 'seat', [], ['几人位', '座数'], '正常坐姿下的设计座位数量', true, 'recommended'),
  attribute('FS', 'SEAT_HEIGHT_MM', '座高', 'VARIANT', 'decimal', 'mm', [], ['坐高', '座面高'], '地面至主要座面的垂直高度', false, 'recommended'),
  attribute('FS', 'MAX_LOAD_KG', '最大承重', 'VARIANT', 'decimal', 'kg', [], ['承重', '载重'], '有测试或规格依据的最大静态承重', false),
  attribute('FS', 'STACKABLE', '可堆叠', 'RSPU', 'boolean', null, [], ['叠放', '可摞放'], '椅凳是否设计为可稳定堆叠收纳', true),
  attribute('FS', 'SWIVEL', '可旋转', 'RSPU', 'boolean', null, [], ['转椅', '旋转底座'], '座面或底座是否具备旋转机构', true),
  attribute('FS', 'RECLINING', '可躺调节', 'RSPU', 'boolean', null, [], ['躺倒', '靠背调节'], '靠背或脚托是否具备躺倒调节功能', true),

  attribute('SF', 'SEAT_COUNT', '座位数', 'RSPU', 'integer', 'seat', [], ['几人位', '座数'], '沙发正常坐姿下的设计座位数量', true, 'recommended'),
  attribute('SF', 'SEAT_HEIGHT_MM', '座高', 'VARIANT', 'decimal', 'mm', [], ['坐高', '座面高'], '地面至主要座面的垂直高度', false),
  attribute('SF', 'SECTION_COUNT', '模块/分段数', 'VARIANT', 'integer', 'piece', [], ['模块数', '组合件数'], '当前组合变体包含的独立沙发模块或分段数量', true),
  attribute('SF', 'RECLINING', '功能躺位', 'RSPU', 'boolean', null, [], ['电动躺位', '脚托'], '是否具有可展开脚托或靠背躺倒机构', true),

  attribute('TB', 'TABLETOP_HEIGHT_MM', '台面高度', 'VARIANT', 'decimal', 'mm', [], ['几高', '桌面高'], '地面至主要台面的高度', false, 'recommended'),
  attribute('TB', 'NESTING_COUNT', '套几件数', 'VARIANT', 'integer', 'piece', [], ['组合数量', '几件套'], '套几或组合几包含的单体数量', true),
  attribute('TB', 'MOBILE', '可移动', 'RSPU', 'boolean', null, [], ['带轮', '移动茶几'], '是否设计有脚轮或专用移动结构', true),

  attribute('FC', 'DOOR_COUNT', '门扇数', 'VARIANT', 'integer', 'piece', [], ['柜门数', '门数'], '当前变体可开启的柜门数量', true),
  attribute('FC', 'DRAWER_COUNT', '抽屉数', 'VARIANT', 'integer', 'piece', [], ['斗数', '抽数'], '当前变体独立抽屉数量', true),
  attribute('FC', 'SHELF_COUNT', '层板数', 'VARIANT', 'integer', 'piece', [], ['隔板数', '层数'], '当前变体可用层板数量', false),
  attribute('FC', 'MOUNTING_TYPE', '柜体安装类型', 'RSPU', 'enum', null,
    [option('FREESTANDING', '独立落地'), option('WALL_MOUNTED', '壁挂'), option('BUILT_IN', '嵌入/固定')],
    ['柜体固定方式', '落地壁挂'], '柜体主要安装方式', true, 'recommended'),
  attribute('FC', 'TV_SIZE_INCH', '适配电视尺寸', 'VARIANT', 'decimal', 'inch', [], ['电视英寸', '适配屏幕'], '电视柜明确标注可适配的最大电视尺寸', false),

  attribute('BS', 'SEAT_HEIGHT_MM', '座高', 'VARIANT', 'decimal', 'mm', [], ['吧椅高度', '坐高'], '地面至座面的垂直高度，是区分吧椅与普通座椅的关键值', false, 'required'),
  attribute('BS', 'HEIGHT_ADJUSTABLE', '高度可调', 'RSPU', 'boolean', null, [], ['升降', '可调高'], '座面高度是否可调', true),
  attribute('BS', 'HAS_FOOTREST', '带脚踏', 'RSPU', 'boolean', null, [], ['脚踏环', '搁脚'], '是否具有脚踏环或横向搁脚杆', true),
  attribute('BS', 'SWIVEL', '可旋转', 'RSPU', 'boolean', null, [], ['旋转吧椅', '转动'], '座面是否可围绕中柱旋转', true),
  attribute('BS', 'MAX_LOAD_KG', '最大承重', 'VARIANT', 'decimal', 'kg', [], ['承重', '载重'], '有测试或规格依据的最大静态承重', false),

  attribute('OF', 'COMMERCIAL_GRADE', '商用强度等级', 'RSKU', 'enum', null,
    [option('HOME', '家用'), option('LIGHT_COMMERCIAL', '轻商用'), option('COMMERCIAL', '商用'), option('CONTRACT', '工程级')],
    ['结构等级', '商用等级'], '具体供应单元可验证的使用强度等级', false),
  attribute('OF', 'CABLE_MANAGEMENT', '走线管理', 'RSPU', 'multi_enum', null,
    [option('GROMMET', '过线孔'), option('CABLE_TRAY', '走线槽'), option('CABLE_BOX', '线盒'), option('VERTICAL_CHANNEL', '竖向走线')],
    ['线孔', '走线槽'], '桌台可见或规格明确的线缆管理结构', true),
  attribute('OF', 'HEIGHT_ADJUSTABLE', '高度可调', 'RSPU', 'boolean', null, [], ['升降办公桌', '升降椅'], '桌面或座面高度是否可调，需结合 product_type 理解', true),
  attribute('OF', 'WORKSTATION_CAPACITY', '工位人数', 'VARIANT', 'integer', 'seat', [], ['几人位工位', '工位数'], '工位系统当前组合可容纳的人数', false),

  attribute('DT', 'TABLETOP_HEIGHT_MM', '台面高度', 'VARIANT', 'decimal', 'mm', [], ['餐桌高', '桌面高'], '地面至主要用餐台面的高度', false, 'recommended'),
  attribute('DT', 'SEATING_CAPACITY', '建议用餐人数', 'VARIANT', 'integer', 'seat', [], ['几人桌', '容纳人数'], '当前尺寸变体建议同时用餐的人数', false, 'recommended'),
  attribute('DT', 'EXTENDABLE', '可伸缩', 'RSPU', 'boolean', null, [], ['延长桌', '拉伸桌'], '台面是否可通过拉伸、翻板等方式扩展', true),

  attribute('BD', 'MATTRESS_SIZE', '适配床垫规格', 'VARIANT', 'text', null, [], ['床垫尺寸', '床规格'], '床架适配的标准或原始床垫尺寸表达', false, 'required'),
  attribute('BD', 'SLEEPING_CAPACITY', '睡眠人数', 'VARIANT', 'integer', 'person', [], ['单人双人', '容纳人数'], '当前尺寸变体设计容纳的睡眠人数', false),
  attribute('BD', 'UNDERBED_STORAGE', '床下储物', 'RSPU', 'enum', null,
    [option('NONE', '无'), option('DRAWER', '抽屉储物'), option('LIFT_UP', '掀床储物'), option('OPEN', '开放置物')],
    ['高箱床', '床下抽屉'], '床下储物的主要结构形式', true),
  attribute('BD', 'HEADBOARD_HEIGHT_MM', '床头高度', 'VARIANT', 'decimal', 'mm', [], ['靠背高', '床屏高度'], '地面至床头最高点的垂直高度', false),

  attribute('LT', 'LIGHT_SOURCE', '光源类型', 'VARIANT', 'enum', null,
    [option('INTEGRATED_LED', '集成 LED'), option('REPLACEABLE_BULB', '可更换灯泡'), option('LIGHT_STRIP', '灯带'), option('OTHER', '其他')],
    ['灯芯', '光源'], '当前灯具变体使用的主要光源形式', false, 'recommended'),
  attribute('LT', 'BULB_BASE', '灯头接口', 'VARIANT', 'text', null, [], ['灯口', '灯座'], '如 E27、E14、GU10 等标准灯头接口', false),
  attribute('LT', 'POWER_W', '功率', 'VARIANT', 'decimal', 'W', [], ['瓦数', '额定功率'], '当前灯具变体的额定功率', false),
  attribute('LT', 'COLOR_TEMPERATURE_K', '色温', 'VARIANT', 'integer', 'K', [], ['光色', '开尔文'], '当前灯具变体标注的色温', false),
  attribute('LT', 'DIMMABLE', '可调光', 'RSPU', 'boolean', null, [], ['调光', '亮度调节'], '是否支持亮度调节', false),
  attribute('LT', 'VOLTAGE_V', '额定电压', 'VARIANT', 'text', 'V', [], ['电压', '输入电压'], '当前灯具变体适用的电压范围', false),
  attribute('LT', 'IP_RATING', '防护等级', 'RSKU', 'text', null, [], ['防水等级', 'IP等级'], '具体供应单元具备检测或规格依据的 IP 防护等级', false),

  attribute('DK', 'TABLETOP_HEIGHT_MM', '台面高度', 'VARIANT', 'decimal', 'mm', [], ['桌面高', '书桌高'], '地面至主要工作台面的高度', false, 'recommended'),
  attribute('DK', 'CABLE_MANAGEMENT', '走线管理', 'RSPU', 'multi_enum', null,
    [option('GROMMET', '过线孔'), option('CABLE_TRAY', '走线槽'), option('CABLE_BOX', '线盒'), option('VERTICAL_CHANNEL', '竖向走线')],
    ['线孔', '走线槽'], '可见或规格明确的线缆管理结构', true),
  attribute('DK', 'HEIGHT_ADJUSTABLE', '高度可调', 'RSPU', 'boolean', null, [], ['成长桌', '升降桌'], '工作台面高度是否可调', true),
  attribute('DK', 'TILT_ADJUSTABLE', '台面角度可调', 'RSPU', 'boolean', null, [], ['斜面调节', '绘图桌角度'], '台面是否可改变倾斜角度', true),

  attribute('MT', 'THICKNESS_MM', '床垫厚度', 'VARIANT', 'decimal', 'mm', [], ['垫厚', '总厚度'], '床垫当前变体的整体厚度', false, 'required'),
  attribute('MT', 'FIRMNESS', '软硬度', 'VARIANT', 'enum', null,
    [option('SOFT', '偏软'), option('MEDIUM_SOFT', '中软'), option('MEDIUM', '适中'), option('MEDIUM_FIRM', '中硬'), option('FIRM', '偏硬')],
    ['硬度', '睡感'], '以工厂或品牌规格为依据的软硬度，不从外观猜测', false, 'recommended'),
  attribute('MT', 'CORE_STRUCTURE', '内芯结构', 'VARIANT', 'multi_enum', null,
    [option('SPRING', '弹簧'), option('FOAM', '泡棉'), option('LATEX', '乳胶'), option('COIR', '椰棕'), option('AIR', '充气')],
    ['内材', '承托层'], '仅依据剖面图或明确文字记录的内部承托结构', false),
  attribute('MT', 'REVERSIBLE', '双面可用', 'RSPU', 'boolean', null, [], ['双面睡感', '正反两用'], '床垫是否明确支持翻面使用', false),
  attribute('MT', 'REMOVABLE_COVER', '外套可拆洗', 'RSPU', 'boolean', null, [], ['可拆套', '拉链外套'], '外层套是否可拆卸清洗', true),

  attribute('MR', 'MOUNTING_TYPE', '镜子安装方式', 'RSPU', 'enum', null,
    [option('WALL', '壁挂'), option('FLOOR', '落地'), option('TABLE', '台面'), option('RECESSED', '嵌入')],
    ['支撑方式', '挂装方式'], '镜子的主要安装或支撑方式', true, 'recommended'),
  attribute('MR', 'LIGHTING', '集成灯光', 'RSPU', 'boolean', null, [], ['LED镜', '发光镜'], '镜体是否集成照明', true),
  attribute('MR', 'ANTI_FOG', '防雾', 'RSPU', 'boolean', null, [], ['除雾', '电加热防雾'], '是否具有明确的防雾或除雾功能', false),
  attribute('MR', 'MAGNIFICATION', '放大倍率', 'VARIANT', 'decimal', 'x', [], ['放大镜倍率', '倍数'], '化妆镜等产品明确标注的放大倍率', false),

  attribute('RG', 'PILE_HEIGHT_MM', '绒高', 'VARIANT', 'decimal', 'mm', [], ['毛高', '绒毛高度'], '地毯表面绒毛的标称高度', false),
  attribute('RG', 'WEAVE_METHOD', '织造方式', 'RSPU', 'enum', null,
    [option('HAND_KNOTTED', '手工打结'), option('HAND_TUFTED', '手工簇绒'), option('MACHINE_WOVEN', '机织'), option('FLAT_WOVEN', '平织'), option('BRAIDED', '编织')],
    ['工艺', '织法'], '仅依据工艺图或明确文字记录的织造方式', false),
  attribute('RG', 'BACKING_TYPE', '背衬类型', 'VARIANT', 'text', null, [], ['毯底', '背胶'], '地毯背面的基布、防滑层或背胶描述', false),
  attribute('RG', 'MACHINE_WASHABLE', '可机洗', 'RSPU', 'boolean', null, [], ['洗衣机可洗', '机洗毯'], '商品说明是否明确允许机洗', false),
  attribute('RG', 'AREA_SQM', '面积', 'VARIANT', 'decimal', 'sqm', [], ['平方米', '覆盖面积'], '当前地毯变体的标称覆盖面积', false),
  attribute('RG', 'CUSTOM_SIZE', '支持定制尺寸', 'RSKU', 'boolean', null, [], ['尺寸定制', '可裁切'], '具体工厂供应是否接受非标准尺寸定制', false),

  attribute('PD', 'PANEL_COUNT', '面板数量', 'VARIANT', 'integer', 'piece', [], ['几联', '片数'], '当前隔断变体包含的独立面板数量', true),
  attribute('PD', 'FOLDABLE', '可折叠', 'RSPU', 'boolean', null, [], ['折屏', '收折'], '面板是否通过铰接结构折叠收拢', true),
  attribute('PD', 'MOBILE', '可移动', 'RSPU', 'boolean', null, [], ['带轮隔断', '移动屏风'], '是否设计有脚轮或其他移动结构', true),
  attribute('PD', 'ACOUSTIC_RATING', '声学指标', 'RSKU', 'text', null, [], ['吸音系数', '降噪等级'], '具体供应单元有检测或规格依据的声学性能指标', false),

  attribute('CW', 'FINISHED_WIDTH_MM', '成品宽度', 'VARIANT', 'decimal', 'mm', [], ['帘宽', '成品宽'], '当前窗饰成品展开后的标称宽度', false, 'required'),
  attribute('CW', 'FINISHED_DROP_MM', '成品高度/帘长', 'VARIANT', 'decimal', 'mm', [], ['帘高', '帘长', '下垂高度'], '当前窗饰成品的标称垂直尺寸', false, 'required'),
  attribute('CW', 'OPENING_MODE', '开合方式', 'RSPU', 'enum', null,
    [option('LEFT_RIGHT', '左右开合'), option('UP_DOWN', '上下收放'), option('ROTATE', '叶片旋转'), option('FIXED', '固定')],
    ['开启方式', '收帘方式'], '窗饰主体的主要开合运动方式', true),
  attribute('CW', 'BLACKOUT_LEVEL', '遮光等级', 'VARIANT', 'enum', null,
    [option('SHEER', '透光'), option('LIGHT_FILTERING', '半遮光'), option('HIGH_BLACKOUT', '高遮光'), option('BLACKOUT', '全遮光')],
    ['遮光率', '透光性'], '以检测或商品明确说明为依据的遮光等级', false, 'recommended'),
  attribute('CW', 'MOTOR_CONTROL', '电机控制方式', 'RSKU', 'multi_enum', null,
    [option('REMOTE', '遥控器'), option('APP', 'App'), option('VOICE', '语音'), option('WALL_SWITCH', '墙面开关'), option('SMART_HOME', '智能家居接入')],
    ['智能控制', '电动窗帘控制'], '具体供应单元支持的电机控制与智能接入方式', false),
];

module.exports = {
  LEGACY_CATEGORY_METADATA,
  SUPPLEMENTAL_PRODUCT_TYPES,
  PRODUCT_ATTRIBUTES,
};
