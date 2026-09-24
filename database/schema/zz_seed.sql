-- RSDP 字典表种子数据（PostgreSQL 版本）
-- 风格数据库的案例/元素/公式种子数据见 database/seed_style_knowledge.sql
-- 后续可根据实际业务扩展

-- 产品类别
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'FS', '座椅', 1),
('category', 'SF', '沙发', 2),
('category', 'TB', '茶几', 3),
('category', 'FC', '柜类', 4),
('category', 'BS', '吧椅', 5),
('category', 'OF', '办公家具', 6),
('category', 'DT', '餐桌', 7),
('category', 'BD', '床', 8),
('category', 'LT', '灯具', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 品类常用叫法别名（营销 Agent 品类归一：椅子/沙发椅等口语词 → 字典码）
UPDATE category_dict SET aliases = '["椅子","凳子","餐椅","靠背椅","休闲椅"]' WHERE dict_type='category' AND dict_code='FS';
UPDATE category_dict SET aliases = '["吧凳","高脚椅"]' WHERE dict_type='category' AND dict_code='BS';
UPDATE category_dict SET aliases = '["沙发椅","单人沙发"]' WHERE dict_type='category' AND dict_code='SF';

-- 家装风格（扩展为 11 个独立风格，保留 2 位编码）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'MC', '中古风', 1),
('style', 'BA', '包豪斯', 2),
('style', 'IT', '意式', 3),
('style', 'FR', '法式', 4),
('style', 'WJ', '侘寂', 5),
('style', 'NC', '新中式', 6),
('style', 'CR', '奶油风', 7),
('style', 'IN', '工业风', 8),
('style', 'MP', '孟菲斯', 9),
('style', 'IL', '意式极简轻奢', 10),
('style', 'ZS', '新中式宋式', 11),
('style', 'MB', '现代极简包豪斯', 12),
('style', 'MD', '孟菲斯多巴胺', 13),
('style', 'IO', '工业风LOFT', 14),
('style', 'FN', '法式复古南洋', 15),
('style', 'HH', '混搭风', 16),
('style', 'DL', '国外顶尖大牌搭配', 17)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 办公家具职级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('grade', 'EX', '总裁级', 1),
('grade', 'MG', '经理级', 2),
('grade', 'ST', '职员级', 3),
('grade', 'PU', '公共区', 4),
('grade', 'CO', '会议区', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 材质版本码（精简为 19 个准确大类，覆盖风格百科高频材质）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material', 'TN', '真藤/竹编/草编', 1),
('material', 'PE', 'PE仿藤', 2),
('material', 'LE', '皮革', 3),
('material', 'NP', '纳帕皮', 4),
('material', 'SU', '磨砂皮', 5),
('material', 'MA', '马鞍皮', 6),
('material', 'LI', '亚麻/棉麻', 7),
('material', 'WL', '羊毛', 8),
('material', 'SF', '羊羔绒/泰迪绒', 9),
('material', 'VE', '天鹅绒/绒布', 10),
('material', 'WO', '实木', 11),
('material', 'RK', '藤编+实木混血', 12),
('material', 'MT', '金属/不锈钢/黄铜', 13),
('material', 'GL', '玻璃', 14),
('material', 'ST', '天然石材/大理石/洞石/岩板', 15),
('material', 'CE', '水泥/混凝土/微水泥', 16),
('material', 'CL', '陶瓷', 17),
('material', 'GP', '石膏/PU线条', 18),
('material', 'PL', '塑料/亚克力', 19)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 面料类型字典（V22，软体类商品专用；与 material_grade 面料等级区分）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('fabric', 'LI', '亚麻/棉麻', 1),
('fabric', 'XN', '雪尼尔', 2),
('fabric', 'KJ', '科技布', 3),
('fabric', 'VE', '天鹅绒/绒布', 4),
('fabric', 'SF', '羊羔绒/泰迪绒', 5),
('fabric', 'DX', '灯芯绒', 6),
('fabric', 'ZP', '真皮', 7),
('fabric', 'NP', '纳帕皮', 8),
('fabric', 'MS', '磨砂皮', 9),
('fabric', 'CX', '超纤皮', 10),
('fabric', 'PU', 'PU/PVC革', 11),
('fabric', 'WB', '网布', 12)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 常用材质/面料别名（V22：AI 识别叫法 → 字典标准项）
UPDATE category_dict SET aliases = '["橡木","胡桃木","松木","原木","木头","木质"]' WHERE dict_type='material' AND dict_code='WO';
UPDATE category_dict SET aliases = '["真皮","牛皮","头层牛皮","黄牛皮","皮质"]' WHERE dict_type='material' AND dict_code='LE';
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='material' AND dict_code='LI';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='material' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='material' AND dict_code='SF';
UPDATE category_dict SET aliases = '["不锈钢","黄铜","铁艺","铝合金","金属框架"]' WHERE dict_type='material' AND dict_code='MT';
UPDATE category_dict SET aliases = '["大理石","岩板","洞石","石材"]' WHERE dict_type='material' AND dict_code='ST';
UPDATE category_dict SET aliases = '["仿藤","PE藤","塑料藤"]' WHERE dict_type='material' AND dict_code='PE';
UPDATE category_dict SET aliases = '["藤编","竹编","草编","真藤"]' WHERE dict_type='material' AND dict_code='TN';
UPDATE category_dict SET aliases = '["亚克力","有机玻璃"]' WHERE dict_type='material' AND dict_code='PL';
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='fabric' AND dict_code='LI';
UPDATE category_dict SET aliases = '["雪尼尔绒","雪尼尔布"]' WHERE dict_type='fabric' AND dict_code='XN';
UPDATE category_dict SET aliases = '["科技绒","科技皮革","三防布"]' WHERE dict_type='fabric' AND dict_code='KJ';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='fabric' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='fabric' AND dict_code='SF';
UPDATE category_dict SET aliases = '["灯芯绒布","条绒"]' WHERE dict_type='fabric' AND dict_code='DX';
UPDATE category_dict SET aliases = '["牛皮","头层牛皮","黄牛皮","全皮"]' WHERE dict_type='fabric' AND dict_code='ZP';
UPDATE category_dict SET aliases = '["纳帕真皮","Napa皮","NAPPA皮"]' WHERE dict_type='fabric' AND dict_code='NP';
UPDATE category_dict SET aliases = '["反绒皮","麂皮"]' WHERE dict_type='fabric' AND dict_code='MS';
UPDATE category_dict SET aliases = '["超纤","超纤革"]' WHERE dict_type='fabric' AND dict_code='CX';
UPDATE category_dict SET aliases = '["PU皮","PVC革","人造革","仿皮","西皮"]' WHERE dict_type='fabric' AND dict_code='PU';
UPDATE category_dict SET aliases = '["网眼布","透气网布"]' WHERE dict_type='fabric' AND dict_code='WB';

-- 六维标签字典种子（V29：dict_code = {品类码}-{中文名}，aliases/remark 内联；E 维度不建枚举，统一引用 material/fabric 字典）
-- >>> SIX_DIM_DICT_SEED (generated by scripts/generate_six_dim_dict_seed.js, do not edit manually) >>>
-- 六维 A 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'FS-一字型', '一字型', 'FS', 1, '["直排","直列"]', '正视呈一条直线，无转角无延伸位'),
('six_dim_A', 'FS-L型/转角组合', 'L型/转角组合', 'FS', 2, '["转角沙发","贵妃位","贵妃榻","L形组合"]', '座位向一侧拐出或一端延伸出无靠背长躺位'),
('six_dim_A', 'FS-方盒形', '方盒形', 'FS', 3, '["豆腐块","方块沙发","盒子形"]', '外轮廓方正利落、棱角分明、厚度均匀的体块感'),
('six_dim_A', 'FS-弧形/环抱形', '弧形/环抱形', 'FS', 4, '["圆弧形","月牙形","腰果形","帆船沙发"]', '整体呈弧线或两侧向内环抱的曲线轮廓'),
('six_dim_A', 'FS-低矮阔坐形', '低矮阔坐形', 'FS', 5, '["云朵沙发","低趴沙发","地平线沙发"]', '整体明显低矮、坐面宽大松软、重心贴地'),
('six_dim_A', 'FS-蛋形/球形', '蛋形/球形', 'FS', 6, '["蛋椅","蛋壳椅","球椅","太空舱椅"]', '座椅主体呈蛋/球形壳体，人被包裹在壳内'),
('six_dim_A', 'FS-贝壳/花瓣形', '贝壳/花瓣形', 'FS', 7, '["贝壳椅","扇贝椅","花瓣椅","微笑椅"]', '座面靠背呈展开的扇形/瓣状壳体，边缘上翘'),
('six_dim_A', 'FS-分节柱形', '分节柱形', 'FS', 8, '["毛毛虫沙发","褶皱沙发","Togo式"]', '无外露硬骨架，由横向分节/褶皱段堆叠成低矮长条'),
('six_dim_A', 'FS-外露骨架形', '外露骨架形', 'FS', 9, '["A字架形","骨架外露","框架式"]', '木/金属骨架构成整椅主体轮廓（含靠背扶手），软包嵌挂其间'),
('six_dim_A', 'FS-沙漏形/收腰', '沙漏形/收腰', 'FS', 10, '["收腰","细腰"]', '腰线明显内收，上下两端外张'),
('six_dim_A', 'FS-模块化组合', '模块化组合', 'FS', 11, '["模块沙发","自由组合","积木沙发"]', '可见多个独立相同模块拼接，模块间接缝清晰'),
('six_dim_A', 'FS-异形/其他', '异形/其他', 'FS', 12, '["不规则造型","艺术造型"]', '不属于以上任何一种的雕塑感/不规则轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'FS-中高靠背', '中高靠背', 'FS', 1, '["标准靠背","中靠背"]', '靠背顶约在坐姿肩部附近，最常见'),
('six_dim_B', 'FS-低靠背', '低靠背', 'FS', 2, '["矮靠背","半靠背"]', '靠背明显低于肩部，仅及腰背部'),
('six_dim_B', 'FS-高靠背', '高靠背', 'FS', 3, '["高背","带头枕靠背"]', '靠背高过头部，可支撑头颈'),
('six_dim_B', 'FS-翼形包裹靠背', '翼形包裹靠背', 'FS', 4, '["翼背","耳朵靠背","包围式靠背","大象耳朵"]', '靠背两侧向前伸出侧翼，环抱头部'),
('six_dim_B', 'FS-分段靠包', '分段靠包', 'FS', 5, '["多靠枕","分离靠包","面包块靠背","活动靠包"]', '靠背由多个独立可移动靠包排列组成'),
('six_dim_B', 'FS-板面壳体靠背', '板面壳体靠背', 'FS', 6, '["曲木靠背","一体板背","贝壳靠背","塑料壳背"]', '靠背为一整片无分缝的弯曲板/壳体'),
('six_dim_B', 'FS-条形/梳背靠背', '条形/梳背靠背', 'FS', 7, '["竖条靠背","格栅靠背","温莎式","竖棂"]', '靠背由多根竖向细条/梳齿状杆件构成，有间隙'),
('six_dim_B', 'FS-Y形/叉骨靠背', 'Y形/叉骨靠背', 'FS', 8, '["叉骨椅","Y椅","Wishbone"]', '靠背中央为单根 Y 形（叉骨形）支撑构件'),
('six_dim_B', 'FS-编织镂空靠背', '编织镂空靠背', 'FS', 9, '["藤编靠背","绳编靠背","网面靠背"]', '靠背为编织/网状面，可见透空缝隙'),
('six_dim_B', 'FS-无靠背', '无靠背', 'FS', 10, '["无背","榻式"]', '坐面之后无任何靠背构件'),
('six_dim_B', 'FS-可调节/翻折靠背', '可调节/翻折靠背', 'FS', 11, '["可调靠背","活动头枕","档位靠背","可躺靠背"]', '可见铰链/档位结构，或靠背处于明显倾躺状态'),
('six_dim_B', 'FS-异形/其他', '异形/其他', 'FS', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × FS 座椅/沙发（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'FS-无扶手', '无扶手', 'FS', 1, '["光杆","无臂"]', '座面两侧无任何扶手构件'),
('six_dim_C', 'FS-标准方扶手', '标准方扶手', 'FS', 2, '["直扶手","方扶手","常规扶手"]', '高度接近靠背、截面方正的常规软包扶手'),
('six_dim_C', 'FS-宽厚扶手', '宽厚扶手', 'FS', 3, '["宽扶手","厚扶手","面包扶手","大软垫扶手"]', '扶手又宽又厚，顶面可置物/坐人'),
('six_dim_C', 'FS-低扶手', '低扶手', 'FS', 4, '["矮扶手","低平扶手"]', '扶手仅略高于座面，明显低于标准高度'),
('six_dim_C', 'FS-卷边外翻扶手', '卷边外翻扶手', 'FS', 5, '["卷扶手","外翻扶手","美式卷臂"]', '扶手顶端向外/向前翻卷成圆卷'),
('six_dim_C', 'FS-斜面/梯形扶手', '斜面/梯形扶手', 'FS', 6, '["斜扶手","楔形扶手"]', '扶手呈内斜面或上窄下宽的楔形'),
('six_dim_C', 'FS-环形/圈形扶手', '环形/圈形扶手', 'FS', 7, '["圈扶手","圆扶手","环抱扶手"]', '扶手与靠背上沿连成连续圆环/半环'),
('six_dim_C', 'FS-细杆金属扶手', '细杆金属扶手', 'FS', 8, '["铁艺扶手","金属细扶手","线条扶手"]', '扶手由细圆管/扁钢线框构成'),
('six_dim_C', 'FS-木面扶手', '木面扶手', 'FS', 9, '["实木扶手","外露木扶手","木框扶手"]', '扶手可见完整木质面（按可见外观判定，不论真实材质）'),
('six_dim_C', 'FS-一体成型扶手', '一体成型扶手', 'FS', 10, '["一体扶手","壳体扶手"]', '扶手与座面/靠背为同一连续壳体，无分缝'),
('six_dim_C', 'FS-异形/其他', '异形/其他', 'FS', 11, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'FS-落地底座', '落地底座', 'FS', 1, '["落地式","无可见腿","贴地"]', '座体底边直接落地，看不到任何腿'),
('six_dim_D', 'FS-实木腿', '实木腿', 'FS', 2, '["木腿","实木脚","锥形木腿"]', '可见四条（或更多）木质腿，常为锥形/斜撑'),
('six_dim_D', 'FS-细金属腿', '细金属腿', 'FS', 3, '["金属细腿","铁艺细腿","细高脚"]', '纤细金属直腿/锥腿，离地间隙大'),
('six_dim_D', 'FS-敦实柱腿', '敦实柱腿', 'FS', 4, '["粗腿","方块脚","矮粗脚"]', '粗短块状或柱状脚，视觉厚重'),
('six_dim_D', 'FS-金属框架底座', '金属框架底座', 'FS', 5, '["铁架底座","外露框架","金属骨架"]', '座面以下由连续钢管/扁钢框架承托，框架外露'),
('six_dim_D', 'FS-雪橇底座', '雪橇底座', 'FS', 6, '["雪橇脚","滑橇底座","弓形架"]', '金属管沿地面形成前后连续滑橇状底架'),
('six_dim_D', 'FS-悬臂底座', '悬臂底座', 'FS', 7, '["悬臂椅","C形架","无后腿"]', '仅前部/单侧支撑，座面后端悬空无后腿'),
('six_dim_D', 'FS-中央柱底座', '中央柱底座', 'FS', 8, '["五星脚","圆盘底座","转椅底座","喇叭底座","郁金香底座"]', '单根中柱下接五星爪/圆盘/喇叭形底座，常可旋转'),
('six_dim_D', 'FS-摇椅底座', '摇椅底座', 'FS', 9, '["摇腿","弧形摇脚","摇摇椅"]', '底部为两根弧形摇杆，可前后摇晃'),
('six_dim_D', 'FS-悬浮底座', '悬浮底座', 'FS', 10, '["悬浮式","内收底座","悬空底座"]', '底座内收/隐藏，正视座体似漂浮（底部有阴影间隙）'),
('six_dim_D', 'FS-滚轮脚', '滚轮脚', 'FS', 11, '["带轮","脚轮","万向轮"]', '腿部末端可见滚轮'),
('six_dim_D', 'FS-异形/其他', '异形/其他', 'FS', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × FS 座椅/沙发（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'FS-光面软包', '光面软包', 'FS', 1, '["素面","平整面","无明显工艺"]', '表面平整绷紧，无装饰性缝迹（最常见的默认项）'),
('six_dim_F', 'FS-饱满蓬松软包', '饱满蓬松软包', 'FS', 2, '["饱满填充","羽绒感","云朵感","厚软包"]', '垫体鼓起外溢、边缘松垮下垂、褶皱自然'),
('six_dim_F', 'FS-薄垫紧凑软包', '薄垫紧凑软包', 'FS', 3, '["薄垫","薄软包","贴合坐垫"]', '垫体薄且紧贴框架，轮廓利落'),
('six_dim_F', 'FS-绗缝', '绗缝', 'FS', 4, '["车格子","间棉","菱形格缝线"]', '表面有规则缝线格纹/条纹'),
('six_dim_F', 'FS-拉扣', '拉扣', 'FS', 5, '["纽扣软包","拉点","tufted"]', '表面有规律分布的内凹扣点/纽扣'),
('six_dim_F', 'FS-褶皱/抽褶', '褶皱/抽褶', 'FS', 6, '["抓褶","毛毛虫褶皱","横向褶皱"]', '表面呈规律横向/放射状挤压褶皱'),
('six_dim_F', 'FS-分块面包块', '分块面包块', 'FS', 7, '["面包块","豆腐块分格","块状软包"]', '座面/靠背分成若干饱满凸起的方块或长条块'),
('six_dim_F', 'FS-局部软包', '局部软包', 'FS', 8, '["半软包","仅坐垫软包","座面垫"]', '仅座面（或仅靠背）有软包，其余为裸露框架/硬面'),
('six_dim_F', 'FS-无软包', '无软包', 'FS', 9, '["硬座","裸面","板面"]', '坐靠处为木/塑/金属硬面，无任何软垫'),
('six_dim_F', 'FS-异形/其他', '异形/其他', 'FS', 10, NULL, '不属于以上工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'SF-一字型', '一字型', 'SF', 1, '["直排","直列"]', '正视呈一条直线，无转角无延伸位'),
('six_dim_A', 'SF-L型/转角组合', 'L型/转角组合', 'SF', 2, '["转角沙发","贵妃位","贵妃榻","L形组合"]', '座位向一侧拐出或一端延伸出无靠背长躺位'),
('six_dim_A', 'SF-方盒形', '方盒形', 'SF', 3, '["豆腐块","方块沙发","盒子形"]', '外轮廓方正利落、棱角分明、厚度均匀的体块感'),
('six_dim_A', 'SF-弧形/环抱形', '弧形/环抱形', 'SF', 4, '["圆弧形","月牙形","腰果形","帆船沙发"]', '整体呈弧线或两侧向内环抱的曲线轮廓'),
('six_dim_A', 'SF-低矮阔坐形', '低矮阔坐形', 'SF', 5, '["云朵沙发","低趴沙发","地平线沙发"]', '整体明显低矮、坐面宽大松软、重心贴地'),
('six_dim_A', 'SF-蛋形/球形', '蛋形/球形', 'SF', 6, '["蛋椅","蛋壳椅","球椅","太空舱椅"]', '座椅主体呈蛋/球形壳体，人被包裹在壳内'),
('six_dim_A', 'SF-贝壳/花瓣形', '贝壳/花瓣形', 'SF', 7, '["贝壳椅","扇贝椅","花瓣椅","微笑椅"]', '座面靠背呈展开的扇形/瓣状壳体，边缘上翘'),
('six_dim_A', 'SF-分节柱形', '分节柱形', 'SF', 8, '["毛毛虫沙发","褶皱沙发","Togo式"]', '无外露硬骨架，由横向分节/褶皱段堆叠成低矮长条'),
('six_dim_A', 'SF-外露骨架形', '外露骨架形', 'SF', 9, '["A字架形","骨架外露","框架式"]', '木/金属骨架构成整椅主体轮廓（含靠背扶手），软包嵌挂其间'),
('six_dim_A', 'SF-沙漏形/收腰', '沙漏形/收腰', 'SF', 10, '["收腰","细腰"]', '腰线明显内收，上下两端外张'),
('six_dim_A', 'SF-模块化组合', '模块化组合', 'SF', 11, '["模块沙发","自由组合","积木沙发"]', '可见多个独立相同模块拼接，模块间接缝清晰'),
('six_dim_A', 'SF-异形/其他', '异形/其他', 'SF', 12, '["不规则造型","艺术造型"]', '不属于以上任何一种的雕塑感/不规则轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'SF-中高靠背', '中高靠背', 'SF', 1, '["标准靠背","中靠背"]', '靠背顶约在坐姿肩部附近，最常见'),
('six_dim_B', 'SF-低靠背', '低靠背', 'SF', 2, '["矮靠背","半靠背"]', '靠背明显低于肩部，仅及腰背部'),
('six_dim_B', 'SF-高靠背', '高靠背', 'SF', 3, '["高背","带头枕靠背"]', '靠背高过头部，可支撑头颈'),
('six_dim_B', 'SF-翼形包裹靠背', '翼形包裹靠背', 'SF', 4, '["翼背","耳朵靠背","包围式靠背","大象耳朵"]', '靠背两侧向前伸出侧翼，环抱头部'),
('six_dim_B', 'SF-分段靠包', '分段靠包', 'SF', 5, '["多靠枕","分离靠包","面包块靠背","活动靠包"]', '靠背由多个独立可移动靠包排列组成'),
('six_dim_B', 'SF-板面壳体靠背', '板面壳体靠背', 'SF', 6, '["曲木靠背","一体板背","贝壳靠背","塑料壳背"]', '靠背为一整片无分缝的弯曲板/壳体'),
('six_dim_B', 'SF-条形/梳背靠背', '条形/梳背靠背', 'SF', 7, '["竖条靠背","格栅靠背","温莎式","竖棂"]', '靠背由多根竖向细条/梳齿状杆件构成，有间隙'),
('six_dim_B', 'SF-Y形/叉骨靠背', 'Y形/叉骨靠背', 'SF', 8, '["叉骨椅","Y椅","Wishbone"]', '靠背中央为单根 Y 形（叉骨形）支撑构件'),
('six_dim_B', 'SF-编织镂空靠背', '编织镂空靠背', 'SF', 9, '["藤编靠背","绳编靠背","网面靠背"]', '靠背为编织/网状面，可见透空缝隙'),
('six_dim_B', 'SF-无靠背', '无靠背', 'SF', 10, '["无背","榻式"]', '坐面之后无任何靠背构件'),
('six_dim_B', 'SF-可调节/翻折靠背', '可调节/翻折靠背', 'SF', 11, '["可调靠背","活动头枕","档位靠背","可躺靠背"]', '可见铰链/档位结构，或靠背处于明显倾躺状态'),
('six_dim_B', 'SF-异形/其他', '异形/其他', 'SF', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × SF 沙发（同 FS 清单）（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'SF-无扶手', '无扶手', 'SF', 1, '["光杆","无臂"]', '座面两侧无任何扶手构件'),
('six_dim_C', 'SF-标准方扶手', '标准方扶手', 'SF', 2, '["直扶手","方扶手","常规扶手"]', '高度接近靠背、截面方正的常规软包扶手'),
('six_dim_C', 'SF-宽厚扶手', '宽厚扶手', 'SF', 3, '["宽扶手","厚扶手","面包扶手","大软垫扶手"]', '扶手又宽又厚，顶面可置物/坐人'),
('six_dim_C', 'SF-低扶手', '低扶手', 'SF', 4, '["矮扶手","低平扶手"]', '扶手仅略高于座面，明显低于标准高度'),
('six_dim_C', 'SF-卷边外翻扶手', '卷边外翻扶手', 'SF', 5, '["卷扶手","外翻扶手","美式卷臂"]', '扶手顶端向外/向前翻卷成圆卷'),
('six_dim_C', 'SF-斜面/梯形扶手', '斜面/梯形扶手', 'SF', 6, '["斜扶手","楔形扶手"]', '扶手呈内斜面或上窄下宽的楔形'),
('six_dim_C', 'SF-环形/圈形扶手', '环形/圈形扶手', 'SF', 7, '["圈扶手","圆扶手","环抱扶手"]', '扶手与靠背上沿连成连续圆环/半环'),
('six_dim_C', 'SF-细杆金属扶手', '细杆金属扶手', 'SF', 8, '["铁艺扶手","金属细扶手","线条扶手"]', '扶手由细圆管/扁钢线框构成'),
('six_dim_C', 'SF-木面扶手', '木面扶手', 'SF', 9, '["实木扶手","外露木扶手","木框扶手"]', '扶手可见完整木质面（按可见外观判定，不论真实材质）'),
('six_dim_C', 'SF-一体成型扶手', '一体成型扶手', 'SF', 10, '["一体扶手","壳体扶手"]', '扶手与座面/靠背为同一连续壳体，无分缝'),
('six_dim_C', 'SF-异形/其他', '异形/其他', 'SF', 11, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'SF-落地底座', '落地底座', 'SF', 1, '["落地式","无可见腿","贴地"]', '座体底边直接落地，看不到任何腿'),
('six_dim_D', 'SF-实木腿', '实木腿', 'SF', 2, '["木腿","实木脚","锥形木腿"]', '可见四条（或更多）木质腿，常为锥形/斜撑'),
('six_dim_D', 'SF-细金属腿', '细金属腿', 'SF', 3, '["金属细腿","铁艺细腿","细高脚"]', '纤细金属直腿/锥腿，离地间隙大'),
('six_dim_D', 'SF-敦实柱腿', '敦实柱腿', 'SF', 4, '["粗腿","方块脚","矮粗脚"]', '粗短块状或柱状脚，视觉厚重'),
('six_dim_D', 'SF-金属框架底座', '金属框架底座', 'SF', 5, '["铁架底座","外露框架","金属骨架"]', '座面以下由连续钢管/扁钢框架承托，框架外露'),
('six_dim_D', 'SF-雪橇底座', '雪橇底座', 'SF', 6, '["雪橇脚","滑橇底座","弓形架"]', '金属管沿地面形成前后连续滑橇状底架'),
('six_dim_D', 'SF-悬臂底座', '悬臂底座', 'SF', 7, '["悬臂椅","C形架","无后腿"]', '仅前部/单侧支撑，座面后端悬空无后腿'),
('six_dim_D', 'SF-中央柱底座', '中央柱底座', 'SF', 8, '["五星脚","圆盘底座","转椅底座","喇叭底座","郁金香底座"]', '单根中柱下接五星爪/圆盘/喇叭形底座，常可旋转'),
('six_dim_D', 'SF-摇椅底座', '摇椅底座', 'SF', 9, '["摇腿","弧形摇脚","摇摇椅"]', '底部为两根弧形摇杆，可前后摇晃'),
('six_dim_D', 'SF-悬浮底座', '悬浮底座', 'SF', 10, '["悬浮式","内收底座","悬空底座"]', '底座内收/隐藏，正视座体似漂浮（底部有阴影间隙）'),
('six_dim_D', 'SF-滚轮脚', '滚轮脚', 'SF', 11, '["带轮","脚轮","万向轮"]', '腿部末端可见滚轮'),
('six_dim_D', 'SF-异形/其他', '异形/其他', 'SF', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × SF 沙发（同 FS 清单）（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'SF-光面软包', '光面软包', 'SF', 1, '["素面","平整面","无明显工艺"]', '表面平整绷紧，无装饰性缝迹（最常见的默认项）'),
('six_dim_F', 'SF-饱满蓬松软包', '饱满蓬松软包', 'SF', 2, '["饱满填充","羽绒感","云朵感","厚软包"]', '垫体鼓起外溢、边缘松垮下垂、褶皱自然'),
('six_dim_F', 'SF-薄垫紧凑软包', '薄垫紧凑软包', 'SF', 3, '["薄垫","薄软包","贴合坐垫"]', '垫体薄且紧贴框架，轮廓利落'),
('six_dim_F', 'SF-绗缝', '绗缝', 'SF', 4, '["车格子","间棉","菱形格缝线"]', '表面有规则缝线格纹/条纹'),
('six_dim_F', 'SF-拉扣', '拉扣', 'SF', 5, '["纽扣软包","拉点","tufted"]', '表面有规律分布的内凹扣点/纽扣'),
('six_dim_F', 'SF-褶皱/抽褶', '褶皱/抽褶', 'SF', 6, '["抓褶","毛毛虫褶皱","横向褶皱"]', '表面呈规律横向/放射状挤压褶皱'),
('six_dim_F', 'SF-分块面包块', '分块面包块', 'SF', 7, '["面包块","豆腐块分格","块状软包"]', '座面/靠背分成若干饱满凸起的方块或长条块'),
('six_dim_F', 'SF-局部软包', '局部软包', 'SF', 8, '["半软包","仅坐垫软包","座面垫"]', '仅座面（或仅靠背）有软包，其余为裸露框架/硬面'),
('six_dim_F', 'SF-无软包', '无软包', 'SF', 9, '["硬座","裸面","板面"]', '坐靠处为木/塑/金属硬面，无任何软垫'),
('six_dim_F', 'SF-异形/其他', '异形/其他', 'SF', 10, NULL, '不属于以上工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × TB 茶几（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'TB-圆形', '圆形', 'TB', 1, '["圆几","大小圆"]', '俯视台面为正圆或近圆'),
('six_dim_A', 'TB-方形', '方形', 'TB', 2, '["方几","正方形茶几"]', '俯视四边近等长、四角分明（含近方矩形）'),
('six_dim_A', 'TB-矩形', '矩形', 'TB', 3, '["长方形茶几","长几"]', '俯视明显长宽不等的直边形'),
('six_dim_A', 'TB-跑道形', '跑道形', 'TB', 4, '["胶囊形","腰果形","长圆形","腰子形"]', '两端半圆+中间直边的拉长圆弧轮廓'),
('six_dim_A', 'TB-鹅卵石形', '鹅卵石形', 'TB', 5, '["蛋形","水滴形","不规则有机形"]', '轮廓为无直边的自由曲线有机形'),
('six_dim_A', 'TB-C形', 'C形', 'TB', 6, '["C型边几","U形边几","沙发边桌","嵌入式边几"]', '侧视呈C字悬臂，底座可插入沙发/床底'),
('six_dim_A', 'TB-鼓形/墩式', '鼓形/墩式', 'TB', 7, '["鼓凳茶几","圆墩茶几","柱墩茶几"]', '整体为一个实心鼓/墩体，无独立细腿'),
('six_dim_A', 'TB-托盘式', '托盘式', 'TB', 8, '["托盘茶几","围边茶几","碟形茶几"]', '俯视整体呈碟形/托盘轮廓且台面围边上翻（围边工艺归 C 维度）'),
('six_dim_A', 'TB-组合套几', '组合套几', 'TB', 9, '["子母茶几","大小组合","套几","高低组合"]', '两张以上大小/高低茶几嵌套或成组出现'),
('six_dim_A', 'TB-异形/其他', '异形/其他', 'TB', 10, '["不规则形","艺术造型"]', '无法归入上述类别的特殊轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × TB 茶几（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'TB-薄台面', '薄台面', 'TB', 1, '["薄板台面","极简薄边"]', '侧视台面单薄一片（厚度相对台面宽度比例小）'),
('six_dim_B', 'TB-厚台面', '厚台面', 'TB', 2, '["厚板台面","大板面"]', '侧视台面明显厚实有体量感'),
('six_dim_B', 'TB-悬浮台面', '悬浮台面', 'TB', 3, '["悬空台面","漂浮台面"]', '台面与支撑间可见内收/缝隙，似漂浮（支撑形态正常）'),
('six_dim_B', 'TB-内嵌/下沉台面', '内嵌/下沉台面', 'TB', 4, '["内凹台面","嵌入式台面"]', '面心低于边框，形成浅槽或嵌板结构'),
('six_dim_B', 'TB-双层台面', '双层台面', 'TB', 5, '["上下双层","夹层台面"]', '可见上下两层平行台面'),
('six_dim_B', 'TB-异形拼色/拼接台面', '异形拼色/拼接台面', 'TB', 6, '["拼色台面","组合台面"]', '台面由两种以上材质/色块拼接'),
('six_dim_B', 'TB-其他', '其他', 'TB', 7, NULL, '无法归类的台面构造')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × TB 茶几（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'TB-直边', '直边', 'TB', 1, '["直角边","平边"]', '边缘为垂直利落的直切面'),
('six_dim_C', 'TB-倒角边', '倒角边', 'TB', 2, '["斜边","切角边","斜切边"]', '边缘可见明显斜切坡面/斜面反光带'),
('six_dim_C', 'TB-圆弧边', '圆弧边', 'TB', 3, '["圆角边","R角","磨圆边"]', '边缘倒圆过渡，无棱角'),
('six_dim_C', 'TB-马肚边', '马肚边', 'TB', 4, '["鸭嘴边","法国边","弧腹边"]', '边缘外凸成连续饱满弧线'),
('six_dim_C', 'TB-瀑布边', '瀑布边', 'TB', 5, '["下挂边","垂边","waterfall"]', '台面材质沿侧边垂直下延至支撑/地面'),
('six_dim_C', 'TB-自然边', '自然边', 'TB', 6, '["原木边","树皮边","随形边"]', '边缘保留木材天然起伏/树皮轮廓'),
('six_dim_C', 'TB-围边', '围边', 'TB', 7, '["挡水边","立边","翻边","托盘边"]', '边缘向上凸起一圈挡沿（托盘式茶几的围边工艺归此维度）'),
('six_dim_C', 'TB-水波纹边', '水波纹边', 'TB', 8, '["热熔边","波浪边","熔岩边"]', '边缘呈不规则流动波浪起伏'),
('six_dim_C', 'TB-异形/其他', '异形/其他', 'TB', 9, NULL, '其他边缘处理')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × TB 茶几（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'TB-细直腿', '细直腿', 'TB', 1, '["细腿","直脚","细圆腿"]', '3~4根纤细垂直腿支撑'),
('six_dim_D', 'TB-锥形腿', '锥形腿', 'TB', 2, '["锥形脚","上粗下细腿","斜锥腿"]', '腿自上而下逐渐收细'),
('six_dim_D', 'TB-外八腿', '外八腿', 'TB', 3, '["八字腿","斜腿","A字腿"]', '腿向外倾斜张开呈八字'),
('six_dim_D', 'TB-粗柱腿', '粗柱腿', 'TB', 4, '["敦实柱腿","墩柱腿","象腿","粗圆/方柱"]', '单根或多根粗壮柱状支撑'),
('six_dim_D', 'TB-金属框架', '金属框架', 'TB', 5, '["铁艺框架","口字架","U形架","方框腿"]', '金属管/板构成框式支撑'),
('six_dim_D', 'TB-交叉底座', '交叉底座', 'TB', 6, '["X形底座","交叉腿","剪刀腿"]', '支撑件交叉成X/剪刀形'),
('six_dim_D', 'TB-单柱圆盘底座', '单柱圆盘底座', 'TB', 7, '["郁金香底座","喇叭底座","独柱底盘"]', '中央单柱+落地圆/喇叭盘'),
('six_dim_D', 'TB-悬浮底座', '悬浮底座', 'TB', 8, '["内收底座","隐形底座","亚克力底座"]', '底座明显内收/透明，台面似悬空'),
('six_dim_D', 'TB-落地式', '落地式', 'TB', 9, '["箱式底座","落地围板","满底"]', '支撑为落地箱体/围板，不见腿'),
('six_dim_D', 'TB-无腿墩式', '无腿墩式', 'TB', 10, '["整体墩","实心墩"]', '整器为一落地实心墩体，无腿可辨'),
('six_dim_D', 'TB-异形/其他', '异形/其他', 'TB', 11, NULL, '其他支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × TB 茶几（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'TB-无功能件', '无功能件', 'TB', 1, '["固定式","纯台面"]', '无任何附加可动/储物结构（默认兜底）'),
('six_dim_F', 'TB-抽屉', '抽屉', 'TB', 2, '["带抽茶几"]', '立面可见抽屉面板/拉手'),
('six_dim_F', 'TB-层板', '层板', 'TB', 3, '["隔板","置物层","开放格"]', '台面下可见开放层板/隔层'),
('six_dim_F', 'TB-升降台面', '升降台面', 'TB', 4, '["升降茶几","Lift-top","可升桌板"]', '台面可抬升，可见铰链/气压杆结构'),
('six_dim_F', 'TB-旋转功能件', '旋转功能件', 'TB', 5, '["旋转台面","转动盘","旋转层"]', '可见可转动圆盘/旋转层结构'),
('six_dim_F', 'TB-折叠', '折叠', 'TB', 6, '["折叠茶几","翻折面"]', '可见折叠铰链/可收折的腿或面'),
('six_dim_F', 'TB-滚轮', '滚轮', 'TB', 7, '["带轮茶几","移动茶几"]', '底部可见脚轮/万向轮'),
('six_dim_F', 'TB-套叠收纳', '套叠收纳', 'TB', 8, '["可叠放","嵌套收纳"]', '小几可完全收入大几之下'),
('six_dim_F', 'TB-其他', '其他', 'TB', 9, NULL, '其他可见功能件')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'DT-长桌', '长桌', 'DT', 1, '["长方形餐桌","条桌","长条桌"]', '俯视为长矩形，市场绝对主力'),
('six_dim_A', 'DT-圆桌', '圆桌', 'DT', 2, '["圆餐桌","大圆桌"]', '俯视为正圆'),
('six_dim_A', 'DT-方桌', '方桌', 'DT', 3, '["方餐桌","八仙桌","四人方桌"]', '俯视四边等长'),
('six_dim_A', 'DT-椭圆桌', '椭圆桌', 'DT', 4, '["椭圆形餐桌","蛋形桌"]', '俯视为规则椭圆'),
('six_dim_A', 'DT-跑道形', '跑道形', 'DT', 5, '["胶囊形","腰果形","长圆桌"]', '两端半圆+直边的拉长轮廓'),
('six_dim_A', 'DT-半圆桌', '半圆桌', 'DT', 6, '["半月桌","靠墙半圆"]', '一侧直边靠墙、一侧半圆'),
('six_dim_A', 'DT-岛台一体桌', '岛台一体桌', 'DT', 7, '["岛台餐桌","餐岛一体","中岛台"]', '桌与厨房岛台连成一体的组合形态'),
('six_dim_A', 'DT-吧台桌', '吧台桌', 'DT', 8, '["吧桌","高脚桌","高吧桌"]', '明显高台面（约90cm及以上）配高脚凳'),
('six_dim_A', 'DT-异形/其他', '异形/其他', 'DT', 9, '["不规则桌"]', '其他轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'DT-平板薄面', '平板薄面', 'DT', 1, '["薄边平板","薄板台面"]', '侧视台面单薄平整'),
('six_dim_B', 'DT-厚台面', '厚台面', 'DT', 2, '["厚板","大板台面"]', '侧视台面厚实有分量'),
('six_dim_B', 'DT-悬浮台面', '悬浮台面', 'DT', 3, '["悬空台面"]', '台面与底座间内收/留缝似漂浮'),
('six_dim_B', 'DT-转盘台面', '转盘台面', 'DT', 4, '["带转盘桌面","双层圆面","旋转餐盘"]', '圆桌面中央可见可转动转盘（转盘特征统一归此维度）'),
('six_dim_B', 'DT-翻板台面', '翻板台面', 'DT', 5, '["蝶形翻板","折叠面板"]', '桌面可见翻折板与铰链拼缝'),
('six_dim_B', 'DT-拼缝台面', '拼缝台面', 'DT', 6, '["拼板台面","伸缩缝面"]', '桌面中部可见伸缩拼接缝/轨道线'),
('six_dim_B', 'DT-内嵌台面', '内嵌台面', 'DT', 7, '["嵌板面","框中嵌面"]', '面板嵌入边框，材质混搭'),
('six_dim_B', 'DT-围边台面', '围边台面', 'DT', 8, '["挡边面","托盘面"]', '桌面边缘上翻有围挡'),
('six_dim_B', 'DT-其他', '其他', 'DT', 9, NULL, '其他台面构造')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × DT 餐桌（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'DT-直边', '直边', 'DT', 1, '["直角边","平边"]', '边缘垂直利落'),
('six_dim_C', 'DT-圆角边', '圆角边', 'DT', 2, '["圆弧边","R角","磨圆边"]', '角部/边缘倒圆'),
('six_dim_C', 'DT-倒角边', '倒角边', 'DT', 3, '["斜边","切角边"]', '边缘可见斜切坡面'),
('six_dim_C', 'DT-马肚边', '马肚边', 'DT', 4, '["鸭嘴边","弧腹边","法国边"]', '边缘外凸成饱满连续弧线'),
('six_dim_C', 'DT-瀑布边', '瀑布边', 'DT', 5, '["下挂边","垂边","waterfall edge"]', '台面材质沿侧边垂直下延'),
('six_dim_C', 'DT-自然边', '自然边', 'DT', 6, '["原木边","随形边"]', '保留木材天然轮廓的边缘'),
('six_dim_C', 'DT-裙边/立水', '裙边/立水', 'DT', 7, '["围板","牙板","束腰"]', '台面下沿有一圈可见围板/牙条结构'),
('six_dim_C', 'DT-异形/其他', '异形/其他', 'DT', 8, NULL, '其他边缘/结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × DT 餐桌（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'DT-四直腿', '四直腿', 'DT', 1, '["直腿","四方腿","立柱腿"]', '四角各一根垂直腿'),
('six_dim_D', 'DT-外八腿', '外八腿', 'DT', 2, '["八字腿","斜腿","A字腿"]', '腿向外张开呈八字'),
('six_dim_D', 'DT-锥形腿', '锥形腿', 'DT', 3, '["锥形脚","收细腿"]', '腿上粗下细逐渐收分'),
('six_dim_D', 'DT-喇叭/郁金香底座', '喇叭/郁金香底座', 'DT', 4, '["喇叭腿","郁金香脚","独柱圆盘"]', '中央单柱下接喇叭形圆盘'),
('six_dim_D', 'DT-粗柱腿', '粗柱腿', 'DT', 5, '["大象腿","墩柱腿","粗方/圆柱"]', '粗壮柱状腿，体量感强'),
('six_dim_D', 'DT-T形/一字底座', 'T形/一字底座', 'DT', 6, '["T字腿","一字脚架"]', '两端各一T字/一字横脚支撑'),
('six_dim_D', 'DT-工字/H形底座', '工字/H形底座', 'DT', 7, '["工字架","H形腿"]', '侧视呈工/H形的框架支撑'),
('six_dim_D', 'DT-交叉底座', '交叉底座', 'DT', 8, '["X形底座","交叉腿","叉骨腿"]', '支撑交叉成X形'),
('six_dim_D', 'DT-U形/口字框架', 'U形/口字框架', 'DT', 9, '["口字腿","U形架","框式腿"]', '两端各一封闭框式支撑'),
('six_dim_D', 'DT-悬浮/内收底座', '悬浮/内收底座', 'DT', 10, '["悬浮底座","隐形底座"]', '底座深内收或透明，桌面似悬空'),
('six_dim_D', 'DT-落地箱式', '落地箱式', 'DT', 11, '["箱体底座","岛台落地柜"]', '支撑为落地柜体/箱体'),
('six_dim_D', 'DT-异形/其他', '异形/其他', 'DT', 12, NULL, '其他支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'DT-固定式', '固定式', 'DT', 1, '["不可伸缩","整板"]', '桌面一整块，无变形结构'),
('six_dim_F', 'DT-伸缩式', '伸缩式', 'DT', 2, '["抽拉伸缩","导轨伸缩","加长桌"]', '桌面可沿导轨拉开加长（可见伸缩缝/导轨）'),
('six_dim_F', 'DT-折叠式', '折叠式', 'DT', 3, '["折叠桌","翻板桌","蝶形折叠"]', '桌板/腿可翻折收合，可见铰链'),
('six_dim_F', 'DT-旋转展开', '旋转展开', 'DT', 4, '["旋转变径","旋转伸缩圆桌"]', '圆桌旋开后拼板展开变大'),
('six_dim_F', 'DT-升降', '升降', 'DT', 5, '["升降餐桌","茶几餐桌两用"]', '桌面高度可调（气压/电动柱可见）'),
('six_dim_F', 'DT-带储物', '带储物', 'DT', 6, '["带抽屉","带柜餐桌"]', '桌体可见抽屉/柜门'),
('six_dim_F', 'DT-带电器件', '带电器件', 'DT', 7, '["电磁炉餐桌","暖菜板桌","火锅桌"]', '桌面可见嵌入式电磁炉/暖菜板/插座面板'),
('six_dim_F', 'DT-带滚轮', '带滚轮', 'DT', 8, '["移动餐桌","带轮岛台"]', '底部可见脚轮'),
('six_dim_F', 'DT-其他', '其他', 'DT', 9, NULL, '其他可见功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × FC 柜类（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'FC-矮柜', '矮柜', 'FC', 1, '["地柜","半高柜","矮边柜","低柜"]', '高度齐腰及以下、横长比例的独立柜体'),
('six_dim_A', 'FC-高柜', '高柜', 'FC', 2, '["立柜","通顶柜","到顶柜","一门到顶"]', '高度明显超过人胸/到顶、竖长比例'),
('six_dim_A', 'FC-组合柜', '组合柜', 'FC', 3, '["高低组合柜","模块柜","电视墙柜","满墙柜"]', '多个柜体单元拼接、高低错落或满墙一体'),
('six_dim_A', 'FC-斗柜', '斗柜', 'FC', 4, '["三斗柜","四斗柜","五斗柜","多斗柜"]', '竖向或横向叠放多个等大抽屉、无门或少量门'),
('six_dim_A', 'FC-转角柜', '转角柜', 'FC', 5, '["L型柜","角柜","拐角柜","三角柜"]', '贴合两面墙转角、俯视呈L形或三角形'),
('six_dim_A', 'FC-弧形柜', '弧形柜', 'FC', 6, '["圆弧柜","圆角柜","弧形边柜","拱形柜"]', '侧板或转角为圆弧曲面而非直角'),
('six_dim_A', 'FC-隔断柜', '隔断柜', 'FC', 7, '["博古架柜","双面柜","镂空置物柜"]', '柜体通透双面可用、作空间分隔'),
('six_dim_A', 'FC-其他', '其他', 'FC', 8, NULL, '无法归入以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × FC 柜类（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'FC-平板门', '平板门', 'FC', 1, '["光板门","双饰面门","PET门板","肤感门"]', '门面平整无造型、无压线'),
('six_dim_B', 'FC-造型门', '造型门', 'FC', 2, '["吸塑门","模压门","拼框门","法式线条门","回字门"]', '门面带内凹/凸起的框线造型'),
('six_dim_B', 'FC-骨骼线门', '骨骼线门', 'FC', 3, '["骨骼门","外凸线条门","井字/回字线条门"]', '门板表面有外凸装饰木线条且常兼作拉手'),
('six_dim_B', 'FC-格栅门', '格栅门', 'FC', 4, '["竖条纹门","木格栅门","栅格门","长城板门"]', '门面为连续竖向凸条纹或镂空格栅'),
('six_dim_B', 'FC-藤编门', '藤编门', 'FC', 5, '["藤面门","藤编框门","编藤柜门"]', '门框内嵌编织藤面、可见经纬编织纹'),
('six_dim_B', 'FC-长虹玻璃门', '长虹玻璃门', 'FC', 6, '["竖纹玻璃门","瓦楞玻璃门"]', '玻璃面带竖向压花条纹、朦胧透光'),
('six_dim_B', 'FC-玻璃门', '玻璃门', 'FC', 7, '["清玻门","灰玻门","茶玻门","铝框玻璃门"]', '透明/有色平玻璃、柜内物品可见'),
('six_dim_B', 'FC-移门', '移门', 'FC', 8, '["推拉门","趟门","滑门"]', '门板左右滑轨平移开启、有重叠门扇'),
('six_dim_B', 'FC-开放格为主', '开放格为主', 'FC', 9, '["敞口柜","格子柜","无门柜"]', '立面大部分为无门敞口格'),
('six_dim_B', 'FC-抽屉为主', '抽屉为主', 'FC', 10, '["全抽屉柜","多抽柜"]', '立面大部分为抽屉面板'),
('six_dim_B', 'FC-其他', '其他', 'FC', 11, '["百叶门","软包门"]', '百叶门、软包门等少见形式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × FC 柜类（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'FC-明装拉手', '明装拉手', 'FC', 1, '["外装拉手","明拉手","圆钮/长条/黄铜把手"]', '拉手凸出安装于门板表面'),
('six_dim_C', 'FC-无拉手', '无拉手', 'FC', 2, '["反弹器","按压弹开","碰碰开"]', '门面完全平整无任何拉手/凹槽'),
('six_dim_C', 'FC-铣槽拉手', '铣槽拉手', 'FC', 3, '["凹槽拉手","J型/C型槽","牛角槽","一字槽"]', '门板边或面上铣出同色暗槽作抠手'),
('six_dim_C', 'FC-斜切拉手', '斜切拉手', 'FC', 4, '["45°斜切","斜边拉手","切角拉手"]', '门板上/下沿45°斜切留缝作抠手位'),
('six_dim_C', 'FC-G型拉手', 'G型拉手', 'FC', 5, '["G型/L型/U型金属型材拉手","内嵌型材拉手"]', '柜体层板间嵌金属型材形成通长拉槽'),
('six_dim_C', 'FC-门板下延', '门板下延', 'FC', 6, '["门板下挂","门板加长","上延抠手"]', '门板比柜体长1-2cm，抠延伸边开门'),
('six_dim_C', 'FC-隐形拉手', '隐形拉手', 'FC', 7, '["线性隐形拉手","拇指拉手","嵌入式拉手"]', '门缝处极窄长条、存在感极低'),
('six_dim_C', 'FC-其他', '其他', 'FC', 8, '["皮拉手","旋转拉手"]', '皮拉手、旋转拉手等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × FC 柜类（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'FC-落地式', '落地式', 'FC', 1, '["落地柜","围板落地","踢脚板底座"]', '柜体直接落地、底部为围板/踢脚线'),
('six_dim_D', 'FC-高脚', '高脚', 'FC', 2, '["细高腿","锥形木脚","实木脚"]', '四只细长脚抬高柜体、底部大空隙'),
('six_dim_D', 'FC-金属支脚', '金属支脚', 'FC', 3, '["金属脚","不锈钢脚","金色细脚","铁艺脚"]', '金属材质细脚或框架脚'),
('six_dim_D', 'FC-矮脚', '矮脚', 'FC', 4, '["小矮脚","方墩脚","离地缝脚"]', '脚高不超过15cm、留缝可过扫地机'),
('six_dim_D', 'FC-悬浮挂墙', '悬浮挂墙', 'FC', 5, '["挂墙柜","壁挂柜","悬空柜","吊柜"]', '柜体固定上墙、底部完全悬空'),
('six_dim_D', 'FC-带轮', '带轮', 'FC', 6, '["万向轮","滑轮柜","移动柜"]', '底部可见滚轮、可移动'),
('six_dim_D', 'FC-其他', '其他', 'FC', 7, '["旋转底座"]', '旋转底座等少见形式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × FC 柜类（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'FC-隔层', '隔层', 'FC', 1, '["层板","可调层板","隔板分隔"]', '内部以水平层板分隔空间'),
('six_dim_F', 'FC-抽屉', '抽屉', 'FC', 2, '["内置抽屉","抽内分隔"]', '门内或外露抽屉收纳'),
('six_dim_F', 'FC-设备位', '设备位', 'FC', 3, '["视听位","机顶盒位","走线孔","插座位"]', '可见开放设备格、背板走线孔/插座'),
('six_dim_F', 'FC-酒架杯架', '酒架杯架', 'FC', 4, '["红酒格","酒格","酒杯挂架"]', '可见X/方格酒架或高脚杯倒挂架'),
('six_dim_F', 'FC-鞋柜结构', '鞋柜结构', 'FC', 5, '["斜插鞋架","翻斗鞋柜","翻转鞋架"]', '斜放鞋位或下翻斗门结构'),
('six_dim_F', 'FC-台面功能区', '台面功能区', 'FC', 6, '["操作台","水吧台","轨道插座","咖啡角"]', '中部留空台面+背板插座/管线位'),
('six_dim_F', 'FC-灯带', '灯带', 'FC', 7, '["感应灯","氛围灯带","展示灯"]', '层板下/柜内可见发光灯带'),
('six_dim_F', 'FC-挂物区', '挂物区', 'FC', 8, '["挂衣钩","洞洞板","挂杆"]', '开放挂衣/挂物区'),
('six_dim_F', 'FC-其他', '其他', 'FC', 9, '["旋转鞋架","抽拉台面"]', '旋转鞋架、抽拉台面等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'BD-齐边床', '齐边床', 'BD', 1, '["齐边设计","窄边床","省空间床"]', '床边与床垫基本齐平、无外扩床框'),
('six_dim_A', 'BD-内嵌床', '内嵌床', 'BD', 2, '["包边床","宽边床","床垫内嵌床"]', '床垫陷入床框、四周有明显宽床沿'),
('six_dim_A', 'BD-地台床', '地台床', 'BD', 3, '["榻榻米床","落地矮床","无腿床"]', '低矮落地台座、床垫直接置于地台'),
('six_dim_A', 'BD-箱体床', '箱体床', 'BD', 4, '["箱框床","厚床身床","高床体床"]', '床身整体厚实落地、呈箱子轮廓'),
('six_dim_A', 'BD-上下床', '上下床', 'BD', 5, '["高低床","双层床","子母床","上下铺"]', '上下两层床面+爬梯/梯柜'),
('six_dim_A', 'BD-半高床/高架床', '半高床/高架床', 'BD', 6, '["中高床","上床下桌","上床下柜"]', '床面抬高、床下为书桌/柜/活动空间'),
('six_dim_A', 'BD-异形造型床', '异形造型床', 'BD', 7, '["汽车床","房子床","城堡床","圆床"]', '整体为卡通/非常规几何造型'),
('six_dim_A', 'BD-其他', '其他', 'BD', 8, '["折叠床","壁床","墨菲床"]', '折叠床、壁床（墨菲床）等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × BD 床（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'BD-软包大靠包', '软包大靠包', 'BD', 1, '["大靠枕","双靠包","饱满靠包","分段靠包"]', '床头为1-2个饱满突出的软靠包'),
('six_dim_B', 'BD-拉扣床头', '拉扣床头', 'BD', 2, '["拉点床头","纽扣床头","法式拉扣","铆钉床头"]', '软包表面有规则凹点纽扣拉花'),
('six_dim_B', 'BD-竖琴床头', '竖琴床头', 'BD', 3, '["竖琴床","温莎床","竖条床头","栅栏床屏"]', '床头由竖向细条/立柱排列构成'),
('six_dim_B', 'BD-平板薄床头', '平板薄床头', 'BD', 4, '["超薄床头","极简床屏","直板床头"]', '床头为薄而平整的一整块板/软包面'),
('six_dim_B', 'BD-折耳靠包', '折耳靠包', 'BD', 5, '["大象耳朵","象耳床","翻折耳朵","Baxter象耳"]', '靠包两侧可翻折下垂如大象耳朵'),
('six_dim_B', 'BD-信封床头', '信封床头', 'BD', 6, '["信封床","信封翻折靠头","皮带扣床头"]', '软包如信封翻折、饰皮带/五金扣'),
('six_dim_B', 'BD-云朵床头', '云朵床头', 'BD', 7, '["云朵床","花瓣床头","波浪软包"]', '床屏轮廓为圆润云朵/花瓣曲线'),
('six_dim_B', 'BD-拱形床头', '拱形床头', 'BD', 8, '["拱门床屏","法式拱门","圆弧床头"]', '床屏顶部为拱形/大圆弧线'),
('six_dim_B', 'BD-卡通造型床头', '卡通造型床头', 'BD', 9, '["猫耳床","兔耳床","皇冠床头"]', '床头带动物耳朵/卡通轮廓'),
('six_dim_B', 'BD-功能床头', '功能床头', 'BD', 10, '["置物床头","书架床头","储物床屏"]', '床屏带置物格/开放架/插座夜灯'),
('six_dim_B', 'BD-无床头', '无床头', 'BD', 11, '["无床屏","裸床架","榻榻米床"]', '无床头板、床尾床头同高或靠墙'),
('six_dim_B', 'BD-其他', '其他', 'BD', 12, '["屏风式","超宽屏床头"]', '屏风式、超宽屏床头等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'BD-齐边无床尾', '齐边无床尾', 'BD', 1, '["无床尾屏","低床尾","齐平床尾"]', '床尾与床垫齐平或更低、无遮挡'),
('six_dim_C', 'BD-高床尾屏', '高床尾屏', 'BD', 2, '["床尾板","美式床尾","高尾屏"]', '床尾有明显高出的屏板'),
('six_dim_C', 'BD-立柱床尾', '立柱床尾', 'BD', 3, '["罗马柱床","四柱床","床尾柱"]', '床尾带装饰立柱'),
('six_dim_C', 'BD-软包床尾', '软包床尾', 'BD', 4, '["软包尾板","床尾凳一体"]', '床尾为软包面或与床体同面料'),
('six_dim_C', 'BD-宽边床沿', '宽边床沿', 'BD', 5, '["宽床沿","可坐床边","平台床边"]', '床侧床沿宽大可坐可置物'),
('six_dim_C', 'BD-圆角床边', '圆角床边', 'BD', 6, '["圆弧收边","防撞圆边","圆润转角"]', '床边转角为圆弧无棱角'),
('six_dim_C', 'BD-铁艺床尾', '铁艺床尾', 'BD', 7, '["金属管床尾","铁艺花纹床围"]', '床尾为铁艺管材/花纹'),
('six_dim_C', 'BD-其他', '其他', 'BD', 8, '["弧形床尾","船型床尾"]', '弧形床尾、船型床尾等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × BD 床（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'BD-实木脚', '实木脚', 'BD', 1, '["木腿","锥形木脚","实木床脚"]', '可见木质支脚（含中脚）'),
('six_dim_D', 'BD-金属脚', '金属脚', 'BD', 2, '["金属细脚","不锈钢脚","碳素钢脚","金色脚"]', '可见金属材质支脚'),
('six_dim_D', 'BD-矮脚', '矮脚', 'BD', 3, '["小矮脚","短脚","离地缝脚"]', '床底仅留小缝隙（约5-15cm）'),
('six_dim_D', 'BD-落地无脚', '落地无脚', 'BD', 4, '["全落地","围边落地","床裙落地"]', '床体四周直接落地不见脚'),
('six_dim_D', 'BD-悬浮底座', '悬浮底座', 'BD', 5, '["内收底座","悬浮床脚","灯带底座","悬浮床剪影"]', '支脚内缩视觉悬浮、常配床底灯（悬浮特征统一归此维度）'),
('six_dim_D', 'BD-排骨架床架', '排骨架床架', 'BD', 6, '["排骨架","床条架","卷闸排骨条"]', '床面由横向木条排列构成（无床垫图可见时适用）'),
('six_dim_D', 'BD-其他', '其他', 'BD', 7, '["万向轮脚","气压升降脚"]', '万向轮脚、气压升降脚等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'BD-无储物', '无储物', 'BD', 1, '["框架床","空床底","普通床架"]', '床底通透或封闭但无任何储物开启结构'),
('six_dim_F', 'BD-气压上掀储物', '气压上掀储物', 'BD', 2, '["高箱床","气压杆床","液压箱体床","油压床"]', '床板连床垫整体可上掀、床体为大箱仓'),
('six_dim_F', 'BD-床体抽屉', '床体抽屉', 'BD', 3, '["侧抽床","床尾抽屉","三抽储物床"]', '床侧/床尾可见抽屉面板与拉手缝'),
('six_dim_F', 'BD-床头功能区', '床头功能区', 'BD', 4, '["置物床头","充电床头","书架床头","夜灯床头"]', '床屏带格架/插座/灯等可见功能件'),
('six_dim_F', 'BD-床底灯带', '床底灯带', 'BD', 5, '["悬浮灯","感应夜灯","氛围灯"]', '床底沿可见灯带发光'),
('six_dim_F', 'BD-组合功能床', '组合功能床', 'BD', 6, '["上床下桌","梯柜床","床柜一体","榻榻米组合"]', '床与书桌/梯柜/衣柜连成一体'),
('six_dim_F', 'BD-拖床', '拖床', 'BD', 7, '["拖拉子床","抽拉床","子母拖床"]', '主床下藏可拉出的小床'),
('six_dim_F', 'BD-其他', '其他', 'BD', 8, '["电动床架","按摩功能"]', '电动床架、按摩功能等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × BS 吧椅（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'BS-圆形座', '圆形座', 'BS', 1, '["圆凳面","圆盘座","圆面吧凳"]', '座面俯视呈正圆或椭圆形，无方向性'),
('six_dim_A', 'BS-方形座', '方形座', 'BS', 2, '["方凳面","矩形座","方座"]', '座面俯视呈正方形或矩形，可见直边直角'),
('six_dim_A', 'BS-曲壳座', '曲壳座', 'BS', 3, '["曲木座","弯板座","一体壳座","PP座壳"]', '座面为一体化成型曲面壳体，带人体工学下凹弧度'),
('six_dim_A', 'BS-马鞍座', '马鞍座', 'BS', 4, '["鞍形座","马鞍凳","W形座"]', '座面呈马鞍状前后翘起、中间下凹的W双曲面'),
('six_dim_A', 'BS-镂空铁座', '镂空铁座', 'BS', 5, '["拖拉机座","拖拉机凳","铸铁座","工业风座"]', '金属座面两侧凸起带镂空孔洞，形似老式拖拉机座'),
('six_dim_A', 'BS-马蹄形座', '马蹄形座', 'BS', 6, '["马蹄座","U形座","半圆口座"]', '座面后缘或前缘带U形/马蹄形缺口或轮廓'),
('six_dim_A', 'BS-其他', '其他', 'BS', 7, NULL, '不属于以上任何形态的座面')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × BS 吧椅（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'BS-无靠背', '无靠背', 'BS', 1, '["无背","光凳","吧凳"]', '座面上方完全无竖向支撑结构'),
('six_dim_B', 'BS-低靠背', '低靠背', 'BS', 2, '["半靠背","矮靠背","腰靠"]', '靠背高度仅及腰部，明显低于肩胛'),
('six_dim_B', 'BS-高靠背', '高靠背', 'BS', 3, '["全靠背","高背吧椅"]', '靠背向上延伸至肩胛及以上，形成完整背部支撑面'),
('six_dim_B', 'BS-环抱式靠背', '环抱式靠背', 'BS', 4, '["环绕靠背","一体壳背","天鹅椅式","蝴蝶背"]', '靠背呈弧形向前环抱包覆，与座壳连成有机曲面'),
('six_dim_B', 'BS-竖条靠背', '竖条靠背', 'BS', 5, '["梳背","温莎背","条背"]', '靠背由多根竖向细条排列构成，条间可见缝隙'),
('six_dim_B', 'BS-镂空网格靠背', '镂空网格靠背', 'BS', 6, '["镂空背","铁艺背","网格背","冲孔背"]', '靠背为带规则或不规则镂空图案的板面/网格'),
('six_dim_B', 'BS-编织靠背', '编织靠背', 'BS', 7, '["藤编背","绳编背","编带背"]', '靠背可见藤条/绳/织带交叉编织纹理'),
('six_dim_B', 'BS-其他', '其他', 'BS', 8, NULL, '不属于以上任何形态的靠背')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × BS 吧椅（6 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'BS-无扶手', '无扶手', 'BS', 1, '["光身","无臂"]', '座面两侧无任何供手臂搭放的结构'),
('six_dim_C', 'BS-双扶手', '双扶手', 'BS', 2, '["两侧扶手","独立扶手","全扶手"]', '座面左右各有一根独立于靠背的扶手杆/板'),
('six_dim_C', 'BS-一体环绕扶手', '一体环绕扶手', 'BS', 3, '["一体扶手","环抱扶手","壳式扶手"]', '扶手与靠背由同一曲面/杆件连续环绕成型，无断点'),
('six_dim_C', 'BS-小扶手', '小扶手', 'BS', 4, '["半扶手","短扶手","半截扶手"]', '扶手长度明显短于座面深度，仅覆盖后半段或呈短柱状'),
('six_dim_C', 'BS-环形扶手', '环形扶手', 'BS', 5, '["圆环扶手","皇冠扶手"]', '扶手为一整圈环形杆件环绕座面上方（低频，注意与座下脚踏圈区分）'),
('six_dim_C', 'BS-其他', '其他', 'BS', 6, NULL, '不属于以上任何形态的扶手')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × BS 吧椅（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'BS-气压升降底座', '气压升降底座', 'BS', 1, '["气压杆","升降底座","气杆圆盘"]', '座下可见单根金属气压柱+圆形平底盘，常带环形脚踏圈'),
('six_dim_D', 'BS-四脚底座', '四脚底座', 'BS', 2, '["四条腿","木脚","四腿架"]', '四根独立椅腿支撑，腿间常有横撑（踏脚撑）'),
('six_dim_D', 'BS-单柱圆盘底座', '单柱圆盘底座', 'BS', 3, '["固定圆盘","喇叭底座","郁金香脚","圆盘脚"]', '单根立柱下接一体成型圆盘/喇叭形盘，不可升降'),
('six_dim_D', 'BS-五星脚底座', '五星脚底座', 'BS', 4, '["五爪脚","五星脚","带轮底座"]', '中心柱下伸出五爪支脚且末端带滚轮'),
('six_dim_D', 'BS-螺旋升降底座', '螺旋升降底座', 'BS', 5, '["螺旋杆","旋转升降","丝杆升降"]', '座面靠螺纹丝杆旋转调节高度，座下可见螺杆结构'),
('six_dim_D', 'BS-雪橇脚底座', '雪橇脚底座', 'BS', 6, '["弓形脚","滑橇脚","钢筋弓架"]', '钢管弯曲成落地滑橇状连贯底架'),
('six_dim_D', 'BS-三脚底座', '三脚底座', 'BS', 7, '["三腿","三角架"]', '三根支腿呈三角分布支撑座面'),
('six_dim_D', 'BS-其他', '其他', 'BS', 8, NULL, '不属于以上任何形态的底座')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × BS 吧椅（6 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'BS-无软包', '无软包', 'BS', 1, '["硬面","光板","素面"]', '座面/靠背为裸露木、金属、塑料或藤面，无织物皮革覆盖'),
('six_dim_F', 'BS-薄垫', '薄垫', 'BS', 2, '["薄软包","平板垫","贴面垫"]', '座面覆一层扁平垫层，厚度薄、轮廓贴合座面'),
('six_dim_F', 'BS-厚软包', '厚软包', 'BS', 3, '["厚垫","饱满软包","面包座"]', '垫层明显隆起饱满，边缘可见鼓胀体量'),
('six_dim_F', 'BS-绗缝', '绗缝', 'BS', 4, '["车线格纹","菱形格","竖条绗缝","拉线"]', '软包表面有规则缝线分割出的格纹/条纹肌理'),
('six_dim_F', 'BS-拉扣', '拉扣', 'BS', 5, '["拉点","纽扣软包","Chesterfield"]', '软包表面有纽扣下陷形成的均匀凹点与放射褶皱'),
('six_dim_F', 'BS-其他', '其他', 'BS', 6, NULL, '不属于以上任何形态的软包')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × OF 办公家具（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'OF-办公椅', '办公椅', 'OF', 1, '["职员椅","转椅","电脑椅","老板椅","会议椅"]', '带靠背与底座的单人居坐家具'),
('six_dim_A', 'OF-职员桌', '职员桌', 'OF', 2, '["员工桌","电脑桌","开放式工位","办公桌"]', '单人/多人开放式条形工作桌，无围合屏风'),
('six_dim_A', 'OF-屏风工位', '屏风工位', 'OF', 3, '["卡位","卡座","隔断工位","屏风桌"]', '桌面被高于台面的屏风板半围合，形成独立格子间'),
('six_dim_A', 'OF-文件柜', '文件柜', 'OF', 4, '["资料柜","书柜","档案柜","铁皮柜","储物柜"]', '以竖向柜体与门/抽屉为主的储物家具，无工作台面'),
('six_dim_A', 'OF-会议桌', '会议桌', 'OF', 5, '["会议台","洽谈长桌"]', '可供多人围坐的长条/圆形大台面桌'),
('six_dim_A', 'OF-班台', '班台', 'OF', 6, '["大班台","老板桌","总裁桌","大班桌"]', '体量大、台面宽厚、带落地侧板与副柜的独立 executive 桌'),
('six_dim_A', 'OF-主管桌', '主管桌', 'OF', 7, '["经理桌","主管台"]', '体量介于班台与职员桌之间，常带小侧柜'),
('six_dim_A', 'OF-培训折叠桌', '培训折叠桌', 'OF', 8, '["折叠桌","条桌","翻板桌","培训桌"]', '桌腿或台面可折叠/翻转，便于收纳拼接'),
('six_dim_A', 'OF-洽谈桌', '洽谈桌', 'OF', 9, '["洽谈台","小圆桌","谈判桌"]', '小尺度圆/方台面，供2~4人近距离对坐'),
('six_dim_A', 'OF-升降桌', '升降桌', 'OF', 10, '["电动升降桌","站立办公桌"]', '可见粗壮双立柱升降腿，台面高度可调'),
('six_dim_A', 'OF-其他', '其他', 'OF', 11, '["前台接待台","讲台"]', '不属于以上任何品类形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × OF 办公家具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'OF-一字台面', '一字台面', 'OF', 1, '["平面台面","矩形台面","直台面"]', '工作面为单一矩形平面，无转角无附加板'),
('six_dim_B', 'OF-L型台面', 'L型台面', 'OF', 2, '["转角台面","L台","拐角台面"]', '工作面由两个方向台面垂直相接成L形'),
('six_dim_B', 'OF-弧形台面', '弧形台面', 'OF', 3, '["鸭嘴边","弧边台","异型台缘"]', '台面前缘呈外凸弧线或波浪形而非直线'),
('six_dim_B', 'OF-带挡板', '带挡板', 'OF', 4, '["前挡板","桌挡","屏风挡板"]', '台面前缘下方或上方有竖直挡板遮挡视线'),
('six_dim_B', 'OF-网布靠背', '网布靠背', 'OF', 5, '["网背","透气网背","全网"]', '椅背为绷紧的半透明网状织物，可透见网格纹理'),
('six_dim_B', 'OF-软包靠背', '软包靠背', 'OF', 6, '["皮背","布背","海绵靠背"]', '椅背为皮革/织物包裹海绵的实心软包面'),
('six_dim_B', 'OF-硬壳靠背', '硬壳靠背', 'OF', 7, '["塑壳背","曲木背","一体壳背"]', '椅背为硬质一体成型壳体，无软包无网面'),
('six_dim_B', 'OF-玻璃柜门', '玻璃柜门', 'OF', 8, '["玻璃门书柜","玻门柜"]', '柜体正面为透明/半透明玻璃门扇，可透视内部'),
('six_dim_B', 'OF-封闭柜门', '封闭柜门', 'OF', 9, '["掩门","铁门","木门","实门柜"]', '柜体正面为不透明实体门扇'),
('six_dim_B', 'OF-其他', '其他', 'OF', 10, NULL, '不属于以上任何工作面/背部形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × OF 办公家具（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'OF-落地侧板', '落地侧板', 'OF', 1, '["侧板","封板","台脚板"]', '桌体两侧为从台面直达地面的整板支撑面'),
('six_dim_C', 'OF-副柜侧柜', '副柜侧柜', 'OF', 2, '["副台","附柜","侧柜","活动侧柜"]', '主台侧面依附连接的低柜/窄台面单元'),
('six_dim_C', 'OF-屏风隔断', '屏风隔断', 'OF', 3, '["屏风板","隔断板","卡位屏风"]', '工位之间/边缘竖立的高于台面的分隔板'),
('six_dim_C', 'OF-扶手', '扶手', 'OF', 4, '["椅扶手","升降扶手","固定扶手"]', '椅座两侧供手臂搭放的杆状/面状结构'),
('six_dim_C', 'OF-椅背侧翼', '椅背侧翼', 'OF', 5, '["侧翼","护翼","包围翼"]', '椅背两侧向前包出的翼状凸起'),
('six_dim_C', 'OF-走线槽盒', '走线槽盒', 'OF', 6, '["线槽","线盒","过线盒","走线孔盖"]', '台面或桌下可见的矩形走线槽/翻盖线盒'),
('six_dim_C', 'OF-横梁拉杆', '横梁拉杆', 'OF', 7, '["桌下横撑","拉杆","连接梁"]', '桌腿之间可见的水平连接杆件'),
('six_dim_C', 'OF-其他', '其他', 'OF', 8, NULL, '不属于以上任何侧部/连接特征')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × OF 办公家具（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'OF-钢架桌腿', '钢架桌腿', 'OF', 1, '["金属桌架","钢架","口字架","钢木腿"]', '金属管材焊接成口字/框形桌架承托台面'),
('six_dim_D', 'OF-板式落地支撑', '板式落地支撑', 'OF', 2, '["落地侧板脚","全落地","板脚"]', '由竖直板材直达地面形成支撑，无独立细腿'),
('six_dim_D', 'OF-实木桌腿', '实木桌腿', 'OF', 3, '["木腿","实木框架脚"]', '木质方/圆腿或木框架支撑'),
('six_dim_D', 'OF-升降立柱腿', '升降立柱腿', 'OF', 4, '["升降柱","电动腿","双柱腿"]', '两条粗壮矩形立柱套筒式桌腿，区别于细管钢架'),
('six_dim_D', 'OF-五星脚底盘', '五星脚底盘', 'OF', 5, '["五爪脚","五星脚","转椅底盘"]', '中心气压柱下伸出五爪支脚并带滚轮'),
('six_dim_D', 'OF-弓形脚', '弓形脚', 'OF', 6, '["悬臂脚","弓字脚","弓架椅"]', '钢管弯成前悬后弓的C/弓形，无后腿'),
('six_dim_D', 'OF-四脚底座', '四脚底座', 'OF', 7, '["四腿","四脚椅腿"]', '四根独立椅腿/桌腿垂直支撑'),
('six_dim_D', 'OF-柜类落地底座', '柜类落地底座', 'OF', 8, '["柜脚","踢脚底","落地柜"]', '柜体直接落地或带矮踢脚/小柜脚'),
('six_dim_D', 'OF-其他', '其他', 'OF', 9, NULL, '不属于以上任何支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × OF 办公家具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'OF-无功能件', '无功能件', 'OF', 1, '["素体","无附件"]', '整桌/整椅无任何附加功能构件'),
('six_dim_F', 'OF-抽屉', '抽屉', 'OF', 2, '["吊抽","桌斗","斗抽"]', '台面下方或柜体正面可见抽屉面板与拉手'),
('six_dim_F', 'OF-升降机构', '升降机构', 'OF', 3, '["气压杆","升降柱","手摇升降"]', '可见气压杆手柄/电动柱/摇把等调高构件'),
('six_dim_F', 'OF-头枕', '头枕', 'OF', 4, '["头靠","颈枕","可调节头枕"]', '椅背顶部凸出的独立小枕面'),
('six_dim_F', 'OF-腰靠', '腰靠', 'OF', 5, '["腰托","腰枕","撑腰"]', '椅背腰部位置独立凸起或可调的支撑件'),
('six_dim_F', 'OF-脚踏', '脚踏', 'OF', 6, '["搁脚","脚托","躺舒宝"]', '椅座前方可抽出/翻出的腿部承托板'),
('six_dim_F', 'OF-活动柜', '活动柜', 'OF', 7, '["移动推柜","三抽柜","桌下柜"]', '桌下带轮可移动的独立小柜'),
('six_dim_F', 'OF-键盘架', '键盘架', 'OF', 8, '["键盘托","键盘抽"]', '台面下方可抽拉的窄托板'),
('six_dim_F', 'OF-软包坐垫', '软包坐垫', 'OF', 9, '["海绵坐垫","厚坐垫"]', '椅座为明显鼓起的软包垫层（与 B 软包靠背区分：一座一背）'),
('six_dim_F', 'OF-其他', '其他', 'OF', 10, '["杯架","书报架"]', '不属于以上任何功能件')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × LT 灯具（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'LT-球形', '球形', 'LT', 1, '["圆球灯","球泡灯","球体灯"]', '灯体主体为正圆球/半球形发光体'),
('six_dim_A', 'LT-圆盘形', '圆盘形', 'LT', 2, '["飞碟灯","碟形","平板灯","面板灯"]', '灯体为扁平圆盘/薄板状，厚度远小于直径'),
('six_dim_A', 'LT-长条形', '长条形', 'LT', 3, '["线性灯","一字灯","长条吊灯","灯管形"]', '灯体呈细长直线状，长度远大于宽度'),
('six_dim_A', 'LT-环形', '环形', 'LT', 4, '["圈圈灯","圆环灯","光环灯"]', '灯体为一个或多个同心发光圆环'),
('six_dim_A', 'LT-枝形', '枝形', 'LT', 5, '["枝形吊灯","多臂吊灯","烛台吊灯"]', '中心灯柱向外分出多个弯曲灯臂，每臂一端灯'),
('six_dim_A', 'LT-伞形', '伞形', 'LT', 6, '["斗笠灯","草帽灯","伞盖灯"]', '灯罩呈上小下大的伞盖/斗笠轮廓，光向下投'),
('six_dim_A', 'LT-蘑菇形', '蘑菇形', 'LT', 7, '["蘑菇灯"]', '半球伞盖+圆柱灯梗构成蘑菇剪影'),
('six_dim_A', 'LT-分子式', '分子式', 'LT', 8, '["分子灯","魔豆灯","多头球灯","卫星灯"]', '主杆上不规则伸展多支杆，各端带球形灯头'),
('six_dim_A', 'LT-柱形', '柱形', 'LT', 9, '["筒形灯","圆柱灯","直筒灯"]', '灯体为直立圆柱/方柱筒状'),
('six_dim_A', 'LT-笼形', '笼形', 'LT', 10, '["鸟笼灯","铁笼灯","笼式灯"]', '光源被金属条/藤条编织的笼状外壳包围'),
('six_dim_A', 'LT-仿生异形', '仿生异形', 'LT', 11, '["花形灯","云朵灯","艺术造型灯"]', '灯体模拟花朵/云/动植物或呈不规则艺术形态'),
('six_dim_A', 'LT-其他', '其他', 'LT', 12, NULL, '不属于以上任何灯体造型')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'LT-玻璃罩', '玻璃罩', 'LT', 1, '["玻璃灯罩","磨砂玻璃","奶白玻璃罩"]', '透明/磨砂/乳白色玻璃罩体，可见玻璃质感与透光'),
('six_dim_B', 'LT-亚克力罩', '亚克力罩', 'LT', 2, '["亚克力灯罩","导光板","PS罩"]', '白色半透明塑料质感罩体，边缘可见均匀导光面'),
('six_dim_B', 'LT-金属罩', '金属罩', 'LT', 3, '["铁艺罩","金属灯罩","铝罩"]', '不透明金属罩壳，光仅从开口定向射出'),
('six_dim_B', 'LT-布艺罩', '布艺罩', 'LT', 4, '["布罩","麻布灯罩","棉麻罩"]', '织物绷面罩体，可见布纹肌理、边缘包边'),
('six_dim_B', 'LT-无罩裸光源', '无罩裸光源', 'LT', 5, '["裸灯泡","爱迪生灯泡","灯带一体","灯盘"]', '光源（灯泡/灯带/灯珠板）完全外露无罩体遮挡'),
('six_dim_B', 'LT-褶皱罩', '褶皱罩', 'LT', 6, '["百褶灯罩","折纸罩","风琴罩"]', '罩面呈规则放射状打褶的风琴式立体纹理'),
('six_dim_B', 'LT-藤竹编织罩', '藤竹编织罩', 'LT', 7, '["藤编灯罩","竹编罩","草编罩"]', '罩体由藤/竹/草条编织而成，可见编织孔洞与漏光'),
('six_dim_B', 'LT-纸质罩', '纸质罩', 'LT', 8, '["纸灯","和纸灯","羊皮纸罩"]', '纸质半透明罩体，质感轻盈哑光'),
('six_dim_B', 'LT-水晶罩串', '水晶罩串', 'LT', 9, '["水晶灯罩","水晶珠串罩","玻璃串珠"]', '出光部位由水晶/玻璃珠串或切割水晶件构成（看罩体本身）'),
('six_dim_B', 'LT-其他', '其他', 'LT', 10, '["陶瓷罩"]', '不属于以上任何罩体/出光形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'LT-吊线吊杆', '吊线吊杆', 'LT', 1, '["吊线","吊杆","直吊","吊链杆"]', '灯体由单根/多根竖直线缆或刚性杆从顶面垂吊'),
('six_dim_C', 'LT-无臂直连', '无臂直连', 'LT', 2, '["直连","贴装","一体化"]', '灯体直接贴合安装面，中间无任何杆臂结构'),
('six_dim_C', 'LT-多枝灯臂', '多枝灯臂', 'LT', 3, '["分叉灯臂","曲臂","枝臂"]', '多根弯曲/伸展灯臂从中心向外连接各灯头'),
('six_dim_C', 'LT-直杆立杆', '直杆立杆', 'LT', 4, '["灯柱","直杆","落地杆"]', '底座向上延伸一根竖直长杆支撑顶部灯头'),
('six_dim_C', 'LT-弧形悬臂', '弧形悬臂', 'LT', 5, '["钓鱼竿","抛物线臂","钓鱼灯臂"]', '长臂呈大跨度抛物线弧，灯头悬垂于弧端'),
('six_dim_C', 'LT-折叠摇臂', '折叠摇臂', 'LT', 6, '["长臂折叠","摇臂","机械臂","弹簧臂"]', '由关节连接的两段以上杆件，可明显弯折调节'),
('six_dim_C', 'LT-链条悬挂', '链条悬挂', 'LT', 7, '["吊链","链吊"]', '灯体由可见金属链条垂挂'),
('six_dim_C', 'LT-轨道连接', '轨道连接', 'LT', 8, '["轨道","滑轨","磁吸连接"]', '灯头插接/吸附在条形轨道上，可沿轨移动'),
('six_dim_C', 'LT-鹅颈软管', '鹅颈软管', 'LT', 9, '["鹅颈管","万向软管","蛇管"]', '连接段为可任意弯曲定型的金属软管'),
('six_dim_C', 'LT-其他', '其他', 'LT', 10, NULL, '不属于以上任何连接形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × LT 灯具（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'LT-吊挂式', '吊挂式', 'LT', 1, '["吊装","悬吊","吊灯"]', '整体悬垂于天花板下方，与顶面间有明显空间'),
('six_dim_D', 'LT-吸顶式', '吸顶式', 'LT', 2, '["吸顶灯","贴顶","半吸顶"]', '灯体紧贴天花板安装，无悬垂间隙'),
('six_dim_D', 'LT-桌面台式', '桌面台式', 'LT', 3, '["台灯","桌灯","床头灯"]', '带自重底座放置于桌面，体量小可移动'),
('six_dim_D', 'LT-落地立式', '落地立式', 'LT', 4, '["落地灯","立式灯","地灯"]', '带落地底座（圆盘/大理石/三脚架），整体立于地面'),
('six_dim_D', 'LT-壁挂式', '壁挂式', 'LT', 5, '["壁灯","挂墙灯"]', '灯体固定于墙面，向墙外或上下出光'),
('six_dim_D', 'LT-嵌入式', '嵌入式', 'LT', 6, '["暗装","筒灯","射灯","嵌入灯"]', '灯体埋入吊顶/墙体，仅露出发光面或边框'),
('six_dim_D', 'LT-轨道磁吸式', '轨道磁吸式', 'LT', 7, '["轨道灯","磁吸轨道灯","滑轨灯","无主灯"]', '天花上可见条形轨道及轨道上多个灯头模组（磁吸与普通轨道肉眼难分，合并）'),
('six_dim_D', 'LT-夹持式', '夹持式', 'LT', 8, '["夹子灯","夹灯"]', '以弹簧夹/蟹钳夹固定在桌沿或床头'),
('six_dim_D', 'LT-其他', '其他', 'LT', 9, '["手提便携灯"]', '不属于以上任何安装形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'LT-无装饰', '无装饰', 'LT', 1, '["素面","极简","光身"]', '灯体表面无任何附加装饰构件'),
('six_dim_F', 'LT-水晶挂饰', '水晶挂饰', 'LT', 2, '["水晶吊坠","水晶条","珠帘"]', '灯架下垂挂水晶/玻璃坠子（看挂件，区别于 B 水晶罩串）'),
('six_dim_F', 'LT-金属饰件', '金属饰件', 'LT', 3, '["黄铜件","金属包边","铆钉"]', '灯体上有装饰性金属配件/包边/铆钉点缀'),
('six_dim_F', 'LT-镂空雕花', '镂空雕花', 'LT', 4, '["镂空花纹","雕花投影","冲花"]', '灯罩/灯体上有镂空花纹，透光形成图案投影'),
('six_dim_F', 'LT-流苏穗边', '流苏穗边', 'LT', 5, '["流苏","穗子","草裙摆"]', '罩体下缘垂挂成排流苏/穗条，形似草裙'),
('six_dim_F', 'LT-彩色玻璃', '彩色玻璃', 'LT', 6, '["蒂芙尼","彩玻","镶嵌玻璃"]', '出光面由多色玻璃拼嵌成图案'),
('six_dim_F', 'LT-绳编缠绕', '绳编缠绕', 'LT', 7, '["麻绳灯","绳艺","缠绳"]', '灯体/吊杆上有麻绳或织绳缠绕编织装饰'),
('six_dim_F', 'LT-羽毛装饰', '羽毛装饰', 'LT', 8, '["羽毛灯","羽饰"]', '灯体周圈环绕蓬松羽毛层'),
('six_dim_F', 'LT-木珠串饰', '木珠串饰', 'LT', 9, '["木珠灯","串珠"]', '灯体由木质圆珠成串垂挂构成装饰层'),
('six_dim_F', 'LT-其他', '其他', 'LT', 10, '["陶瓷花饰"]', '不属于以上任何装饰元素')
ON CONFLICT (dict_type, dict_code) DO NOTHING;
-- 六维 A 维度 × DK 书桌/写字台（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'DK-一字矩形', '一字矩形', 'DK', 1, '["直桌","写字台"]', '俯视为单一直线矩形工作面，无转角延伸'),
('six_dim_A', 'DK-L型转角', 'L型转角', 'DK', 2, '["L桌","拐角书桌"]', '两段桌面垂直或近垂直相接形成转角'),
('six_dim_A', 'DK-双人长桌', '双人长桌', 'DK', 3, '["双人书桌","双工位"]', '桌面明显拉长，可同时布置两个工作位'),
('six_dim_A', 'DK-弧形/曲线', '弧形/曲线', 'DK', 4, '["弧形书桌","曲线桌"]', '桌面外轮廓以连续弧线或曲线构成'),
('six_dim_A', 'DK-窄长壁靠', '窄长壁靠', 'DK', 5, '["窄桌","靠墙桌"]', '桌面深度较小且长边贴墙布置'),
('six_dim_A', 'DK-上架一体', '上架一体', 'DK', 6, '["书架桌","带书架书桌"]', '桌面上方有与桌体连成整体的置物架'),
('six_dim_A', 'DK-柜桌一体', '柜桌一体', 'DK', 7, '["书柜书桌一体","侧柜桌"]', '高柜或侧柜与桌面构成不可分的整体'),
('six_dim_A', 'DK-模块组合', '模块组合', 'DK', 8, '["组合书桌","模块桌"]', '多个可辨识桌、柜或架模块拼接'),
('six_dim_A', 'DK-异形/其他', '异形/其他', 'DK', 9, '["艺术书桌","不规则桌"]', '无法归入以上布局的不规则轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × DK 书桌/写字台（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'DK-平板薄面', '平板薄面', 'DK', 1, '["薄台面","极简桌面"]', '侧视桌面为单层薄板，边缘轻薄'),
('six_dim_B', 'DK-厚台面', '厚台面', 'DK', 2, '["厚板桌面","大板桌"]', '侧视台面厚度明显，具有块体感'),
('six_dim_B', 'DK-悬浮台面', '悬浮台面', 'DK', 3, '["漂浮桌面","悬空桌面"]', '台面与下部支撑间有内收或明显缝隙'),
('six_dim_B', 'DK-内嵌/包框台面', '内嵌/包框台面', 'DK', 4, '["嵌板桌面","包边桌面"]', '工作面嵌在可见外框中或被框体包围'),
('six_dim_B', 'DK-双层台面', '双层台面', 'DK', 5, '["上下双层","夹层桌面"]', '可见两层平行台面及中间空隙'),
('six_dim_B', 'DK-抬高层台面', '抬高层台面', 'DK', 6, '["显示器架","抬高层"]', '主桌面上方有局部抬高的第二层板'),
('six_dim_B', 'DK-翻折台面', '翻折台面', 'DK', 7, '["翻板桌","可折桌面"]', '台面有明显铰链、折痕或翻转结构'),
('six_dim_B', 'DK-拼接台面', '拼接台面', 'DK', 8, '["组合桌面","拼板桌面"]', '台面由多块材料或色块拼接，接缝可见'),
('six_dim_B', 'DK-异形/其他', '异形/其他', 'DK', 9, '["不规则台面","艺术桌面"]', '无法归入以上构造的特殊台面')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × DK 书桌/写字台（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'DK-无明显侧件', '无明显侧件', 'DK', 1, '["无侧板","光身桌"]', '桌面侧部无柜箱、挡板或上架连接件'),
('six_dim_C', 'DK-落地侧板', '落地侧板', 'DK', 2, '["板式侧板","封闭侧板"]', '台面一侧或两侧有直达地面的整板'),
('six_dim_C', 'DK-侧柜/抽屉箱', '侧柜/抽屉箱', 'DK', 3, '["抽屉柜","书桌侧柜"]', '桌面侧下方可见抽屉箱或储物柜体'),
('six_dim_C', 'DK-前挡板', '前挡板', 'DK', 4, '["桌挡","遮腿板"]', '桌面前下方有竖向板面遮挡视线'),
('six_dim_C', 'DK-桌面屏风', '桌面屏风', 'DK', 5, '["桌上隔断","桌屏"]', '台面上方竖立用于分隔的屏风板'),
('six_dim_C', 'DK-走线孔/线盒', '走线孔/线盒', 'DK', 6, '["过线孔","桌面线盒"]', '台面可见圆形过线孔或翻盖式线盒'),
('six_dim_C', 'DK-上架连接', '上架连接', 'DK', 7, '["桌架连接","上置书架"]', '侧部立板或竖杆向上支撑书架'),
('six_dim_C', 'DK-横梁拉杆', '横梁拉杆', 'DK', 8, '["桌下横撑","连接梁"]', '桌腿或侧板之间有可见水平拉杆'),
('six_dim_C', 'DK-异形/其他', '异形/其他', 'DK', 9, '["特殊连接","不规则侧件"]', '无法归入以上类型的侧部结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × DK 书桌/写字台（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'DK-四直腿', '四直腿', 'DK', 1, '["四脚桌","直桌腿"]', '桌面下方四角分别由垂直腿支撑'),
('six_dim_D', 'DK-外八/斜腿', '外八/斜腿', 'DK', 2, '["八字腿","A字腿"]', '桌腿自上而下向外倾斜张开'),
('six_dim_D', 'DK-U形/口字框架', 'U形/口字框架', 'DK', 3, '["U形腿","口字桌腿"]', '两侧由金属或木质封闭框架承托'),
('six_dim_D', 'DK-T形底座', 'T形底座', 'DK', 4, '["T字腿","一字横脚"]', '竖向支柱底部连接水平横脚形成T形'),
('six_dim_D', 'DK-板式落地侧板', '板式落地侧板', 'DK', 5, '["板脚","全落地侧板"]', '以宽板从台面直接延伸至地面承重'),
('six_dim_D', 'DK-单侧柜支撑', '单侧柜支撑', 'DK', 6, '["单边柜脚","一侧柜体"]', '一侧以柜体承重，另一侧为桌腿或侧板'),
('six_dim_D', 'DK-双侧柜支撑', '双侧柜支撑', 'DK', 7, '["双柜脚","两侧柜体"]', '桌面两侧均由落地柜体承重'),
('six_dim_D', 'DK-悬浮/壁挂', '悬浮/壁挂', 'DK', 8, '["壁挂书桌","悬空桌"]', '桌面固定于墙体且下方无落地支撑'),
('six_dim_D', 'DK-升降立柱', '升降立柱', 'DK', 9, '["电动升降腿","套筒立柱"]', '可见粗壮套筒式立柱和升降框架'),
('six_dim_D', 'DK-脚轮移动', '脚轮移动', 'DK', 10, '["带轮书桌","移动桌"]', '支撑末端可见脚轮或万向轮'),
('six_dim_D', 'DK-异形/其他', '异形/其他', 'DK', 11, '["艺术底座","特殊桌腿"]', '无法归入以上类型的支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × DK 书桌/写字台（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'DK-无功能件', '无功能件', 'DK', 1, '["纯台面","固定式"]', '除基本桌面和支撑外无可见功能构件'),
('six_dim_F', 'DK-抽屉', '抽屉', 'DK', 2, '["带抽书桌","桌斗"]', '台面下方可见可抽拉的抽屉面板'),
('six_dim_F', 'DK-侧柜', '侧柜', 'DK', 3, '["桌边柜","副柜"]', '主桌一侧带门板或抽屉的储物柜'),
('six_dim_F', 'DK-开放格', '开放格', 'DK', 4, '["置物格","开放层板"]', '桌体上或下方有无门开放收纳格'),
('six_dim_F', 'DK-上置书架', '上置书架', 'DK', 5, '["桌上书架","上架"]', '桌面上方连接多层书架或层板'),
('six_dim_F', 'DK-走线管理', '走线管理', 'DK', 6, '["理线架","过线系统"]', '可见线盒、线槽、穿线孔或桌下理线架'),
('six_dim_F', 'DK-升降', '升降', 'DK', 7, '["电动升降","手摇升降"]', '可见升降立柱、控制器或手摇机构'),
('six_dim_F', 'DK-折叠', '折叠', 'DK', 8, '["折叠书桌","收折桌"]', '桌腿或桌面具有可见铰链与收折节点'),
('six_dim_F', 'DK-插座/USB/无线充电', '插座/USB/无线充电', 'DK', 9, '["桌面插座","无线充"]', '台面可见电源、USB接口或无线充电标识'),
('six_dim_F', 'DK-键盘托', '键盘托', 'DK', 10, '["键盘架","抽拉键盘板"]', '台面下方有窄长可抽拉托板'),
('six_dim_F', 'DK-脚轮', '脚轮', 'DK', 11, '["万向轮","移动轮"]', '桌体底部可见用于移动的轮体'),
('six_dim_F', 'DK-异形/其他', '异形/其他', 'DK', 12, '["其他桌体功能","特殊功能"]', '无法归入以上类型的可见功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × MT 床垫（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'MT-薄垫型', '薄垫型', 'MT', 1, '["薄垫","榻榻米垫"]', '整体厚度较小，侧视呈薄片状'),
('six_dim_A', 'MT-标准厚度', '标准厚度', 'MT', 2, '["常规床垫","标准床垫"]', '整体为常规单层矩形厚度，无额外顶层'),
('six_dim_A', 'MT-厚垫型', '厚垫型', 'MT', 3, '["厚床垫","加厚床垫"]', '侧围高度明显较大，呈厚重块体'),
('six_dim_A', 'MT-双层/加厚舒适层', '双层/加厚舒适层', 'MT', 4, '["双层床垫","舒适层"]', '侧视可见上下两层或加厚顶层分界'),
('six_dim_A', 'MT-枕顶型', '枕顶型', 'MT', 5, '["pillow top","欧式枕顶"]', '床垫顶部有一层边缘独立鼓起的枕形舒适层'),
('six_dim_A', 'MT-折叠型', '折叠型', 'MT', 6, '["三折垫","折叠床垫"]', '垫体有明显横向折线并可分段叠合'),
('six_dim_A', 'MT-分体组合型', '分体组合型', 'MT', 7, '["组合床垫","分体垫"]', '由两块以上独立垫体并置或叠置组成'),
('six_dim_A', 'MT-异形/其他', '异形/其他', 'MT', 8, '["不规则床垫","特殊垫体"]', '无法归入以上厚度轮廓的特殊形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × MT 床垫（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'MT-光面', '光面', 'MT', 1, '["素面顶层","无绗缝"]', '顶面平整，无明显装饰性缝线或分区'),
('six_dim_B', 'MT-菱形绗缝', '菱形绗缝', 'MT', 2, '["钻石格","菱格绗缝"]', '顶面缝线交叉形成连续菱形网格'),
('six_dim_B', 'MT-方格绗缝', '方格绗缝', 'MT', 3, '["棋盘格","方格车线"]', '顶面经纬缝线形成规则方格'),
('six_dim_B', 'MT-横条绗缝', '横条绗缝', 'MT', 4, '["横向车线","条纹绗缝"]', '顶面以平行横向缝线分区'),
('six_dim_B', 'MT-波浪绗缝', '波浪绗缝', 'MT', 5, '["水波纹","曲线绗缝"]', '顶面缝线呈连续波浪或曲线'),
('six_dim_B', 'MT-深拉点/拉扣', '深拉点/拉扣', 'MT', 6, '["拉点","纽扣绗缝"]', '顶面有规则深凹点及放射褶皱'),
('six_dim_B', 'MT-立体花纹针织', '立体花纹针织', 'MT', 7, '["针织花纹","提花面"]', '面料本身具有凸起针织或提花纹理'),
('six_dim_B', 'MT-分区纹理', '分区纹理', 'MT', 8, '["七区纹理","功能分区"]', '顶面用不同缝线或纹理明确分成多个区域'),
('six_dim_B', 'MT-异形/其他', '异形/其他', 'MT', 9, '["特殊绗缝","不规则表层"]', '无法归入以上类型的顶面纹理')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × MT 床垫（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'MT-直边', '直边', 'MT', 1, '["平直围边","素边"]', '顶面与侧围以平直缝线收边'),
('six_dim_C', 'MT-滚边/包边', '滚边/包边', 'MT', 2, '["围边","嵌线包边"]', '上下边缘有连续凸起滚条'),
('six_dim_C', 'MT-双色撞色包边', '双色撞色包边', 'MT', 3, '["撞色滚边","双色围边"]', '包边颜色与顶面或侧围形成明显反差'),
('six_dim_C', 'MT-加厚围边', '加厚围边', 'MT', 4, '["宽围边","厚边条"]', '边缘有宽厚的加固包边带'),
('six_dim_C', 'MT-圆角包边', '圆角包边', 'MT', 5, '["R角围边","圆弧边"]', '床垫四角圆滑过渡，包边沿圆角连续延伸'),
('six_dim_C', 'MT-双层围边', '双层围边', 'MT', 6, '["双道滚边","双线包边"]', '边缘可见上下两道平行围边'),
('six_dim_C', 'MT-异形/其他', '异形/其他', 'MT', 7, '["特殊围边","不规则收边"]', '无法归入以上类型的边缘工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × MT 床垫（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'MT-素面侧围', '素面侧围', 'MT', 1, '["光面侧边","无装饰侧围"]', '侧围为单色平整面，无明显缝线或功能件'),
('six_dim_D', 'MT-绗缝侧围', '绗缝侧围', 'MT', 2, '["侧边车线","绗缝围边"]', '侧围可见规则格纹或条纹缝线'),
('six_dim_D', 'MT-透气网侧围', '透气网侧围', 'MT', 3, '["透气网布","3D网围边"]', '侧围有明显网眼或网格透气带'),
('six_dim_D', 'MT-分段拼接侧围', '分段拼接侧围', 'MT', 4, '["拼色侧围","分区侧边"]', '侧围由不同材料、颜色或纹理分段拼接'),
('six_dim_D', 'MT-带提手', '带提手', 'MT', 5, '["搬运提手","侧提带"]', '侧围可见缝制或固定的环形提手'),
('six_dim_D', 'MT-带拉链外套', '带拉链外套', 'MT', 6, '["可拆拉链","拉链套"]', '侧围或底边可见连续拉链轨迹'),
('six_dim_D', 'MT-加固护边', '加固护边', 'MT', 7, '["护边条","围护加固"]', '侧围边缘有额外加宽或加厚的支撑带'),
('six_dim_D', 'MT-异形/其他', '异形/其他', 'MT', 8, '["特殊侧围","不规则侧边"]', '无法归入以上类型的侧围结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × MT 床垫（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'MT-无明显功能', '无明显功能', 'MT', 1, '["常规床垫","无附加功能"]', '外观与文字中无法确认其他功能结构'),
('six_dim_F', 'MT-可拆洗外套', '可拆洗外套', 'MT', 2, '["可脱卸外套","可洗床垫套"]', '可见环绕拉链或文字明确说明外套可拆洗'),
('six_dim_F', 'MT-可折叠', '可折叠', 'MT', 3, '["三折","收折垫"]', '垫体有可折叠分段和明显软性铰接线'),
('six_dim_F', 'MT-双面可用', '双面可用', 'MT', 4, '["双面睡","两面用"]', '只在图文明确展示两面不同或标注双面可用时判定'),
('six_dim_F', 'MT-独立舒适层/薄垫组合', '独立舒适层/薄垫组合', 'MT', 5, '["子母垫","独立顶垫"]', '主床垫上方有可分离的独立薄垫层'),
('six_dim_F', 'MT-分区标识', '分区标识', 'MT', 6, '["七区承托","分区线"]', '顶面图形或文字明确标出不同承托区'),
('six_dim_F', 'MT-防滑底面', '防滑底面', 'MT', 7, '["防滑布","防移位底"]', '底面可见防滑胶点、粗糙纹理或文字标注'),
('six_dim_F', 'MT-卷包/压缩包装', '卷包/压缩包装', 'MT', 8, '["卷装床垫","压缩床垫"]', '图文明确展示床垫卷曲或真空压缩包装'),
('six_dim_F', 'MT-异形/其他', '异形/其他', 'MT', 9, '["其他床垫功能","特殊功能"]', '只记录图片或明确商品文字可确认的其他功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × MR 镜子（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'MR-圆形', '圆形', 'MR', 1, '["圆镜","正圆镜"]', '镜面外轮廓为正圆或近圆'),
('six_dim_A', 'MR-椭圆形', '椭圆形', 'MR', 2, '["椭圆镜","蛋形镜"]', '镜面呈规则椭圆轮廓'),
('six_dim_A', 'MR-矩形', '矩形', 'MR', 3, '["长方镜","方镜"]', '镜面为直边四边形，长宽可不等'),
('six_dim_A', 'MR-拱形', '拱形', 'MR', 4, '["拱门镜","圆拱镜"]', '下部为直边，顶部呈半圆或拱门形'),
('six_dim_A', 'MR-跑道形', '跑道形', 'MR', 5, '["胶囊镜","长圆镜"]', '两端半圆与中间直边构成拉长轮廓'),
('six_dim_A', 'MR-水滴形', '水滴形', 'MR', 6, '["滴水镜","梨形镜"]', '轮廓一端收尖、另一端圆润'),
('six_dim_A', 'MR-不规则有机形', '不规则有机形', 'MR', 7, '["异形镜","云朵镜"]', '镜面边界为非对称自由曲线'),
('six_dim_A', 'MR-全身长镜', '全身长镜', 'MR', 8, '["穿衣镜","落地长镜"]', '镜面高度明显大于宽度，可映照全身'),
('six_dim_A', 'MR-多片组合', '多片组合', 'MR', 9, '["拼镜","组合装饰镜"]', '由两片以上独立镜面拼组成整体'),
('six_dim_A', 'MR-异形/其他', '异形/其他', 'MR', 10, '["艺术镜","特殊轮廓镜"]', '无法归入以上类型的特殊镜面轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × MR 镜子（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'MR-无框', '无框', 'MR', 1, '["裸镜","无边框镜"]', '镜面边缘无可见独立边框'),
('six_dim_B', 'MR-超窄框', '超窄框', 'MR', 2, '["极窄框","线框镜"]', '边框宽度极小，正视仅呈细线'),
('six_dim_B', 'MR-标准细框', '标准细框', 'MR', 3, '["细边框","常规框"]', '边框清晰可见但不占据大面积'),
('six_dim_B', 'MR-宽框', '宽框', 'MR', 4, '["粗框","厚边框"]', '边框宽度明显，形成较大装饰面'),
('six_dim_B', 'MR-双层框', '双层框', 'MR', 5, '["内外双框","双圈框"]', '镜面外周可见两道分离或叠层框线'),
('six_dim_B', 'MR-包覆软框', '包覆软框', 'MR', 6, '["软包镜框","皮革包框"]', '边框表面有织物或皮革包覆的软质感'),
('six_dim_B', 'MR-雕塑异形框', '雕塑异形框', 'MR', 7, '["造型镜框","雕塑框"]', '边框以不规则立体块面或雕塑形态构成'),
('six_dim_B', 'MR-装饰雕花框', '装饰雕花框', 'MR', 8, '["雕花镜框","花纹框"]', '边框表面有清晰浮雕、雕花或古典纹样'),
('six_dim_B', 'MR-异形/其他', '异形/其他', 'MR', 9, '["特殊镜框","其他框型"]', '无法归入以上类型的边框形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × MR 镜子（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'MR-平面直边', '平面直边', 'MR', 1, '["平镜","直切边"]', '镜面平整，边缘无可见斜面或分割'),
('six_dim_C', 'MR-磨边', '磨边', 'MR', 2, '["精磨边","抛光边"]', '镜面边缘可见窄小均匀的磨光带'),
('six_dim_C', 'MR-斜边/车边', '斜边/车边', 'MR', 3, '["斜切镜","车边镜"]', '镜面四周有明显斜面反光边'),
('six_dim_C', 'MR-圆角', '圆角', 'MR', 4, '["R角镜","圆角边"]', '镜面角部以圆弧过渡而非尖角'),
('six_dim_C', 'MR-烟熏/灰镜', '烟熏/灰镜', 'MR', 5, '["灰镜","烟熏镜"]', '镜面整体呈灰色或烟熏色反射'),
('six_dim_C', 'MR-茶色镜', '茶色镜', 'MR', 6, '["茶镜","棕镜"]', '镜面整体呈茶棕色反射'),
('six_dim_C', 'MR-分割拼镜', '分割拼镜', 'MR', 7, '["分格镜","几何拼镜"]', '镜面由多块平面镜以可见接缝拼接'),
('six_dim_C', 'MR-波纹/艺术镜面', '波纹/艺术镜面', 'MR', 8, '["波浪镜","纹理镜"]', '镜面本身有起伏、波纹或故意变形反射'),
('six_dim_C', 'MR-异形/其他', '异形/其他', 'MR', 9, '["特殊镜面工艺","其他边缘"]', '无法归入以上类型的镜面或边缘工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × MR 镜子（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'MR-壁挂', '壁挂', 'MR', 1, '["挂墙镜","墙镜"]', '镜体贴墙固定，不触地且无独立支架'),
('six_dim_D', 'MR-落地斜靠', '落地斜靠', 'MR', 2, '["斜靠镜","靠墙镜"]', '长镜底部落地，上部直接斜靠墙面'),
('six_dim_D', 'MR-落地自立支架', '落地自立支架', 'MR', 3, '["支架穿衣镜","落地镜架"]', '镜后或两侧有独立支架使其自立'),
('six_dim_D', 'MR-台面摆放', '台面摆放', 'MR', 4, '["台镜","桌面镜"]', '小尺寸镜体通过底座放置在台面'),
('six_dim_D', 'MR-旋转支架', '旋转支架', 'MR', 5, '["翻转镜","旋转台镜"]', '镜体两侧或中轴可见转轴支撑'),
('six_dim_D', 'MR-吊带悬挂', '吊带悬挂', 'MR', 6, '["皮带挂镜","绳挂镜"]', '镜体上方以皮带、绳或链条悬挂'),
('six_dim_D', 'MR-柜体一体', '柜体一体', 'MR', 7, '["镜柜一体","柜门镜"]', '镜面与柜门、收纳柜或梳妆台连成整体'),
('six_dim_D', 'MR-嵌墙', '嵌墙', 'MR', 8, '["嵌入式镜","入墙镜"]', '镜面边缘与墙面平齐或嵌入墙体槽内'),
('six_dim_D', 'MR-异形/其他', '异形/其他', 'MR', 9, '["特殊安装镜","其他支撑"]', '无法归入以上类型的安装或支撑方式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × MR 镜子（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'MR-无功能', '无功能', 'MR', 1, '["普通镜","纯镜面"]', '除反射成像外无其他可见功能构件'),
('six_dim_F', 'MR-环形灯带', '环形灯带', 'MR', 2, '["环形发光","一圈灯带"]', '镜面周边有连续环形发光带'),
('six_dim_F', 'MR-背光', '背光', 'MR', 3, '["镜后灯","背面发光"]', '光线从镜体背后投向墙面形成光晕'),
('six_dim_F', 'MR-前置灯', '前置灯', 'MR', 4, '["镜前灯","正面灯条"]', '镜体正面或边框上有独立灯头或灯条'),
('six_dim_F', 'MR-储物', '储物', 'MR', 5, '["镜柜","带置物架镜"]', '镜后或侧部有柜体、层板或置物格'),
('six_dim_F', 'MR-可调角度', '可调角度', 'MR', 6, '["俯仰调节","角度可调"]', '可见转轴、紧固旋钮或多档俯仰结构'),
('six_dim_F', 'MR-双面旋转', '双面旋转', 'MR', 7, '["双面镜","翻转双面镜"]', '镜体可绕轴翻转且两面均为镜面'),
('six_dim_F', 'MR-放大镜', '放大镜', 'MR', 8, '["倍率镜","局部放大镜"]', '主镜上或侧部有独立小型放大镜'),
('six_dim_F', 'MR-智能显示', '智能显示', 'MR', 9, '["智能镜","时钟温度显示"]', '镜面可见数字、图标或信息显示区'),
('six_dim_F', 'MR-异形/其他', '异形/其他', 'MR', 10, '["其他镜面功能","特殊功能镜"]', '无法归入以上类型的可见附加功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × RG 地毯（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'RG-矩形', '矩形', 'RG', 1, '["长方形地毯","方边毯"]', '外轮廓为四条直边的长方形'),
('six_dim_A', 'RG-方形', '方形', 'RG', 2, '["正方形地毯","方毯"]', '外轮廓为四边近等长的正方形'),
('six_dim_A', 'RG-圆形', '圆形', 'RG', 3, '["圆毯","正圆地毯"]', '外轮廓为正圆或近圆'),
('six_dim_A', 'RG-椭圆形', '椭圆形', 'RG', 4, '["椭圆毯","蛋形地毯"]', '外轮廓为规则椭圆'),
('six_dim_A', 'RG-长条跑道/走廊毯', '长条跑道/走廊毯', 'RG', 5, '["走廊毯","长条毯"]', '轮廓狭长，长度明显大于宽度'),
('six_dim_A', 'RG-不规则有机形', '不规则有机形', 'RG', 6, '["异形地毯","有机形毯"]', '边界为非对称自由曲线'),
('six_dim_A', 'RG-拼接模块', '拼接模块', 'RG', 7, '["方块毯","模块地毯"]', '由多块边界清晰的小毯块拼接'),
('six_dim_A', 'RG-异形/其他', '异形/其他', 'RG', 8, '["特殊轮廓毯","其他形状地毯"]', '无法归入以上类型的特殊轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × RG 地毯（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'RG-纯色', '纯色', 'RG', 1, '["素色毯","无图案"]', '毯面整体为单一色相或近似色调'),
('six_dim_B', 'RG-几何', '几何', 'RG', 2, '["几何图案","方圆纹样"]', '由直线、圆形、多边形等规则图形构成'),
('six_dim_B', 'RG-条纹', '条纹', 'RG', 3, '["横条毯","竖条毯"]', '毯面以平行或交叉条带为主'),
('six_dim_B', 'RG-抽象', '抽象', 'RG', 4, '["抽象画毯","抽象纹样"]', '图案为非具象的自由色彩与形状组合'),
('six_dim_B', 'RG-色块拼接', '色块拼接', 'RG', 5, '["拼色毯","色块毯"]', '多个边界清晰的大面积色块拼组'),
('six_dim_B', 'RG-渐变', '渐变', 'RG', 6, '["渐变毯","晕染毯"]', '颜色明度或色相在毯面连续过渡'),
('six_dim_B', 'RG-植物/花卉', '植物/花卉', 'RG', 7, '["花卉毯","叶片图案"]', '图案以花朵、枝叶或植物形态为主'),
('six_dim_B', 'RG-东方/古典纹样', '东方/古典纹样', 'RG', 8, '["传统纹样","波斯纹"]', '毯面以中轴对称花纹、回纹或古典章法组成'),
('six_dim_B', 'RG-仿旧纹样', '仿旧纹样', 'RG', 9, '["做旧毯","褪色毯"]', '图案带有故意磨损、断续或褪色效果'),
('six_dim_B', 'RG-无规则艺术纹样', '无规则艺术纹样', 'RG', 10, '["艺术喷洒","自由纹样"]', '图案无明显重复单元，呈自由艺术构图'),
('six_dim_B', 'RG-异形/其他', '异形/其他', 'RG', 11, '["其他地毯图案","特殊纹样"]', '无法归入以上类型的图案构成')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × RG 地毯（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'RG-直切边', '直切边', 'RG', 1, '["平切边","无装饰边"]', '毯边被平直裁切，无额外装饰'),
('six_dim_C', 'RG-包边', '包边', 'RG', 2, '["包边带","嵌条边"]', '毯边被独立布带或材料条包覆'),
('six_dim_C', 'RG-锁边', '锁边', 'RG', 3, '["机锁边","绕线边"]', '边缘可见密集绕线或锁线针脚'),
('six_dim_C', 'RG-流苏', '流苏', 'RG', 4, '["流苏边","穗子边"]', '毯体端部垂下成排细线束'),
('six_dim_C', 'RG-穗边', '穗边', 'RG', 5, '["粗穗边","手工穗子"]', '边缘有较粗、较短的结绳或穗状束'),
('six_dim_C', 'RG-波浪/花边', '波浪/花边', 'RG', 6, '["波浪边","花瓣边"]', '毯边以规则曲线或花瓣轮廓起伏'),
('six_dim_C', 'RG-自然毛边', '自然毛边', 'RG', 7, '["毛边","未裁切边"]', '边缘保留纤维或毛料自然散开状态'),
('six_dim_C', 'RG-不规则随形边', '不规则随形边', 'RG', 8, '["随形边","有机曲线边"]', '边缘跟随不规则图形连续变化'),
('six_dim_C', 'RG-异形/其他', '异形/其他', 'RG', 9, '["特殊毯边","其他收边"]', '无法归入以上类型的边缘处理')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × RG 地毯（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'RG-平织', '平织', 'RG', 1, '["无绒平织","平面编织"]', '毯面几乎无竖起绒毛，经纬结构清晰'),
('six_dim_D', 'RG-短绒', '短绒', 'RG', 2, '["低绒","短毛毯"]', '绒毛竖起高度较小，表面紧密'),
('six_dim_D', 'RG-中绒', '中绒', 'RG', 3, '["中毛毯","常规绒高"]', '绒毛高度中等，可见柔软但不呈长毛'),
('six_dim_D', 'RG-高绒', '高绒', 'RG', 4, '["厚绒","高毛毯"]', '绒毛明显较高，手感外观蓬松厚实'),
('six_dim_D', 'RG-长毛/Shaggy', '长毛/Shaggy', 'RG', 5, '["Shaggy毯","长绒毯"]', '绒线长且自由倒伏，表面呈明显长毛感'),
('six_dim_D', 'RG-圈绒', '圈绒', 'RG', 6, '["圈毛","毛圈毯"]', '毯面纱线保持闭合环圈，可见小圈结构'),
('six_dim_D', 'RG-割绒', '割绒', 'RG', 7, '["剪绒","平剪绒"]', '纱圈被割开形成整齐竖立绒头'),
('six_dim_D', 'RG-圈割结合', '圈割结合', 'RG', 8, '["混合绒","圈绒割绒"]', '毯面同时可见环圈和割开绒头'),
('six_dim_D', 'RG-编织/辫织', '编织/辫织', 'RG', 9, '["粗编毯","辫绳毯"]', '粗纱或带状材料以编织、辫织结构构成毯面'),
('six_dim_D', 'RG-高低立体纹理', '高低立体纹理', 'RG', 10, '["高低绒","浮雕毯面"]', '不同区域绒高有明显高低差形成立体图案'),
('six_dim_D', 'RG-异形/其他', '异形/其他', 'RG', 11, '["特殊织造表面","其他绒面"]', '无法归入以上类型的织造表面')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × RG 地毯（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'RG-常规固定', '常规固定', 'RG', 1, '["常规毯","无明显工艺功能"]', '图片与文字无法确认特殊工艺或功能'),
('six_dim_F', 'RG-手工簇绒', '手工簇绒', 'RG', 2, '["手工枪刺","簇绒毯"]', '只在背面、工艺图或商品文字明确时判定手工簇绒'),
('six_dim_F', 'RG-手工打结', '手工打结', 'RG', 3, '["手工结毯","手工结织"]', '只在结构细节或文字明确可见手工打结时判定'),
('six_dim_F', 'RG-机织', '机织', 'RG', 4, '["机器织造","机制毯"]', '只在商品文字或工艺标注明确时判定机织'),
('six_dim_F', 'RG-可机洗', '可机洗', 'RG', 5, '["机洗地毯","洗衣机可洗"]', '商品图文明确出现可机洗标识或说明'),
('six_dim_F', 'RG-户外耐候', '户外耐候', 'RG', 6, '["户外毯","防晒防水"]', '商品图文明确标注户外、耐候或防晒用途'),
('six_dim_F', 'RG-防滑底', '防滑底', 'RG', 7, '["止滑背胶","防滑背面"]', '背面可见胶点、橡胶层或防滑纹理'),
('six_dim_F', 'RG-双面可用', '双面可用', 'RG', 8, '["双面毯","正反两用"]', '两面均完整展示或文字明确标注可翻面使用'),
('six_dim_F', 'RG-拼块组合', '拼块组合', 'RG', 9, '["地毯方块","可替换毯块"]', '由标准化小块组合，块间边界可辨识'),
('six_dim_F', 'RG-异形/其他', '异形/其他', 'RG', 10, '["其他地毯工艺","特殊功能毯"]', '只记录图片或明确文字可确认的其他工艺功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × PD 屏风/隔断（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'PD-单片直立', '单片直立', 'PD', 1, '["单片屏风","直立隔断"]', '由一片独立面板直立构成'),
('six_dim_A', 'PD-双片折屏', '双片折屏', 'PD', 2, '["两折屏风","双联屏"]', '两片面板以一道铰接线折叠'),
('six_dim_A', 'PD-三片折屏', '三片折屏', 'PD', 3, '["三折屏风","三联屏"]', '三片面板以两道铰接线连接'),
('six_dim_A', 'PD-多片折屏', '多片折屏', 'PD', 4, '["多折屏风","多联屏"]', '四片以上面板连续铰接并可折叠'),
('six_dim_A', 'PD-弧形围合', '弧形围合', 'PD', 5, '["弧形屏风","环抱隔断"]', '整体以弧线或多片折线形成围合'),
('six_dim_A', 'PD-模块拼接', '模块拼接', 'PD', 6, '["组合隔断","模块屏"]', '多个标准面板或单元可重复拼接'),
('six_dim_A', 'PD-滑动隔断', '滑动隔断', 'PD', 7, '["移动隔断","推拉屏"]', '面板沿水平轨道滑动开合'),
('six_dim_A', 'PD-悬挂隔断', '悬挂隔断', 'PD', 8, '["吊挂屏","悬空隔断"]', '隔断从顶部吊挂，底部不落地'),
('six_dim_A', 'PD-架体隔断', '架体隔断', 'PD', 9, '["置物架隔断","格架屏"]', '以开放架体或格子架作为分隔主体'),
('six_dim_A', 'PD-异形/其他', '异形/其他', 'PD', 10, '["艺术隔断","特殊屏风"]', '无法归入以上组合形态的特殊轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × PD 屏风/隔断（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'PD-实体板', '实体板', 'PD', 1, '["实心屏板","封闭面板"]', '面板为不透空的连续实体表面'),
('six_dim_B', 'PD-格栅', '格栅', 'PD', 2, '["竖格栅","条栅隔断"]', '由平行条杆排列构成，条间可透视'),
('six_dim_B', 'PD-镂空花格', '镂空花格', 'PD', 3, '["花格屏风","雕花隔断"]', '板面开有规则或装饰性镂空图案'),
('six_dim_B', 'PD-藤编/绳编', '藤编/绳编', 'PD', 4, '["藤编屏","绳网隔断"]', '面板由藤条、绳或编带交叉编织'),
('six_dim_B', 'PD-玻璃', '玻璃', 'PD', 5, '["玻璃隔断","透明屏"]', '主要面板为透明、半透明或磨砂玻璃'),
('six_dim_B', 'PD-布艺软包', '布艺软包', 'PD', 6, '["软包屏风","布面隔断"]', '面板表面有织物包覆和可见填充厚度'),
('six_dim_B', 'PD-声学吸音面', '声学吸音面', 'PD', 7, '["吸音屏","声学隔断"]', '面板有声学毛毡、微孔或商品文字明确标注吸音'),
('six_dim_B', 'PD-开放置物架', '开放置物架', 'PD', 8, '["层架隔断","书架屏风"]', '主体为多层开放格，可放置物品'),
('six_dim_B', 'PD-混合拼接', '混合拼接', 'PD', 9, '["多材料屏","实虚组合"]', '同一面板明显由两种以上结构或材料分区拼接'),
('six_dim_B', 'PD-异形/其他', '异形/其他', 'PD', 10, '["特殊面板","其他屏面"]', '无法归入以上类型的面板结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × PD 屏风/隔断（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'PD-无连接单片', '无连接单片', 'PD', 1, '["单片无铰","独立屏"]', '单片面板无与其他面板的可见连接件'),
('six_dim_C', 'PD-合页铰接', '合页铰接', 'PD', 2, '["合页折屏","金属合页"]', '相邻面板以可见合页连接并可折叠'),
('six_dim_C', 'PD-转轴连接', '转轴连接', 'PD', 3, '["立轴连接","旋转轴"]', '面板沿竖向转轴转动，轴线结构可见'),
('six_dim_C', 'PD-插接模块', '插接模块', 'PD', 4, '["卡槽拼接","插槽连接"]', '模块通过槽口、卡扣或插销可拆拼接'),
('six_dim_C', 'PD-金属连接杆', '金属连接杆', 'PD', 5, '["连接管","金属拉杆"]', '面板之间以外露金属杆或管件连接'),
('six_dim_C', 'PD-轨道连接', '轨道连接', 'PD', 6, '["滑轨隔断","导轨连接"]', '面板顶部或底部嵌入连续滑轨'),
('six_dim_C', 'PD-绳索/吊线连接', '绳索/吊线连接', 'PD', 7, '["吊线屏","绳挂隔断"]', '独立单元由绳索、钢索或吊线串联'),
('six_dim_C', 'PD-隐藏连接', '隐藏连接', 'PD', 8, '["无缝连接","暗铰"]', '面板间可活动或拼接，但正面看不到明显连接件'),
('six_dim_C', 'PD-异形/其他', '异形/其他', 'PD', 9, '["特殊连接","其他铰接"]', '无法归入以上类型的连接结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × PD 屏风/隔断（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'PD-落地宽脚', '落地宽脚', 'PD', 1, '["宽底座","板式底脚"]', '面板底部有明显加宽的落地脚或底板'),
('six_dim_D', 'PD-T形脚', 'T形脚', 'PD', 2, '["T字底脚","一字横脚"]', '竖向支柱底部连接水平横脚形成T形'),
('six_dim_D', 'PD-金属框架脚', '金属框架脚', 'PD', 3, '["金属底架","管状框架"]', '底部由金属管或扁钢框架承托'),
('six_dim_D', 'PD-脚轮移动', '脚轮移动', 'PD', 4, '["移动屏风","带轮隔断"]', '底座下方可见脚轮或万向轮'),
('six_dim_D', 'PD-顶天立地', '顶天立地', 'PD', 5, '["顶地撑","张紧立柱"]', '立柱同时抵住地面与顶面形成张紧固定'),
('six_dim_D', 'PD-墙面固定', '墙面固定', 'PD', 6, '["靠墙隔断","壁装屏"]', '隔断通过连接件固定在墙面'),
('six_dim_D', 'PD-吊顶悬挂', '吊顶悬挂', 'PD', 7, '["顶吊屏","吊挂隔断"]', '隔断重量主要由顶部吊件承担'),
('six_dim_D', 'PD-地轨滑动', '地轨滑动', 'PD', 8, '["地轨屏","推拉地轨"]', '底部嵌入或运行于地面导轨'),
('six_dim_D', 'PD-无明显底座', '无明显底座', 'PD', 9, '["隐藏底座","无脚屏"]', '正视无法看到独立脚、底板或轨道'),
('six_dim_D', 'PD-异形/其他', '异形/其他', 'PD', 10, '["特殊安装","其他底座"]', '无法归入以上类型的安装或底座形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × PD 屏风/隔断（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'PD-无功能', '无功能', 'PD', 1, '["纯分隔","普通屏风"]', '除空间分隔外无其他可见功能构件'),
('six_dim_F', 'PD-可折叠', '可折叠', 'PD', 2, '["折屏","可收折屏风"]', '多片面板可沿铰接点折叠收拢'),
('six_dim_F', 'PD-可移动', '可移动', 'PD', 3, '["移动隔断","带轮屏"]', '可见脚轮、手柄或其他专用移动结构'),
('six_dim_F', 'PD-置物/书架', '置物/书架', 'PD', 4, '["置物屏风","书架隔断"]', '隔断上设有可放物的层板或开放格'),
('six_dim_F', 'PD-挂衣', '挂衣', 'PD', 5, '["挂衣隔断","衣钩屏"]', '面板或架体上可见挂钩、挂杆或衣帽架'),
('six_dim_F', 'PD-声学吸音', '声学吸音', 'PD', 6, '["吸音隔断","降噪屏"]', '只在吸音材料结构可见或商品文字明确时判定'),
('six_dim_F', 'PD-白板/展示', '白板/展示', 'PD', 7, '["书写屏","展板隔断"]', '面板可书写、磁吸或张贴展示内容'),
('six_dim_F', 'PD-绿植组合', '绿植组合', 'PD', 8, '["植物隔断","花槽屏风"]', '隔断与花槽、种植盒或绿植支架连成整体'),
('six_dim_F', 'PD-灯光', '灯光', 'PD', 9, '["发光隔断","灯带屏风"]', '面板或框架可见集成灯带或发光模块'),
('six_dim_F', 'PD-异形/其他', '异形/其他', 'PD', 10, '["其他隔断功能","特殊功能屏"]', '无法归入以上类型的可见附加功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × CW 窗帘/窗饰（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'CW-布艺开合帘', '布艺开合帘', 'CW', 1, '["布帘","对开帘"]', '织物帘身沿水平轨道或帘杆左右开合'),
('six_dim_A', 'CW-纱帘', '纱帘', 'CW', 2, '["窗纱","透光纱帘"]', '帘身轻薄半透明，可透光且可见细密网纹'),
('six_dim_A', 'CW-罗马帘', '罗马帘', 'CW', 3, '["折叠罗马帘","上收布帘"]', '帘身向上收起时形成水平层叠'),
('six_dim_A', 'CW-卷帘', '卷帘', 'CW', 4, '["上卷帘","滚轴帘"]', '平整帘布绕顶部卷管上下收卷'),
('six_dim_A', 'CW-百叶帘', '百叶帘', 'CW', 5, '["横百叶","百叶窗"]', '多片平行叶片水平排列并可调角度'),
('six_dim_A', 'CW-垂直帘', '垂直帘', 'CW', 6, '["竖百叶","垂直叶片帘"]', '多片竖向长条叶片并排悬挂'),
('six_dim_A', 'CW-蜂巢帘', '蜂巢帘', 'CW', 7, '["风琴帘","蜂窝帘"]', '侧视可见连续中空蜂巢或风琴褶结构'),
('six_dim_A', 'CW-柔纱帘', '柔纱帘', 'CW', 8, '["调光帘","斑马帘"]', '透纱与不透光条带交替，通过错位调光'),
('six_dim_A', 'CW-面板帘', '面板帘', 'CW', 9, '["日式面板帘","滑动布板"]', '多片平整宽幅布板沿多轨水平滑动'),
('six_dim_A', 'CW-异形/其他', '异形/其他', 'CW', 10, '["特殊窗饰","其他帘类"]', '无法归入以上类型的窗口遮挡结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × CW 窗帘/窗饰（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'CW-挂钩打褶', '挂钩打褶', 'CW', 1, '["普通挂钩","打褶帘头"]', '帘布顶部以挂钩固定并形成规则褶皱'),
('six_dim_B', 'CW-四爪/多褶', '四爪/多褶', 'CW', 2, '["四爪钩","多倍褶"]', '每个挂点附近被集中收成四爪或多道立褶'),
('six_dim_B', 'CW-波浪帘', '波浪帘', 'CW', 3, '["S形帘头","蛇形帘"]', '顶部沿轨道形成连续等距S形波浪'),
('six_dim_B', 'CW-穿杆', '穿杆', 'CW', 4, '["杆袋帘","套杆帘"]', '帘布顶部缝成连续套袋，帘杆从中穿过'),
('six_dim_B', 'CW-打孔环', '打孔环', 'CW', 5, '["罗马圈","孔环帘"]', '帘布顶部排列大型金属或塑料环孔'),
('six_dim_B', 'CW-轨道带', '轨道带', 'CW', 6, '["挂轨带","帘头轨带"]', '顶部缝有连续窄带并以滑轮挂接轨道'),
('six_dim_B', 'CW-明装帘头', '明装帘头', 'CW', 7, '["装饰帘头","帘楣"]', '主帘上方有独立可见的短帘或装饰帘楣'),
('six_dim_B', 'CW-盒式帘头', '盒式帘头', 'CW', 8, '["帘盒","窗帘盒"]', '轨道和帘布顶部被硬质盒体遮挡'),
('six_dim_B', 'CW-异形/其他', '异形/其他', 'CW', 9, '["特殊帘头","其他上部结构"]', '无法归入以上类型的帘头或上部构造')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × CW 窗帘/窗饰（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'CW-单层', '单层', 'CW', 1, '["单帘","单层窗帘"]', '窗口只有一层帘身或叶片系统'),
('six_dim_C', 'CW-双层布纱', '双层布纱', 'CW', 2, '["布纱双层","遮光帘加纱帘"]', '同一窗口前后并置遮光布帘与透光纱帘'),
('six_dim_C', 'CW-左右对开', '左右对开', 'CW', 3, '["双开帘","对开窗帘"]', '两幅帘身从中央分开并向左右收拢'),
('six_dim_C', 'CW-单边开合', '单边开合', 'CW', 4, '["单开帘","一侧收帘"]', '整幅帘身统一向左或向右一侧收拢'),
('six_dim_C', 'CW-连续波浪', '连续波浪', 'CW', 5, '["S形褶","等距波形"]', '帘身从顶到底保持连续等距波形褶皱'),
('six_dim_C', 'CW-平面垂落', '平面垂落', 'CW', 6, '["平帘","无褶垂帘"]', '帘身展开时为平整平面，几乎无立体褶皱'),
('six_dim_C', 'CW-分幅拼接', '分幅拼接', 'CW', 7, '["多幅拼帘","拼色帘身"]', '帘身由多个布幅或色块以可见缝线拼接'),
('six_dim_C', 'CW-异形/其他', '异形/其他', 'CW', 8, '["特殊褶型","其他帘身组合"]', '无法归入以上类型的帘身褶型或组合')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × CW 窗帘/窗饰（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'CW-窗帘杆', '窗帘杆', 'CW', 1, '["明杆","罗马杆"]', '帘体悬挂于可见圆杆或装饰杆'),
('six_dim_D', 'CW-壁装轨道', '壁装轨道', 'CW', 2, '["墙装滑轨","侧装轨道"]', '轨道通过支架固定在窗上方墙面'),
('six_dim_D', 'CW-顶装轨道', '顶装轨道', 'CW', 3, '["顶装滑轨","天花轨道"]', '轨道直接固定于顶面或窗洞顶部'),
('six_dim_D', 'CW-隐藏轨道', '隐藏轨道', 'CW', 4, '["暗轨","帘盒内轨道"]', '轨道藏于帘盒或吊顶槽内，正面不可见'),
('six_dim_D', 'CW-窗框内装', '窗框内装', 'CW', 5, '["内嵌安装","窗洞内装"]', '帘体与机构整体安装在窗框内侧'),
('six_dim_D', 'CW-手拉', '手拉', 'CW', 6, '["手动开合","直接拉帘"]', '直接用手拉动帘身，无可见拉珠或电机'),
('six_dim_D', 'CW-拉珠/拉绳', '拉珠/拉绳', 'CW', 7, '["珠链拉绳","循环拉珠"]', '窗侧可见珠链、拉绳或拉带操作件'),
('six_dim_D', 'CW-电动轨道', '电动轨道', 'CW', 8, '["电机帘","智能窗帘轨"]', '可见电机端头、电源线或图文明确标注电动'),
('six_dim_D', 'CW-异形/其他', '异形/其他', 'CW', 9, '["特殊安装窗饰","其他驱动"]', '无法归入以上类型的安装或驱动方式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × CW 窗帘/窗饰（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'CW-普通遮光', '普通遮光', 'CW', 1, '["常规遮光","日常窗帘"]', '只在图文标注普通遮光或无更高等级时选择'),
('six_dim_F', 'CW-高遮光', '高遮光', 'CW', 2, '["高遮光帘","80%遮光"]', '商品图文明确标注高遮光或较高遮光率'),
('six_dim_F', 'CW-全遮光', '全遮光', 'CW', 3, '["全黑窗帘","blackout"]', '商品图文明确标注全遮光、百分百遮光或 blackout'),
('six_dim_F', 'CW-透光不透人', '透光不透人', 'CW', 4, '["隐私纱","采光纱帘"]', '帘身允许日光透过，同时图文标注保护隐私'),
('six_dim_F', 'CW-纯装饰', '纯装饰', 'CW', 5, '["装饰帘","帘楣装饰"]', '主要用于视觉装饰，无明显遮光或隐私功能'),
('six_dim_F', 'CW-隔热', '隔热', 'CW', 6, '["保温窗帘","隔热帘"]', '图文明确标注隔热、保温或反射热能'),
('six_dim_F', 'CW-吸音', '吸音', 'CW', 7, '["吸音窗帘","降噪帘"]', '图文明确标注声学吸音或降噪性能'),
('six_dim_F', 'CW-电动', '电动', 'CW', 8, '["智能开合","电机驱动"]', '可见电机或图文明确标注遥控、智能电动'),
('six_dim_F', 'CW-阻燃', '阻燃', 'CW', 9, '["防火窗帘","阻燃布"]', '只在图文或规格明确标注阻燃等级时判定'),
('six_dim_F', 'CW-可水洗', '可水洗', 'CW', 10, '["水洗窗帘","可洗帘"]', '洗涤标识或商品文字明确说明可水洗'),
('six_dim_F', 'CW-异形/其他', '异形/其他', 'CW', 11, '["其他窗饰功能","特殊功能帘"]', '只记录图片或明确商品文字可确认的其他功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;
-- <<< SIX_DIM_DICT_SEED <<<

-- >>> SIX_DIM_SCHEMA_SEED (V30, do not edit manually) >>>
INSERT INTO six_dim_schema (category_code, dim_key, label, description, sort_order) VALUES
-- FS 座椅/沙发
('FS','A','轮廓形态','整体造型，如弧形、方盒形、蛋形、模块化组合',1),
('FS','B','靠背/背部特征','靠背高度、包裹性、编织镂空等',2),
('FS','C','扶手特征','扶手形态，如无扶手、环形扶手、实木扶手',3),
('FS','D','腿部/底座特征','腿部形态，如细腿、落地底座、金属框架',4),
('FS','E','表面材质','实木、皮革、布艺、金属等表面材质',5),
('FS','F','软包填充形态','软包饱满度、绗缝、拉扣等填充形态',6),
-- SF 沙发
('SF','A','轮廓形态','整体造型，如L型、弧形、一字型、模块化组合',1),
('SF','B','靠背/背部特征','靠背高度、倾斜角度、包裹性',2),
('SF','C','扶手特征','扶手形态，如无扶手、低扶手、宽厚扶手',3),
('SF','D','腿部/底座特征','落地式、细腿、金属脚、悬浮底座',4),
('SF','E','表面材质','皮革、布艺、羊羔绒、天鹅绒等',5),
('SF','F','软包填充形态','坐垫/靠背填充饱满度、绗缝、拉扣',6),
-- TB 茶几
('TB','A','整体造型/轮廓','茶几整体形态，如圆形、方形、异形、组合式',1),
('TB','B','台面形态','台面形状、厚度、悬浮/内嵌设计',2),
('TB','C','台面边缘/连接部','台面边缘处理、与支撑结构的连接方式',3),
('TB','D','桌腿/底座','桌腿形态，如细腿、敦实柱腿、金属框架、悬浮底座',4),
('TB','E','表面材质','大理石、玻璃、实木、金属等台面/框架材质',5),
('TB','F','收纳/功能件','抽屉、层板、旋转功能件等附加功能',6),
-- FC 柜类
('FC','A','整体造型/轮廓','柜体整体形态，如高柜、矮柜、组合柜、悬浮柜',1),
('FC','B','门板/抽屉特征','门板分割方式、抽屉排列、开放格/封闭格比例',2),
('FC','C','拉手/五金特征','拉手形态，如无拉手、明装拉手、隐藏拉手、金属拉手',3),
('FC','D','底座/支脚','落地式、高脚、金属支脚、悬浮挂墙',4),
('FC','E','表面材质','实木、板材、岩板、藤编、烤漆等表面材质',5),
('FC','F','内部结构/功能分区','内部隔层、抽屉、灯带、视听设备位等功能分区',6),
-- BS 吧椅
('BS','A','座面轮廓','座面形状，如圆形、方形、马蹄形',1),
('BS','B','靠背/背部特征','靠背高度、包裹性，无靠背/低靠背/高靠背',2),
('BS','C','扶手特征','扶手形态，如无扶手、小扶手、环形扶手',3),
('BS','D','底座/升降杆','固定底座、三脚/四脚底座、气压升降杆',4),
('BS','E','表面材质','皮革、金属、实木、塑料等',5),
('BS','F','软包填充形态','座面/靠背软包形态、厚度、绗缝',6),
-- OF 办公家具
('OF','A','整体造型/轮廓','家具整体形态，如班台、职员桌、会议桌、文件柜',1),
('OF','B','工作面/背部特征','台面/工作面形态，或柜类背板/门板特征',2),
('OF','C','侧部/连接部','侧板、挡板、线槽、扶手/侧翼结构',3),
('OF','D','支撑/底座','桌腿、桌架、柜脚、人体工学底盘',4),
('OF','E','表面材质','实木皮、板材、金属、网布、皮革等',5),
('OF','F','功能件/软包','抽屉、线槽、升降机构、坐垫软包等功能件',6),
-- DT 餐桌
('DT','A','造型','餐桌整体俯视轮廓，如长桌、圆桌、方桌、跑道形、岛台一体桌',1),
('DT','B','台面形态','台面厚度与构造，如平板薄面、厚台面、悬浮台面、转盘台面',2),
('DT','C','边缘/结构','台面边缘工艺与附属结构，如直边、马肚边、瀑布边、裙边/立水',3),
('DT','D','桌腿/底座','支撑形态，如四直腿、外八腿、喇叭/郁金香底座、落地箱式',4),
('DT','E','表面材质','岩板、实木、玻璃、大理石等台面/框架材质',5),
('DT','F','功能/展开方式','固定式、伸缩、折叠、旋转展开、升降、储物等',6),
-- BD 床
('BD','A','整体造型','床体整体形态，如齐边床、内嵌床、地台床、箱体床、上下床',1),
('BD','B','床头','床头/床屏形态（识别置信度最高维度），如软包大靠包、拉扣床头、平板薄床头、拱形床头',2),
('BD','C','床尾/床边','床尾屏板与床沿形态，如齐边无床尾、高床尾屏、宽边床沿',3),
('BD','D','床脚/底座','支脚与底座形态，如实木脚、金属脚、落地无脚、悬浮底座',4),
('BD','E','表面材质','真皮、布艺、实木、绒布等床体表面材质',5),
('BD','F','储物/功能','气压上掀储物、床体抽屉、床头功能区、床底灯带等',6),
-- LT 灯具
('LT','A','灯体造型','灯体整体剪影，如球形、长条形、枝形、分子式',1),
('LT','B','灯罩/出光','灯罩形态与出光方式，如玻璃罩、布艺罩、无罩裸光源',2),
('LT','C','灯臂/连接','灯体与安装面的连接结构，如吊线吊杆、弧形悬臂、折叠摇臂',3),
('LT','D','安装/底座','安装方式与底座形态，如吊挂式、吸顶式、落地立式、壁挂式',4),
('LT','E','表面材质','金属、玻璃、亚克力、藤竹、布艺等灯体材质',5),
('LT','F','装饰元素','附加装饰构件，如水晶挂饰、流苏穗边、彩色玻璃',6),
-- GENERIC 通用兜底
('GENERIC','A','整体造型/轮廓','产品整体外观形态',1),
('GENERIC','B','上部/背部特征','座椅靠背、柜类背板/门板、桌类台面',2),
('GENERIC','C','侧部/连接部','扶手、侧板、台面边缘、连接结构',3),
('GENERIC','D','支撑/底座','腿部、底座、支脚、底盘',4),
('GENERIC','E','表面材质','主要表面材质与纹理',5),
('GENERIC','F','功能/填充件','软包填充、抽屉、层板等功能件',6)
ON CONFLICT (category_code, dim_key) DO NOTHING;
-- <<< SIX_DIM_SCHEMA_SEED <<<

-- >>> PRODUCT_TAXONOMY_SEED (generated by scripts/generate_six_dim_dict_seed.js, do not edit manually) >>>
-- 扩展一级业务品类（Feature Flag 默认关闭，种子存在不代表进入正式业务候选集）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order, aliases, remark) VALUES
('category', 'DK', '书桌/写字台', 10, '["书桌","写字台","家庭办公桌","电脑桌"]', '家庭、酒店与书房使用的工作桌，不含 OF 办公职级语义'),
('category', 'MT', '床垫', 11, '["床垫","睡垫","弹簧床垫","乳胶床垫"]', '独立于床架的睡眠承托产品，内部结构仅在剖面或文字可见时判定'),
('category', 'MR', '镜子', 12, '["镜子","落地镜","穿衣镜","装饰镜"]', '以镜面、边框和安装支撑方式为主要结构的反射类家居产品'),
('category', 'RG', '地毯', 13, '["地毯","块毯","客厅毯","床边毯"]', '铺设于地面的织物或编织产品，以轮廓、图案、边缘和织造表面区分'),
('category', 'PD', '屏风/隔断', 14, '["屏风","隔断","折屏","空间分隔"]', '非柜类的空间分隔结构，包括折屏、滑动、悬挂与声学隔断'),
('category', 'CW', '窗帘/窗饰', 15, '["窗帘","窗饰","卷帘","百叶帘","罗马帘"]', '布艺开合帘、卷帘、百叶等窗口软装及其可见安装结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 二级产品类型知识层：独立旁路，不关联 rspu_master，不参与编码/报价/订单/同款
INSERT INTO knowledge_product_type
    (type_code, type_name, business_category_code, schema_category_code, aliases, room_tags, description, sort_order) VALUES
('DINING_CHAIR', '餐椅', 'FS', 'FS', '["餐桌椅","吃饭椅"]'::jsonb, '["DINING_ROOM"]'::jsonb, '用于餐桌周边的单人座椅', 1),
('LOUNGE_CHAIR', '休闲椅', 'FS', 'FS', '["休闲单椅","主人椅"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '以放松坐姿为主的单人椅', 2),
('ARMCHAIR', '扶手椅', 'FS', 'FS', '["有扶手椅","扶手单椅"]'::jsonb, '["LIVING_ROOM","STUDY"]'::jsonb, '两侧具有明确扶手的单人椅', 3),
('ACCENT_CHAIR', '装饰单椅', 'FS', 'FS', '["造型椅","点缀椅"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '以空间视觉点缀为主的单椅', 4),
('ROCKING_CHAIR', '摇椅', 'FS', 'FS', '["摇摇椅","弧底椅"]'::jsonb, '["LIVING_ROOM","BALCONY"]'::jsonb, '底部具有弧形摇杆或摇摆机构的座椅', 5),
('FOLDING_CHAIR', '折叠椅', 'FS', 'FS', '["可折椅","收折椅"]'::jsonb, '["DINING_ROOM","BALCONY"]'::jsonb, '框架可折叠收纳的座椅', 6),
('HANGING_CHAIR', '吊椅', 'FS', 'FS', '["秋千椅","悬挂椅"]'::jsonb, '["BALCONY","LIVING_ROOM"]'::jsonb, '座体通过吊链或吊绳悬挂的椅类', 7),
('BENCH', '长凳/床尾凳', 'FS', 'FS', '["长凳","床尾凳","换鞋凳"]'::jsonb, '["BEDROOM","ENTRYWAY","DINING_ROOM"]'::jsonb, '可容纳两人以上或用于床尾的长条座具', 8),
('OTTOMAN', '脚踏', 'FS', 'FS', '["脚凳","搁脚凳"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '与沙发或单椅配套的无靠背脚踏', 9),
('POUF', '坐墩', 'FS', 'FS', '["蒲团","软坐墩"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '低矮、多为软体的无靠背坐具', 10),
('STOOL', '矮凳', 'FS', 'FS', '["小凳子","无背凳"]'::jsonb, '["DINING_ROOM","ENTRYWAY"]'::jsonb, '低高度的无靠背单人凳', 11),
('CHAIR_LOUNGER', '单人躺椅', 'FS', 'FS', '["躺椅","贵妃椅"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '可支撑腿部伸展的单人躺坐具', 12),
('LOVESEAT', '双人沙发', 'SF', 'SF', '["二人位沙发","情侣沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '主要容纳两人的小型沙发', 13),
('STRAIGHT_SOFA', '直排沙发', 'SF', 'SF', '["一字沙发","直线沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '座位沿单一直线排列的沙发', 14),
('SECTIONAL_SOFA', '转角沙发', 'SF', 'SF', '["L型沙发","转角组合"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '座位在转角处连续延伸的沙发', 15),
('MODULAR_SOFA', '模块沙发', 'SF', 'SF', '["组合沙发","积木沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '由多个可重组座位模块构成', 16),
('SOFA_BED', '沙发床', 'SF', 'SF', '["可睡沙发","折叠沙发床"]'::jsonb, '["LIVING_ROOM","GUEST_ROOM"]'::jsonb, '可展开或翻折为睡眠面的沙发', 17),
('RECLINER_SOFA', '功能沙发', 'SF', 'SF', '["电动沙发","伸展沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '座位可躺倒或展开脚托的功能沙发', 18),
('CURVED_SOFA', '弧形沙发', 'SF', 'SF', '["弯沙发","月牙沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '座位整体沿弧线排列的沙发', 19),
('CHAISE_SOFA', '贵妃/躺榻组合', 'SF', 'SF', '["贵妃沙发","躺位沙发"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '直排座位与长躺位组合的沙发', 20),
('COFFEE_TABLE', '茶几', 'TB', 'TB', '["客厅中心几","大茶几"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '用于沙发区中心的低桌', 21),
('SIDE_TABLE', '边几', 'TB', 'TB', '["沙发边几","小边桌"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '放置于沙发或座椅侧边的小几', 22),
('END_TABLE', '角几', 'TB', 'TB', '["角桌","端几"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '放置于沙发端部或墙角的小几', 23),
('C_TABLE', 'C形沙发边桌', 'TB', 'TB', '["C形边几","嵌入式边桌"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '底座可插入沙发或床下的C形小桌', 24),
('NESTING_TABLE', '子母/套几', 'TB', 'TB', '["套几","组合几"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '大小多张可嵌套收纳的小几组', 25),
('DRUM_TABLE', '鼓形几', 'TB', 'TB', '["鼓几","墩式边几"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '整体呈鼓或实心墩体的小几', 26),
('PEDESTAL_TABLE', '柱式小几', 'TB', 'TB', '["独柱几","圆盘底边几"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '由中央单柱支撑小台面的几类', 27),
('CONSOLE_TABLE', '玄关台/窄边桌', 'TB', 'TB', '["玄关桌","靠墙窄桌"]'::jsonb, '["ENTRYWAY","LIVING_ROOM"]'::jsonb, '靠墙摆放的窄长高台，过渡归入 TB', 28),
('TV_STAND', '电视柜', 'FC', 'FC', '["视听柜","地柜"]'::jsonb, '["LIVING_ROOM"]'::jsonb, '用于承放电视与视听设备的低柜', 29),
('SIDEBOARD', '餐边柜', 'FC', 'FC', '["餐厅边柜","餐具柜"]'::jsonb, '["DINING_ROOM"]'::jsonb, '放置于餐厅用于餐具与餐酒收纳的柜体', 30),
('CREDENZA', '边柜', 'FC', 'FC', '["矮边柜","靠墙柜"]'::jsonb, '["LIVING_ROOM","DINING_ROOM"]'::jsonb, '靠墙摆放的通用横向矮柜', 31),
('NIGHTSTAND', '床头柜', 'FC', 'FC', '["床边柜","床头小柜"]'::jsonb, '["BEDROOM"]'::jsonb, '放置于床侧的小型收纳柜', 32),
('DRESSER', '斗柜', 'FC', 'FC', '["抽屉柜","五斗柜"]'::jsonb, '["BEDROOM","LIVING_ROOM"]'::jsonb, '以多层水平抽屉为主的柜体', 33),
('WARDROBE', '衣柜', 'FC', 'FC', '["大衣橱","衣帽柜"]'::jsonb, '["BEDROOM","CLOAKROOM"]'::jsonb, '用于挂放与叠放衣物的高柜', 34),
('BOOKCASE', '书柜', 'FC', 'FC', '["书架柜","藏书柜"]'::jsonb, '["STUDY","LIVING_ROOM"]'::jsonb, '以层板收纳书籍的柜体', 35),
('DISPLAY_CABINET', '展示柜', 'FC', 'FC', '["陈列柜","玻璃展柜"]'::jsonb, '["LIVING_ROOM","DINING_ROOM"]'::jsonb, '具有透明门或开放展示面的柜体', 36),
('SHOE_CABINET', '鞋柜', 'FC', 'FC', '["玄关鞋柜","换鞋柜"]'::jsonb, '["ENTRYWAY"]'::jsonb, '用于收纳鞋履的玄关柜体', 37),
('WINE_CABINET', '酒柜', 'FC', 'FC', '["餐酒柜","葡萄酒柜"]'::jsonb, '["DINING_ROOM","LIVING_ROOM"]'::jsonb, '用于存放或展示酒瓶酒具的柜体', 38),
('ENTRY_CABINET', '玄关柜', 'FC', 'FC', '["入户柜","门厅柜"]'::jsonb, '["ENTRYWAY"]'::jsonb, '位于入户区的综合收纳柜', 39),
('MOBILE_CART', '移动柜/推车', 'FC', 'FC', '["带轮柜","收纳推车"]'::jsonb, '["DINING_ROOM","LIVING_ROOM"]'::jsonb, '底部带轮可移动的小柜或推车', 40),
('BAR_STOOL', '吧凳', 'BS', 'BS', '["高凳","无背吧凳"]'::jsonb, '["BAR","KITCHEN"]'::jsonb, '吧台高度的无靠背或低靠背凳', 41),
('BAR_CHAIR', '吧椅', 'BS', 'BS', '["高脚椅","有背吧椅"]'::jsonb, '["BAR","KITCHEN"]'::jsonb, '具有明确靠背的吧台高座椅', 42),
('COUNTER_STOOL', '中岛凳', 'BS', 'BS', '["厨岛凳","柜台凳"]'::jsonb, '["KITCHEN","DINING_ROOM"]'::jsonb, '匹配厨房中岛或柜台高度的高凳', 43),
('ADJUSTABLE_STOOL', '升降吧椅', 'BS', 'BS', '["气压吧椅","可调高吧凳"]'::jsonb, '["BAR","KITCHEN"]'::jsonb, '高度可通过气压或螺旋机构调节的吧椅', 44),
('BACKLESS_STOOL', '无靠背高凳', 'BS', 'BS', '["无背吧凳","高脚凳"]'::jsonb, '["BAR","KITCHEN"]'::jsonb, '无任何靠背构件的高凳', 45),
('TASK_CHAIR', '办公椅', 'OF', 'OF', '["职员椅","任务椅"]'::jsonb, '["OFFICE"]'::jsonb, '日常工位使用的人体工学办公椅', 46),
('EXECUTIVE_CHAIR', '班椅/高管椅', 'OF', 'OF', '["老板椅","总裁椅"]'::jsonb, '["OFFICE"]'::jsonb, '靠背较高、体量较大的高管办公椅', 47),
('GUEST_CHAIR', '访客椅', 'OF', 'OF', '["接待椅","会客椅"]'::jsonb, '["OFFICE"]'::jsonb, '用于办公接待或桌前访客的椅类', 48),
('STAFF_DESK', '职员桌', 'OF', 'OF', '["员工桌","办公桌"]'::jsonb, '["OFFICE"]'::jsonb, '单人或并列职员工位用办公桌', 49),
('EXECUTIVE_DESK', '班台', 'OF', 'OF', '["大班台","总裁桌"]'::jsonb, '["OFFICE"]'::jsonb, '体量大、台面宽厚的高管办公桌', 50),
('MANAGER_DESK', '主管桌', 'OF', 'OF', '["经理桌","主管台"]'::jsonb, '["OFFICE"]'::jsonb, '体量介于班台与职员桌之间的办公桌', 51),
('WORKSTATION', '屏风工位', 'OF', 'OF', '["卡位","格子间工位"]'::jsonb, '["OFFICE"]'::jsonb, '桌面与屏风组成的单人或多人工位系统', 52),
('CONFERENCE_TABLE', '会议桌', 'OF', 'OF', '["会议台","多人会议桌"]'::jsonb, '["OFFICE","MEETING_ROOM"]'::jsonb, '供多人围坐会议的大尺寸桌台', 53),
('TRAINING_TABLE', '培训桌', 'OF', 'OF', '["培训折叠桌","条桌"]'::jsonb, '["OFFICE","TRAINING_ROOM"]'::jsonb, '可并排、收折或翻板的培训用桌', 54),
('HEIGHT_ADJUSTABLE_DESK', '升降办公桌', 'OF', 'OF', '["站立办公桌","电动升降桌"]'::jsonb, '["OFFICE"]'::jsonb, '台面高度可调的办公桌', 55),
('FILING_CABINET', '文件柜', 'OF', 'OF', '["档案柜","资料柜"]'::jsonb, '["OFFICE"]'::jsonb, '用于文件档案分类收纳的办公柜', 56),
('OFFICE_BOOKCASE', '办公书柜', 'OF', 'OF', '["办公室书柜","文件书柜"]'::jsonb, '["OFFICE"]'::jsonb, '办公空间使用的书籍与资料柜', 57),
('OFFICE_CREDENZA', '办公边柜', 'OF', 'OF', '["班台副柜","办公矮柜"]'::jsonb, '["OFFICE"]'::jsonb, '与办公桌或会议区配套的低柜', 58),
('DINING_TABLE', '餐桌', 'DT', 'DT', '["家用餐桌","饭桌"]'::jsonb, '["DINING_ROOM"]'::jsonb, '常规家用或商用用餐桌', 59),
('ROUND_DINING_TABLE', '圆餐桌', 'DT', 'DT', '["圆桌","圆形饭桌"]'::jsonb, '["DINING_ROOM"]'::jsonb, '台面为圆形的餐桌', 60),
('EXTENDABLE_DINING_TABLE', '伸缩餐桌', 'DT', 'DT', '["可延长餐桌","拉伸桌"]'::jsonb, '["DINING_ROOM"]'::jsonb, '台面可通过拉伸、翻板等方式扩展的餐桌', 61),
('BANQUET_TABLE', '宴会桌', 'DT', 'DT', '["宴会长桌","酒店餐桌"]'::jsonb, '["BANQUET_HALL","DINING_ROOM"]'::jsonb, '宴会与酒店多人就餐使用的桌台', 62),
('BAR_TABLE', '吧台桌', 'DT', 'DT', '["高脚桌","吧桌"]'::jsonb, '["BAR","DINING_ROOM"]'::jsonb, '台面高度较高、与吧椅配套的桌台', 63),
('KITCHEN_ISLAND_TABLE', '岛台餐桌', 'DT', 'DT', '["餐岛一体桌","中岛桌"]'::jsonb, '["KITCHEN","DINING_ROOM"]'::jsonb, '与厨房岛台连接或一体化的餐桌', 64),
('UPHOLSTERED_BED', '软包床', 'BD', 'BD', '["布艺床","皮床"]'::jsonb, '["BEDROOM"]'::jsonb, '床头或床体大面积采用软包的床', 65),
('PLATFORM_BED', '平台床', 'BD', 'BD', '["地台床","低平台床"]'::jsonb, '["BEDROOM"]'::jsonb, '床垫承托面较低且床边呈平台形的床', 66),
('STORAGE_BED', '储物床', 'BD', 'BD', '["箱体床","高箱床"]'::jsonb, '["BEDROOM"]'::jsonb, '床下具有上揀或抽屉储物空间的床', 67),
('BUNK_BED', '上下床', 'BD', 'BD', '["双层床","高低床"]'::jsonb, '["KIDS_ROOM","DORMITORY"]'::jsonb, '两个睡眠面上下叠置的床', 68),
('LOFT_BED', '高架床', 'BD', 'BD', '["阁楼床","上床下桌"]'::jsonb, '["KIDS_ROOM","DORMITORY"]'::jsonb, '睡眠面抬高，下方留出桌柜或活动空间的床', 69),
('CANOPY_BED', '四柱床', 'BD', 'BD', '["帷幔床","天蓬床"]'::jsonb, '["BEDROOM"]'::jsonb, '四角立柱向上延伸并可悬挂帷幔的床', 70),
('DAY_BED', '日间床', 'BD', 'BD', '["贵妃床","沙发床榻"]'::jsonb, '["LIVING_ROOM","GUEST_ROOM"]'::jsonb, '白天可坐卧、夜间可睡眠的单人床榻', 71),
('KIDS_BED', '儿童床', 'BD', 'BD', '["小孩床","童床"]'::jsonb, '["KIDS_ROOM"]'::jsonb, '尺寸、护栏或造型为儿童使用设计的床', 72),
('PENDANT_LIGHT', '吊灯', 'LT', 'LT', '["单头吊灯","悬吊灯"]'::jsonb, '["LIVING_ROOM","DINING_ROOM"]'::jsonb, '由吊线或吊杆从顶部悬吊的灯具', 73),
('CHANDELIER', '枝形吊灯', 'LT', 'LT', '["水晶吊灯","多臂吊灯"]'::jsonb, '["LIVING_ROOM","DINING_ROOM"]'::jsonb, '具有多灯臂或装饰性大型悬吊结构的灯具', 74),
('CEILING_LIGHT', '吸顶灯', 'LT', 'LT', '["贴顶灯","半吸顶灯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '紧贴顶面安装的照明灯具', 75),
('FLOOR_LAMP', '落地灯', 'LT', 'LT', '["立灯","地灯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '带落地底座与长灯杆的可移动灯具', 76),
('TABLE_LAMP', '台灯', 'LT', 'LT', '["桌灯","床头灯"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '放置于桌面或柜面的小型灯具', 77),
('WALL_LIGHT', '壁灯', 'LT', 'LT', '["墙灯","床头壁灯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '固定在墙面的灯具', 78),
('TRACK_LIGHT', '轨道灯', 'LT', 'LT', '["导轨射灯","磁吸轨道灯"]'::jsonb, '["LIVING_ROOM","COMMERCIAL"]'::jsonb, '灯头安装在条形轨道上的灯具系统', 79),
('RECESSED_LIGHT', '嵌入式灯', 'LT', 'LT', '["筒灯","嵌入射灯"]'::jsonb, '["LIVING_ROOM","COMMERCIAL"]'::jsonb, '灯体嵌入吊顶或墙体、仅露出光口的灯具', 80),
('CLAMP_LIGHT', '夹式灯', 'LT', 'LT', '["夹子灯","夹灯"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '通过弹簧夹固定于桌沿或床头的灯具', 81),
('DECORATIVE_LIGHT', '装饰灯', 'LT', 'LT', '["造型灯","艺术灯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '以造型与氛围装饰为主的灯具', 82),
('WRITING_DESK', '写字台', 'DK', 'DK', '["书桌","写字桌"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '以阅读与书写为主的家用桌', 83),
('COMPUTER_DESK', '电脑桌', 'DK', 'DK', '["PC桌","家庭办公桌"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '以电脑设备与走线管理为主的书桌', 84),
('CORNER_DESK', '转角书桌', 'DK', 'DK', '["L型书桌","拐角桌"]'::jsonb, '["STUDY","OFFICE"]'::jsonb, '两段工作面构成转角的书桌', 85),
('SECRETARY_DESK', '翻板书桌', 'DK', 'DK', '["秘书柜桌","柜桌"]'::jsonb, '["STUDY","LIVING_ROOM"]'::jsonb, '柜体前板翻下后形成工作面的桌', 86),
('WALL_DESK', '壁挂书桌', 'DK', 'DK', '["悬浮书桌","折叠壁桌"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '固定于墙面的悬空或折叠桌', 87),
('SPRING_MATTRESS', '弹簧床垫', 'MT', 'MT', '["弹簧垫","袋装弹簧床垫"]'::jsonb, '["BEDROOM"]'::jsonb, '仅在剖面或商品文字明确时判定的弹簧结构床垫', 88),
('FOAM_MATTRESS', '海绵床垫', 'MT', 'MT', '["泡棉床垫","记忆棉床垫"]'::jsonb, '["BEDROOM"]'::jsonb, '仅在剖面或文字明确时判定的泡棉类床垫', 89),
('LATEX_MATTRESS', '乳胶床垫', 'MT', 'MT', '["天然乳胶垫","乳胶睡垫"]'::jsonb, '["BEDROOM"]'::jsonb, '仅在剖面或文字明确时判定的乳胶床垫', 90),
('HYBRID_MATTRESS', '混合床垫', 'MT', 'MT', '["复合床垫","弹簧海绵混合床垫"]'::jsonb, '["BEDROOM"]'::jsonb, '仅在剖面或文字明确多种承托层组合时判定', 91),
('FOLDING_MATTRESS', '折叠床垫', 'MT', 'MT', '["三折床垫","便携睡垫"]'::jsonb, '["BEDROOM","GUEST_ROOM"]'::jsonb, '具有明显分段折线的可折叠床垫', 92),
('WALL_MIRROR', '壁挂镜', 'MR', 'MR', '["挂墙镜","墙镜"]'::jsonb, '["ENTRYWAY","BATHROOM","BEDROOM"]'::jsonb, '固定在墙面的镜子', 93),
('FLOOR_MIRROR', '落地镜', 'MR', 'MR', '["穿衣镜","全身镜"]'::jsonb, '["BEDROOM","ENTRYWAY"]'::jsonb, '落地摆放或斜靠的全身长镜', 94),
('VANITY_MIRROR', '梳妆镜', 'MR', 'MR', '["化妆镜","台镜"]'::jsonb, '["BEDROOM","BATHROOM"]'::jsonb, '配合梳妆台或台面使用的镜子', 95),
('DECORATIVE_MIRROR', '装饰镜', 'MR', 'MR', '["艺术镜","造型镜"]'::jsonb, '["LIVING_ROOM","ENTRYWAY"]'::jsonb, '以异形、拼组或装饰边框为主的镜子', 96),
('AREA_RUG', '块毯', 'RG', 'RG', '["区域地毯","客厅毯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '用于覆盖局部地面区域的地毯', 97),
('RUNNER_RUG', '长条毯', 'RG', 'RG', '["走廊毯","走道毯"]'::jsonb, '["HALLWAY","ENTRYWAY"]'::jsonb, '用于走廊或床侧的狭长地毯', 98),
('ROUND_RUG', '圆形地毯', 'RG', 'RG', '["圆毯","圆形块毯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '外轮廓为圆形的区域地毯', 99),
('OUTDOOR_RUG', '户外地毯', 'RG', 'RG', '["防水地毯","阳台毯"]'::jsonb, '["BALCONY","OUTDOOR"]'::jsonb, '图文明确标注耐候或户外用途的地毯', 100),
('FOLDING_SCREEN', '折屏', 'PD', 'PD', '["折叠屏风","多联屏风"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '多片面板铰接并可折叠的屏风', 101),
('ROOM_DIVIDER', '空间隔断', 'PD', 'PD', '["房间分隔","隔断屏"]'::jsonb, '["LIVING_ROOM","OFFICE"]'::jsonb, '用于划分空间且非柜体的直立分隔', 102),
('SLIDING_PARTITION', '滑动隔断', 'PD', 'PD', '["移动隔墙","推拉隔断"]'::jsonb, '["LIVING_ROOM","OFFICE"]'::jsonb, '面板沿轨道滑动开合的隔断', 103),
('ACOUSTIC_PARTITION', '声学隔断', 'PD', 'PD', '["吸音屏风","降噪隔断"]'::jsonb, '["OFFICE","COMMERCIAL"]'::jsonb, '图文明确具有吸音或降噪性能的隔断', 104),
('DRAPE_CURTAIN', '布艺窗帘', 'CW', 'CW', '["布帘","开合帘"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '沿帘杆或轨道水平开合的布艺帘', 105),
('SHEER_CURTAIN', '纱帘', 'CW', 'CW', '["窗纱帘","透光帘"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '以半透明纱料制成的窗帘', 106),
('ROLLER_BLIND', '卷帘', 'CW', 'CW', '["卷布帘","上卷帘"]'::jsonb, '["STUDY","OFFICE"]'::jsonb, '帘布绕卷管上下收卷的窗饰', 107),
('ROMAN_SHADE', '罗马帘', 'CW', 'CW', '["折叠帘","上收布帘"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '向上收起时形成水平层叠的布帘', 108),
('VENETIAN_BLIND', '百叶帘', 'CW', 'CW', '["横百叶","百叶窗"]'::jsonb, '["STUDY","OFFICE"]'::jsonb, '由多片可调角度水平叶片构成的窗饰', 109),
('CELLULAR_SHADE', '蜂巢帘', 'CW', 'CW', '["风琴帘","蜂窝帘"]'::jsonb, '["BEDROOM","STUDY"]'::jsonb, '截面呈中空蜂巢结构的折叠窗饰', 110)
ON CONFLICT (type_code) DO NOTHING;

-- 扩展品类 A-F Schema；E 维只声明语义，枚举继续复用 material/fabric
INSERT INTO six_dim_schema (category_code, dim_key, label, description, sort_order) VALUES
('DK', 'A', '整体布局/轮廓', '桌体俯视与整体布局', 1),
('DK', 'B', '台面形态', '工作台面的厚薄、层级与构造', 2),
('DK', 'C', '侧部/连接结构', '侧柜、挡板、走线与上架连接', 3),
('DK', 'D', '桌腿/支撑', '桌面以下的承重结构', 4),
('DK', 'E', '表面材质', '统一引用 material/fabric 字典', 5),
('DK', 'F', '收纳/功能', '抽屉、升降、充电、折叠等可见功能', 6),
('MT', 'A', '整体厚度/轮廓', '床垫整体厚度、层级与可见轮廓', 1),
('MT', 'B', '顶面绗缝/表层', '顶面缝线、花纹与可见分区', 2),
('MT', 'C', '边缘工艺', '床垫上下边缘的包边与收口工艺', 3),
('MT', 'D', '侧围结构', '侧围纹理、拼接、提手与拉链结构', 4),
('MT', 'E', '表层材质', '统一引用 material/fabric 字典', 5),
('MT', 'F', '可见功能/结构特征', '仅记录图片或明确商品文字可确认的功能', 6),
('MR', 'A', '镜面整体轮廓', '镜面主体的几何轮廓与组合方式', 1),
('MR', 'B', '边框形态', '边框宽度、层级、包覆与装饰形态', 2),
('MR', 'C', '镜面/边缘工艺', '镜面颜色、分割与磨边、车边等工艺', 3),
('MR', 'D', '安装/支撑', '壁挂、落地、台面、悬挂与嵌墙方式', 4),
('MR', 'E', '边框/主体材质', '统一引用 material/fabric 字典', 5),
('MR', 'F', '附加功能', '灯光、储物、角度调节与智能显示等', 6),
('RG', 'A', '外轮廓', '地毯平面外轮廓与模块组合方式', 1),
('RG', 'B', '图案构成', '毯面主要图案、色块与构图类型', 2),
('RG', 'C', '边缘处理', '直切、包边、锁边、流苏与随形边', 3),
('RG', 'D', '绒高/织造表面', '平织、绒高、圈割结构与立体纹理', 4),
('RG', 'E', '材质', '统一引用 material/fabric 字典', 5),
('RG', 'F', '工艺/功能', '可确认的织造工艺、洗护、耐候与防滑功能', 6),
('PD', 'A', '整体组合形态', '屏风片数、围合、滑动、悬挂与架体形态', 1),
('PD', 'B', '面板结构', '实体板、格栅、镂空、编织、玻璃与声学面', 2),
('PD', 'C', '连接结构', '合页、转轴、插接、轨道与吊线等面板连接', 3),
('PD', 'D', '安装/底座', '落地脚、脚轮、顶地支撑、壁固、吊装与地轨', 4),
('PD', 'E', '表面材质', '统一引用 material/fabric 字典', 5),
('PD', 'F', '附加功能', '折叠、移动、置物、挂衣、吸音、展示与绿植等', 6),
('CW', 'A', '窗饰类型', '开合帘、纱帘、罗马帘、卷帘、百叶等主体类型', 1),
('CW', 'B', '帘头/上部结构', '挂钩、波浪、穿杆、孔环、轨道带与帘盒', 2),
('CW', 'C', '帘身褶型/组合', '单双层、开合方向、波浪褶与分幅拼接', 3),
('CW', 'D', '安装/驱动', '帘杆、壁装/顶装轨道、内装、手动与电动驱动', 4),
('CW', 'E', '面料/主体材质', '统一引用 material/fabric 字典', 5),
('CW', 'F', '遮光/功能', '遮光、隐私、隔热、吸音、电动、阻燃与洗护功能', 6)
ON CONFLICT (category_code, dim_key) DO NOTHING;
-- <<< PRODUCT_TAXONOMY_SEED <<<

-- >>> PRODUCT_ATTRIBUTE_KNOWLEDGE_SEED (generated by scripts/generate_product_attribute_seed.js, do not edit manually) >>>
-- 旧九类一级品类元数据：保留既有兼容别名，并补齐分类边界说明
UPDATE category_dict SET aliases = '["椅子","凳子","单椅","餐椅","靠背椅","休闲椅","扶手椅","长凳","床尾凳","脚踏","坐墩","矮凳"]', remark = '供坐卧但不具备典型多人沙发结构的座具；排除吧椅、办公职级椅和床。凳子、单人沙发等歧义词需结合座高、体量和结构复核。'
WHERE dict_type = 'category' AND dict_code = 'FS';
UPDATE category_dict SET aliases = '["沙发","沙发椅","单人沙发","多人沙发","双人沙发","三人沙发","转角沙发","组合沙发","模块沙发","沙发床","贵妃榻"]', remark = '以连续软包坐面、多人尺度或沙发式模块为主要特征；单人沙发与扶手椅边界需结合整体体量、连续底座和软包结构判定。'
WHERE dict_type = 'category' AND dict_code = 'SF';
UPDATE category_dict SET aliases = '["茶几","咖啡桌","客厅几","边几","角几","套几","子母几","沙发边桌","玄关台"]', remark = '客厅或休闲区使用的低桌和辅助桌；排除餐桌、书桌、办公桌及以收纳为主的柜类。'
WHERE dict_type = 'category' AND dict_code = 'TB';
UPDATE category_dict SET aliases = '["柜类","柜子","收纳柜","电视柜","餐边柜","床头柜","斗柜","衣柜","书柜","鞋柜","展示柜"]', remark = '以封闭或半封闭收纳、陈列为主要功能的柜体；排除只有开放工作台面的桌台类。'
WHERE dict_type = 'category' AND dict_code = 'FC';
UPDATE category_dict SET aliases = '["吧椅","吧凳","高脚椅","高脚凳","中岛凳","升降吧椅"]', remark = '明显高于普通座椅、面向吧台或厨房中岛使用的座具；需结合座高与脚踏结构区别于 FS 普通座椅。'
WHERE dict_type = 'category' AND dict_code = 'BS';
UPDATE category_dict SET aliases = '["办公家具","办公桌","办公椅","班台","班椅","工位","会议桌","培训桌","文件柜"]', remark = '具有明确办公场景、工位系统或职级语义的家具；家庭书房桌优先归入 DK，通用餐桌归入 DT。'
WHERE dict_type = 'category' AND dict_code = 'OF';
UPDATE category_dict SET aliases = '["餐桌","饭桌","圆餐桌","长餐桌","伸缩餐桌","宴会桌","吧台桌","岛台餐桌"]', remark = '以用餐高度和多人围坐为主要特征的桌台；排除茶几、书桌、办公会议桌和独立厨房岛柜。'
WHERE dict_type = 'category' AND dict_code = 'DT';
UPDATE category_dict SET aliases = '["床","床架","软包床","平台床","储物床","上下床","高架床","儿童床"]', remark = '指床架和完整床体，不包含独立床垫；独立床垫归入 MT，日间坐卧家具需结合是否具有正式睡眠承托结构判断。'
WHERE dict_type = 'category' AND dict_code = 'BD';
UPDATE category_dict SET aliases = '["灯具","灯饰","吊灯","吸顶灯","壁灯","落地灯","台灯","轨道灯","筒灯","射灯"]', remark = '具有独立照明功能及灯体、光源或安装结构的产品；排除仅发光但主要用途不是照明的装饰物。'
WHERE dict_type = 'category' AND dict_code = 'LT';

-- 补充二级产品类型：知识层旁路，不关联 RSPU，不参与编码/报价/订单/同款
INSERT INTO knowledge_product_type
    (type_code, type_name, business_category_code, schema_category_code, parent_type_code, aliases, room_tags, description, sort_order) VALUES
('DINING_BENCH', '餐凳', 'FS', 'FS', 'BENCH', '["餐桌长凳","餐厅长凳"]'::jsonb, '["DINING_ROOM"]'::jsonb, '与餐桌配套的多人长条座具', 1001),
('SHOE_BENCH', '换鞋凳', 'FS', 'FS', 'BENCH', '["玄关凳","鞋柜凳"]'::jsonb, '["ENTRYWAY"]'::jsonb, '供入户换鞋使用的凳类', 1002),
('WINDOW_BENCH', '窗边凳', 'FS', 'FS', 'BENCH', '["飘窗凳","窗台凳"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '靠窗或飘窗区域使用的长凳', 1003),
('VANITY_STOOL', '梳妆凳', 'FS', 'FS', 'STOOL', '["化妆凳","妆凳"]'::jsonb, '["BEDROOM"]'::jsonb, '与梳妆台配套的低凳', 1004),
('BATHROOM_VANITY', '浴室柜', 'FC', 'FC', NULL, '["洗手台柜","卫浴柜"]'::jsonb, '["BATHROOM"]'::jsonb, '与洗手盆组合的浴室收纳柜', 1005),
('LINEN_CABINET', '布草柜', 'FC', 'FC', NULL, '["毛巾柜","床品柜"]'::jsonb, '["BATHROOM","BEDROOM"]'::jsonb, '用于收纳毛巾、床品等布草的柜体', 1006),
('KIDS_STORAGE_CABINET', '儿童收纳柜', 'FC', 'FC', NULL, '["玩具柜","儿童柜"]'::jsonb, '["KIDS_ROOM"]'::jsonb, '适合儿童高度和安全要求的收纳柜', 1007),
('CRIB', '婴儿床', 'BD', 'BD', NULL, '["宝宝床","围栏婴儿床"]'::jsonb, '["KIDS_ROOM"]'::jsonb, '四周具有防护围栏的婴幼儿睡眠床', 1008),
('MURPHY_BED', '壁床/墨菲床', 'BD', 'BD', NULL, '["翻板床","隐藏床"]'::jsonb, '["BEDROOM","GUEST_ROOM"]'::jsonb, '可向墙柜方向翻起收纳的床', 1009),
('ADJUSTABLE_BED', '电动调节床', 'BD', 'BD', NULL, '["护理床","升降床"]'::jsonb, '["BEDROOM"]'::jsonb, '床面角度或高度可电动调节的床', 1010),
('VANITY_LIGHT', '镜前灯', 'LT', 'LT', NULL, '["浴室镜灯","化妆镜灯"]'::jsonb, '["BATHROOM","BEDROOM"]'::jsonb, '安装于镜面周边或上方的局部照明灯', 1011),
('CABINET_LIGHT', '柜内灯', 'LT', 'LT', NULL, '["橱柜灯","层板灯"]'::jsonb, '["KITCHEN","CLOAKROOM"]'::jsonb, '安装于柜内或层板下方的辅助灯具', 1012),
('OUTDOOR_LIGHT', '户外灯', 'LT', 'LT', NULL, '["户外壁灯","防水灯"]'::jsonb, '["OUTDOOR","BALCONY"]'::jsonb, '具备户外耐候用途的照明灯具', 1013),
('GARDEN_LIGHT', '庭院灯', 'LT', 'LT', NULL, '["草坪灯","景观灯"]'::jsonb, '["OUTDOOR"]'::jsonb, '用于庭院、步道或草坪的景观照明', 1014),
('DRESSING_TABLE', '梳妆台', 'DK', 'DK', NULL, '["化妆台","妆台"]'::jsonb, '["BEDROOM"]'::jsonb, '以梳妆、化妆和小件收纳为主的桌台', 1015),
('DRAFTING_TABLE', '绘图桌', 'DK', 'DK', NULL, '["制图桌","画图桌"]'::jsonb, '["STUDY","STUDIO"]'::jsonb, '台面角度可调或适合绘图制图的工作桌', 1016),
('GAMING_DESK', '游戏桌', 'DK', 'DK', NULL, '["电竞桌","电竞电脑桌"]'::jsonb, '["STUDY","BEDROOM"]'::jsonb, '面向电脑游戏设备、走线和外设布置的桌台', 1017),
('KIDS_STUDY_DESK', '儿童学习桌', 'DK', 'DK', NULL, '["学生桌","成长书桌"]'::jsonb, '["KIDS_ROOM","STUDY"]'::jsonb, '适合儿童身高并常带高度调节的学习桌', 1018),
('MATTRESS_TOPPER', '床垫薄垫/保护层', 'MT', 'MT', NULL, '["床褥","床垫加垫"]'::jsonb, '["BEDROOM"]'::jsonb, '铺设于主床垫上方的较薄舒适层', 1019),
('AIR_MATTRESS', '充气床垫', 'MT', 'MT', NULL, '["气垫床","充气床"]'::jsonb, '["GUEST_ROOM","OUTDOOR"]'::jsonb, '通过充气形成承托结构的便携床垫', 1020),
('KIDS_MATTRESS', '儿童床垫', 'MT', 'MT', NULL, '["婴儿床垫","青少年床垫"]'::jsonb, '["KIDS_ROOM"]'::jsonb, '适配婴童或儿童床规格的床垫', 1021),
('TABLE_MIRROR', '台镜', 'MR', 'MR', NULL, '["桌面镜","折叠台镜"]'::jsonb, '["BEDROOM","BATHROOM"]'::jsonb, '依靠底座放置于桌面的可移动镜子', 1022),
('LIGHTED_MIRROR', '灯光镜', 'MR', 'MR', NULL, '["发光镜","LED镜"]'::jsonb, '["BATHROOM","BEDROOM"]'::jsonb, '镜面或边框集成照明的镜子', 1023),
('IRREGULAR_RUG', '异形地毯', 'RG', 'RG', 'AREA_RUG', '["不规则地毯","造型毯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '外轮廓为自由曲线或非规则几何形的区域毯', 1024),
('CARPET_TILE', '拼块地毯', 'RG', 'RG', NULL, '["方块地毯","地毯砖"]'::jsonb, '["OFFICE","COMMERCIAL"]'::jsonb, '由标准化小块拼铺的地毯系统', 1025),
('WALL_TO_WALL_CARPET', '满铺地毯', 'RG', 'RG', NULL, '["工程地毯","整屋地毯"]'::jsonb, '["BEDROOM","OFFICE"]'::jsonb, '按房间边界连续铺设的地毯', 1026),
('HIDE_RUG', '皮毛毯', 'RG', 'RG', NULL, '["牛皮毯","羊皮毯"]'::jsonb, '["LIVING_ROOM","BEDROOM"]'::jsonb, '保留动物皮张或仿皮毛自然轮廓的地毯', 1027),
('HANGING_DIVIDER', '悬挂隔断', 'PD', 'PD', NULL, '["吊挂隔断","吊屏"]'::jsonb, '["LIVING_ROOM","COMMERCIAL"]'::jsonb, '由顶部吊挂、底部不落地的空间隔断', 1028),
('GRILLE_PARTITION', '格栅隔断', 'PD', 'PD', NULL, '["木格栅","竖条隔断"]'::jsonb, '["LIVING_ROOM","ENTRYWAY"]'::jsonb, '由重复条杆构成的半通透隔断', 1029),
('FIXED_SCREEN', '固定屏风', 'PD', 'PD', NULL, '["固定隔断","落地固定屏"]'::jsonb, '["LIVING_ROOM","OFFICE"]'::jsonb, '固定于地面、墙面或顶面的非活动屏风', 1030),
('VERTICAL_BLIND', '垂直帘', 'CW', 'CW', NULL, '["竖百叶","垂直百叶"]'::jsonb, '["OFFICE","LIVING_ROOM"]'::jsonb, '由多片竖向叶片并排悬挂的窗饰', 1031),
('ZEBRA_BLIND', '斑马帘/柔纱帘', 'CW', 'CW', NULL, '["柔纱帘","调光帘"]'::jsonb, '["LIVING_ROOM","STUDY"]'::jsonb, '透纱和遮光条带交替错位调光的卷帘', 1032),
('BAMBOO_BLIND', '竹帘', 'CW', 'CW', NULL, '["竹卷帘","木织帘"]'::jsonb, '["STUDY","BALCONY"]'::jsonb, '以竹木条片或天然纤维编织的窗饰', 1033)
ON CONFLICT (type_code) DO UPDATE SET
    type_name = EXCLUDED.type_name,
    business_category_code = EXCLUDED.business_category_code,
    schema_category_code = EXCLUDED.schema_category_code,
    parent_type_code = EXCLUDED.parent_type_code,
    aliases = EXCLUDED.aliases,
    room_tags = EXCLUDED.room_tags,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 产品属性定义：只定义语义与归属层，不自动回写任何现有产品数据
INSERT INTO knowledge_product_attribute
    (attribute_id, attribute_code, attribute_name, category_code, product_type_code, value_layer, value_type, unit, enum_options, aliases, description, ai_extractable, required_level, sort_order) VALUES
('ATTR-GENERIC-INDOOR_OUTDOOR', 'INDOOR_OUTDOOR', '使用环境', NULL, NULL, 'RSPU', 'enum', NULL, '[{"code":"INDOOR","name":"室内"},{"code":"OUTDOOR","name":"户外"},{"code":"BOTH","name":"室内外通用"}]'::jsonb, '["室内外","户外适用","使用环境"]'::jsonb, '产品设计适用的环境范围，不以单张场景图推断', true, 'recommended', 1),
('ATTR-GENERIC-TARGET_USERS', 'TARGET_USERS', '适用人群', NULL, NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"ADULT","name":"成人"},{"code":"KIDS","name":"儿童"},{"code":"ELDERLY","name":"适老"},{"code":"ACCESSIBLE","name":"无障碍"}]'::jsonb, '["适用对象","人群"]'::jsonb, '产品设计明确面向的人群，可多选', false, 'optional', 2),
('ATTR-GENERIC-INSTALL_METHOD', 'INSTALL_METHOD', '安装方式', NULL, NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"FLOOR","name":"落地"},{"code":"WALL","name":"壁挂"},{"code":"CEILING","name":"吊装"},{"code":"RECESSED","name":"嵌入"},{"code":"HANGING","name":"悬挂"}]'::jsonb, '["固定方式","安装类型"]'::jsonb, '产品设计要求的主要安装或放置方式', true, 'recommended', 3),
('ATTR-GENERIC-MODULARITY', 'MODULARITY', '组合方式', NULL, NULL, 'RSPU', 'enum', NULL, '[{"code":"FIXED","name":"固定单体"},{"code":"MODULAR","name":"模块化"},{"code":"SET","name":"成套组合"}]'::jsonb, '["模块化","组合形式"]'::jsonb, '款式是固定单体、可重组模块还是成套销售组合', true, 'optional', 4),
('ATTR-GENERIC-FUNCTION_TAGS', 'FUNCTION_TAGS', '功能标签', NULL, NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"STORAGE","name":"储物"},{"code":"FOLDING","name":"折叠"},{"code":"EXTENDABLE","name":"伸缩"},{"code":"HEIGHT_ADJUSTABLE","name":"升降"},{"code":"SWIVEL","name":"旋转"},{"code":"RECLINING","name":"躺倒"},{"code":"MOBILE","name":"移动"}]'::jsonb, '["功能","特性"]'::jsonb, '跨品类的稳定功能集合，只有图片或文字明确时记录', true, 'optional', 5),
('ATTR-GENERIC-CARE_METHOD', 'CARE_METHOD', '清洁维护方式', NULL, NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"WIPE_CLEAN","name":"擦拭清洁"},{"code":"DRY_CLEAN","name":"干洗"},{"code":"HAND_WASH","name":"手洗"},{"code":"MACHINE_WASH","name":"机洗"},{"code":"PROFESSIONAL","name":"专业维护"}]'::jsonb, '["洗护","保养方式"]'::jsonb, '商品说明明确给出的清洁维护要求', false, 'optional', 6),
('ATTR-GENERIC-ASSEMBLY_MODE', 'ASSEMBLY_MODE', '交付组装方式', NULL, NULL, 'RSKU', 'enum', NULL, '[{"code":"ASSEMBLED","name":"整装"},{"code":"PARTIAL","name":"部分组装"},{"code":"KNOCK_DOWN","name":"拆装包装"},{"code":"PROFESSIONAL","name":"专业安装"}]'::jsonb, '["安装服务","组装要求"]'::jsonb, '具体供应单元的包装交付和安装要求', false, 'optional', 7),
('ATTR-GENERIC-CERTIFICATION_TAGS', 'CERTIFICATION_TAGS', '认证标签', NULL, NULL, 'RSKU', 'multi_enum', NULL, '[{"code":"FSC","name":"FSC"},{"code":"BSCI","name":"BSCI"},{"code":"GREENGUARD","name":"GREENGUARD"},{"code":"CE","name":"CE"},{"code":"ROHS","name":"RoHS"},{"code":"OTHER","name":"其他"}]'::jsonb, '["资质","认证"]'::jsonb, '工厂或具体供应单元可提供证明材料的认证', false, 'optional', 8),
('ATTR-FS-SEAT_COUNT', 'SEAT_COUNT', '座位数', 'FS', NULL, 'RSPU', 'integer', 'seat', '[]'::jsonb, '["几人位","座数"]'::jsonb, '正常坐姿下的设计座位数量', true, 'recommended', 1),
('ATTR-FS-SEAT_HEIGHT_MM', 'SEAT_HEIGHT_MM', '座高', 'FS', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["坐高","座面高"]'::jsonb, '地面至主要座面的垂直高度', false, 'recommended', 2),
('ATTR-FS-MAX_LOAD_KG', 'MAX_LOAD_KG', '最大承重', 'FS', NULL, 'VARIANT', 'decimal', 'kg', '[]'::jsonb, '["承重","载重"]'::jsonb, '有测试或规格依据的最大静态承重', false, 'optional', 3),
('ATTR-FS-STACKABLE', 'STACKABLE', '可堆叠', 'FS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["叠放","可摞放"]'::jsonb, '椅凳是否设计为可稳定堆叠收纳', true, 'optional', 4),
('ATTR-FS-SWIVEL', 'SWIVEL', '可旋转', 'FS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["转椅","旋转底座"]'::jsonb, '座面或底座是否具备旋转机构', true, 'optional', 5),
('ATTR-FS-RECLINING', 'RECLINING', '可躺调节', 'FS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["躺倒","靠背调节"]'::jsonb, '靠背或脚托是否具备躺倒调节功能', true, 'optional', 6),
('ATTR-SF-SEAT_COUNT', 'SEAT_COUNT', '座位数', 'SF', NULL, 'RSPU', 'integer', 'seat', '[]'::jsonb, '["几人位","座数"]'::jsonb, '沙发正常坐姿下的设计座位数量', true, 'recommended', 1),
('ATTR-SF-SEAT_HEIGHT_MM', 'SEAT_HEIGHT_MM', '座高', 'SF', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["坐高","座面高"]'::jsonb, '地面至主要座面的垂直高度', false, 'optional', 2),
('ATTR-SF-SECTION_COUNT', 'SECTION_COUNT', '模块/分段数', 'SF', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["模块数","组合件数"]'::jsonb, '当前组合变体包含的独立沙发模块或分段数量', true, 'optional', 3),
('ATTR-SF-RECLINING', 'RECLINING', '功能躺位', 'SF', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["电动躺位","脚托"]'::jsonb, '是否具有可展开脚托或靠背躺倒机构', true, 'optional', 4),
('ATTR-TB-TABLETOP_HEIGHT_MM', 'TABLETOP_HEIGHT_MM', '台面高度', 'TB', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["几高","桌面高"]'::jsonb, '地面至主要台面的高度', false, 'recommended', 1),
('ATTR-TB-NESTING_COUNT', 'NESTING_COUNT', '套几件数', 'TB', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["组合数量","几件套"]'::jsonb, '套几或组合几包含的单体数量', true, 'optional', 2),
('ATTR-TB-MOBILE', 'MOBILE', '可移动', 'TB', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["带轮","移动茶几"]'::jsonb, '是否设计有脚轮或专用移动结构', true, 'optional', 3),
('ATTR-FC-DOOR_COUNT', 'DOOR_COUNT', '门扇数', 'FC', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["柜门数","门数"]'::jsonb, '当前变体可开启的柜门数量', true, 'optional', 1),
('ATTR-FC-DRAWER_COUNT', 'DRAWER_COUNT', '抽屉数', 'FC', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["斗数","抽数"]'::jsonb, '当前变体独立抽屉数量', true, 'optional', 2),
('ATTR-FC-SHELF_COUNT', 'SHELF_COUNT', '层板数', 'FC', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["隔板数","层数"]'::jsonb, '当前变体可用层板数量', false, 'optional', 3),
('ATTR-FC-MOUNTING_TYPE', 'MOUNTING_TYPE', '柜体安装类型', 'FC', NULL, 'RSPU', 'enum', NULL, '[{"code":"FREESTANDING","name":"独立落地"},{"code":"WALL_MOUNTED","name":"壁挂"},{"code":"BUILT_IN","name":"嵌入/固定"}]'::jsonb, '["柜体固定方式","落地壁挂"]'::jsonb, '柜体主要安装方式', true, 'recommended', 4),
('ATTR-FC-TV_SIZE_INCH', 'TV_SIZE_INCH', '适配电视尺寸', 'FC', NULL, 'VARIANT', 'decimal', 'inch', '[]'::jsonb, '["电视英寸","适配屏幕"]'::jsonb, '电视柜明确标注可适配的最大电视尺寸', false, 'optional', 5),
('ATTR-BS-SEAT_HEIGHT_MM', 'SEAT_HEIGHT_MM', '座高', 'BS', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["吧椅高度","坐高"]'::jsonb, '地面至座面的垂直高度，是区分吧椅与普通座椅的关键值', false, 'required', 1),
('ATTR-BS-HEIGHT_ADJUSTABLE', 'HEIGHT_ADJUSTABLE', '高度可调', 'BS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["升降","可调高"]'::jsonb, '座面高度是否可调', true, 'optional', 2),
('ATTR-BS-HAS_FOOTREST', 'HAS_FOOTREST', '带脚踏', 'BS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["脚踏环","搁脚"]'::jsonb, '是否具有脚踏环或横向搁脚杆', true, 'optional', 3),
('ATTR-BS-SWIVEL', 'SWIVEL', '可旋转', 'BS', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["旋转吧椅","转动"]'::jsonb, '座面是否可围绕中柱旋转', true, 'optional', 4),
('ATTR-BS-MAX_LOAD_KG', 'MAX_LOAD_KG', '最大承重', 'BS', NULL, 'VARIANT', 'decimal', 'kg', '[]'::jsonb, '["承重","载重"]'::jsonb, '有测试或规格依据的最大静态承重', false, 'optional', 5),
('ATTR-OF-COMMERCIAL_GRADE', 'COMMERCIAL_GRADE', '商用强度等级', 'OF', NULL, 'RSKU', 'enum', NULL, '[{"code":"HOME","name":"家用"},{"code":"LIGHT_COMMERCIAL","name":"轻商用"},{"code":"COMMERCIAL","name":"商用"},{"code":"CONTRACT","name":"工程级"}]'::jsonb, '["结构等级","商用等级"]'::jsonb, '具体供应单元可验证的使用强度等级', false, 'optional', 1),
('ATTR-OF-CABLE_MANAGEMENT', 'CABLE_MANAGEMENT', '走线管理', 'OF', NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"GROMMET","name":"过线孔"},{"code":"CABLE_TRAY","name":"走线槽"},{"code":"CABLE_BOX","name":"线盒"},{"code":"VERTICAL_CHANNEL","name":"竖向走线"}]'::jsonb, '["线孔","走线槽"]'::jsonb, '桌台可见或规格明确的线缆管理结构', true, 'optional', 2),
('ATTR-OF-HEIGHT_ADJUSTABLE', 'HEIGHT_ADJUSTABLE', '高度可调', 'OF', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["升降办公桌","升降椅"]'::jsonb, '桌面或座面高度是否可调，需结合 product_type 理解', true, 'optional', 3),
('ATTR-OF-WORKSTATION_CAPACITY', 'WORKSTATION_CAPACITY', '工位人数', 'OF', NULL, 'VARIANT', 'integer', 'seat', '[]'::jsonb, '["几人位工位","工位数"]'::jsonb, '工位系统当前组合可容纳的人数', false, 'optional', 4),
('ATTR-DT-TABLETOP_HEIGHT_MM', 'TABLETOP_HEIGHT_MM', '台面高度', 'DT', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["餐桌高","桌面高"]'::jsonb, '地面至主要用餐台面的高度', false, 'recommended', 1),
('ATTR-DT-SEATING_CAPACITY', 'SEATING_CAPACITY', '建议用餐人数', 'DT', NULL, 'VARIANT', 'integer', 'seat', '[]'::jsonb, '["几人桌","容纳人数"]'::jsonb, '当前尺寸变体建议同时用餐的人数', false, 'recommended', 2),
('ATTR-DT-EXTENDABLE', 'EXTENDABLE', '可伸缩', 'DT', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["延长桌","拉伸桌"]'::jsonb, '台面是否可通过拉伸、翻板等方式扩展', true, 'optional', 3),
('ATTR-BD-MATTRESS_SIZE', 'MATTRESS_SIZE', '适配床垫规格', 'BD', NULL, 'VARIANT', 'text', NULL, '[]'::jsonb, '["床垫尺寸","床规格"]'::jsonb, '床架适配的标准或原始床垫尺寸表达', false, 'required', 1),
('ATTR-BD-SLEEPING_CAPACITY', 'SLEEPING_CAPACITY', '睡眠人数', 'BD', NULL, 'VARIANT', 'integer', 'person', '[]'::jsonb, '["单人双人","容纳人数"]'::jsonb, '当前尺寸变体设计容纳的睡眠人数', false, 'optional', 2),
('ATTR-BD-UNDERBED_STORAGE', 'UNDERBED_STORAGE', '床下储物', 'BD', NULL, 'RSPU', 'enum', NULL, '[{"code":"NONE","name":"无"},{"code":"DRAWER","name":"抽屉储物"},{"code":"LIFT_UP","name":"掀床储物"},{"code":"OPEN","name":"开放置物"}]'::jsonb, '["高箱床","床下抽屉"]'::jsonb, '床下储物的主要结构形式', true, 'optional', 3),
('ATTR-BD-HEADBOARD_HEIGHT_MM', 'HEADBOARD_HEIGHT_MM', '床头高度', 'BD', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["靠背高","床屏高度"]'::jsonb, '地面至床头最高点的垂直高度', false, 'optional', 4),
('ATTR-LT-LIGHT_SOURCE', 'LIGHT_SOURCE', '光源类型', 'LT', NULL, 'VARIANT', 'enum', NULL, '[{"code":"INTEGRATED_LED","name":"集成 LED"},{"code":"REPLACEABLE_BULB","name":"可更换灯泡"},{"code":"LIGHT_STRIP","name":"灯带"},{"code":"OTHER","name":"其他"}]'::jsonb, '["灯芯","光源"]'::jsonb, '当前灯具变体使用的主要光源形式', false, 'recommended', 1),
('ATTR-LT-BULB_BASE', 'BULB_BASE', '灯头接口', 'LT', NULL, 'VARIANT', 'text', NULL, '[]'::jsonb, '["灯口","灯座"]'::jsonb, '如 E27、E14、GU10 等标准灯头接口', false, 'optional', 2),
('ATTR-LT-POWER_W', 'POWER_W', '功率', 'LT', NULL, 'VARIANT', 'decimal', 'W', '[]'::jsonb, '["瓦数","额定功率"]'::jsonb, '当前灯具变体的额定功率', false, 'optional', 3),
('ATTR-LT-COLOR_TEMPERATURE_K', 'COLOR_TEMPERATURE_K', '色温', 'LT', NULL, 'VARIANT', 'integer', 'K', '[]'::jsonb, '["光色","开尔文"]'::jsonb, '当前灯具变体标注的色温', false, 'optional', 4),
('ATTR-LT-DIMMABLE', 'DIMMABLE', '可调光', 'LT', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["调光","亮度调节"]'::jsonb, '是否支持亮度调节', false, 'optional', 5),
('ATTR-LT-VOLTAGE_V', 'VOLTAGE_V', '额定电压', 'LT', NULL, 'VARIANT', 'text', 'V', '[]'::jsonb, '["电压","输入电压"]'::jsonb, '当前灯具变体适用的电压范围', false, 'optional', 6),
('ATTR-LT-IP_RATING', 'IP_RATING', '防护等级', 'LT', NULL, 'RSKU', 'text', NULL, '[]'::jsonb, '["防水等级","IP等级"]'::jsonb, '具体供应单元具备检测或规格依据的 IP 防护等级', false, 'optional', 7),
('ATTR-DK-TABLETOP_HEIGHT_MM', 'TABLETOP_HEIGHT_MM', '台面高度', 'DK', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["桌面高","书桌高"]'::jsonb, '地面至主要工作台面的高度', false, 'recommended', 1),
('ATTR-DK-CABLE_MANAGEMENT', 'CABLE_MANAGEMENT', '走线管理', 'DK', NULL, 'RSPU', 'multi_enum', NULL, '[{"code":"GROMMET","name":"过线孔"},{"code":"CABLE_TRAY","name":"走线槽"},{"code":"CABLE_BOX","name":"线盒"},{"code":"VERTICAL_CHANNEL","name":"竖向走线"}]'::jsonb, '["线孔","走线槽"]'::jsonb, '可见或规格明确的线缆管理结构', true, 'optional', 2),
('ATTR-DK-HEIGHT_ADJUSTABLE', 'HEIGHT_ADJUSTABLE', '高度可调', 'DK', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["成长桌","升降桌"]'::jsonb, '工作台面高度是否可调', true, 'optional', 3),
('ATTR-DK-TILT_ADJUSTABLE', 'TILT_ADJUSTABLE', '台面角度可调', 'DK', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["斜面调节","绘图桌角度"]'::jsonb, '台面是否可改变倾斜角度', true, 'optional', 4),
('ATTR-MT-THICKNESS_MM', 'THICKNESS_MM', '床垫厚度', 'MT', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["垫厚","总厚度"]'::jsonb, '床垫当前变体的整体厚度', false, 'required', 1),
('ATTR-MT-FIRMNESS', 'FIRMNESS', '软硬度', 'MT', NULL, 'VARIANT', 'enum', NULL, '[{"code":"SOFT","name":"偏软"},{"code":"MEDIUM_SOFT","name":"中软"},{"code":"MEDIUM","name":"适中"},{"code":"MEDIUM_FIRM","name":"中硬"},{"code":"FIRM","name":"偏硬"}]'::jsonb, '["硬度","睡感"]'::jsonb, '以工厂或品牌规格为依据的软硬度，不从外观猜测', false, 'recommended', 2),
('ATTR-MT-CORE_STRUCTURE', 'CORE_STRUCTURE', '内芯结构', 'MT', NULL, 'VARIANT', 'multi_enum', NULL, '[{"code":"SPRING","name":"弹簧"},{"code":"FOAM","name":"泡棉"},{"code":"LATEX","name":"乳胶"},{"code":"COIR","name":"椰棕"},{"code":"AIR","name":"充气"}]'::jsonb, '["内材","承托层"]'::jsonb, '仅依据剖面图或明确文字记录的内部承托结构', false, 'optional', 3),
('ATTR-MT-REVERSIBLE', 'REVERSIBLE', '双面可用', 'MT', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["双面睡感","正反两用"]'::jsonb, '床垫是否明确支持翻面使用', false, 'optional', 4),
('ATTR-MT-REMOVABLE_COVER', 'REMOVABLE_COVER', '外套可拆洗', 'MT', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["可拆套","拉链外套"]'::jsonb, '外层套是否可拆卸清洗', true, 'optional', 5),
('ATTR-MR-MOUNTING_TYPE', 'MOUNTING_TYPE', '镜子安装方式', 'MR', NULL, 'RSPU', 'enum', NULL, '[{"code":"WALL","name":"壁挂"},{"code":"FLOOR","name":"落地"},{"code":"TABLE","name":"台面"},{"code":"RECESSED","name":"嵌入"}]'::jsonb, '["支撑方式","挂装方式"]'::jsonb, '镜子的主要安装或支撑方式', true, 'recommended', 1),
('ATTR-MR-LIGHTING', 'LIGHTING', '集成灯光', 'MR', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["LED镜","发光镜"]'::jsonb, '镜体是否集成照明', true, 'optional', 2),
('ATTR-MR-ANTI_FOG', 'ANTI_FOG', '防雾', 'MR', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["除雾","电加热防雾"]'::jsonb, '是否具有明确的防雾或除雾功能', false, 'optional', 3),
('ATTR-MR-MAGNIFICATION', 'MAGNIFICATION', '放大倍率', 'MR', NULL, 'VARIANT', 'decimal', 'x', '[]'::jsonb, '["放大镜倍率","倍数"]'::jsonb, '化妆镜等产品明确标注的放大倍率', false, 'optional', 4),
('ATTR-RG-PILE_HEIGHT_MM', 'PILE_HEIGHT_MM', '绒高', 'RG', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["毛高","绒毛高度"]'::jsonb, '地毯表面绒毛的标称高度', false, 'optional', 1),
('ATTR-RG-WEAVE_METHOD', 'WEAVE_METHOD', '织造方式', 'RG', NULL, 'RSPU', 'enum', NULL, '[{"code":"HAND_KNOTTED","name":"手工打结"},{"code":"HAND_TUFTED","name":"手工簇绒"},{"code":"MACHINE_WOVEN","name":"机织"},{"code":"FLAT_WOVEN","name":"平织"},{"code":"BRAIDED","name":"编织"}]'::jsonb, '["工艺","织法"]'::jsonb, '仅依据工艺图或明确文字记录的织造方式', false, 'optional', 2),
('ATTR-RG-BACKING_TYPE', 'BACKING_TYPE', '背衬类型', 'RG', NULL, 'VARIANT', 'text', NULL, '[]'::jsonb, '["毯底","背胶"]'::jsonb, '地毯背面的基布、防滑层或背胶描述', false, 'optional', 3),
('ATTR-RG-MACHINE_WASHABLE', 'MACHINE_WASHABLE', '可机洗', 'RG', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["洗衣机可洗","机洗毯"]'::jsonb, '商品说明是否明确允许机洗', false, 'optional', 4),
('ATTR-RG-AREA_SQM', 'AREA_SQM', '面积', 'RG', NULL, 'VARIANT', 'decimal', 'sqm', '[]'::jsonb, '["平方米","覆盖面积"]'::jsonb, '当前地毯变体的标称覆盖面积', false, 'optional', 5),
('ATTR-RG-CUSTOM_SIZE', 'CUSTOM_SIZE', '支持定制尺寸', 'RG', NULL, 'RSKU', 'boolean', NULL, '[]'::jsonb, '["尺寸定制","可裁切"]'::jsonb, '具体工厂供应是否接受非标准尺寸定制', false, 'optional', 6),
('ATTR-PD-PANEL_COUNT', 'PANEL_COUNT', '面板数量', 'PD', NULL, 'VARIANT', 'integer', 'piece', '[]'::jsonb, '["几联","片数"]'::jsonb, '当前隔断变体包含的独立面板数量', true, 'optional', 1),
('ATTR-PD-FOLDABLE', 'FOLDABLE', '可折叠', 'PD', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["折屏","收折"]'::jsonb, '面板是否通过铰接结构折叠收拢', true, 'optional', 2),
('ATTR-PD-MOBILE', 'MOBILE', '可移动', 'PD', NULL, 'RSPU', 'boolean', NULL, '[]'::jsonb, '["带轮隔断","移动屏风"]'::jsonb, '是否设计有脚轮或其他移动结构', true, 'optional', 3),
('ATTR-PD-ACOUSTIC_RATING', 'ACOUSTIC_RATING', '声学指标', 'PD', NULL, 'RSKU', 'text', NULL, '[]'::jsonb, '["吸音系数","降噪等级"]'::jsonb, '具体供应单元有检测或规格依据的声学性能指标', false, 'optional', 4),
('ATTR-CW-FINISHED_WIDTH_MM', 'FINISHED_WIDTH_MM', '成品宽度', 'CW', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["帘宽","成品宽"]'::jsonb, '当前窗饰成品展开后的标称宽度', false, 'required', 1),
('ATTR-CW-FINISHED_DROP_MM', 'FINISHED_DROP_MM', '成品高度/帘长', 'CW', NULL, 'VARIANT', 'decimal', 'mm', '[]'::jsonb, '["帘高","帘长","下垂高度"]'::jsonb, '当前窗饰成品的标称垂直尺寸', false, 'required', 2),
('ATTR-CW-OPENING_MODE', 'OPENING_MODE', '开合方式', 'CW', NULL, 'RSPU', 'enum', NULL, '[{"code":"LEFT_RIGHT","name":"左右开合"},{"code":"UP_DOWN","name":"上下收放"},{"code":"ROTATE","name":"叶片旋转"},{"code":"FIXED","name":"固定"}]'::jsonb, '["开启方式","收帘方式"]'::jsonb, '窗饰主体的主要开合运动方式', true, 'optional', 3),
('ATTR-CW-BLACKOUT_LEVEL', 'BLACKOUT_LEVEL', '遮光等级', 'CW', NULL, 'VARIANT', 'enum', NULL, '[{"code":"SHEER","name":"透光"},{"code":"LIGHT_FILTERING","name":"半遮光"},{"code":"HIGH_BLACKOUT","name":"高遮光"},{"code":"BLACKOUT","name":"全遮光"}]'::jsonb, '["遮光率","透光性"]'::jsonb, '以检测或商品明确说明为依据的遮光等级', false, 'recommended', 4),
('ATTR-CW-MOTOR_CONTROL', 'MOTOR_CONTROL', '电机控制方式', 'CW', NULL, 'RSKU', 'multi_enum', NULL, '[{"code":"REMOTE","name":"遥控器"},{"code":"APP","name":"App"},{"code":"VOICE","name":"语音"},{"code":"WALL_SWITCH","name":"墙面开关"},{"code":"SMART_HOME","name":"智能家居接入"}]'::jsonb, '["智能控制","电动窗帘控制"]'::jsonb, '具体供应单元支持的电机控制与智能接入方式', false, 'optional', 5)
ON CONFLICT (attribute_id) DO UPDATE SET
    attribute_code = EXCLUDED.attribute_code,
    attribute_name = EXCLUDED.attribute_name,
    category_code = EXCLUDED.category_code,
    product_type_code = EXCLUDED.product_type_code,
    value_layer = EXCLUDED.value_layer,
    value_type = EXCLUDED.value_type,
    unit = EXCLUDED.unit,
    enum_options = EXCLUDED.enum_options,
    aliases = EXCLUDED.aliases,
    description = EXCLUDED.description,
    ai_extractable = EXCLUDED.ai_extractable,
    required_level = EXCLUDED.required_level,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;
-- <<< PRODUCT_ATTRIBUTE_KNOWLEDGE_SEED <<<

-- 工厂等级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('factory_level', 'S', 'S级战略厂', 1),
('factory_level', 'A', 'A级核心厂', 2),
('factory_level', 'B', 'B级合作厂', 3),
('factory_level', 'C', 'C级备选厂', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 复核状态
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('review_status', '待复核', '待复核', 1),
('review_status', '已确认', '已确认', 2),
('review_status', '存疑', '存疑', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 报价置信度
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('quote_confidence', 'high', '高', 1),
('quote_confidence', 'mid', '中', 2),
('quote_confidence', 'low', '低', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 尺寸码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('size', 'S', '小号', 1),
('size', 'M', '中号', 2),
('size', 'L', '大号', 3),
('size', 'SINGLE', '单人位', 4),
('size', 'DOUBLE', '双人位', 5),
('size', 'TRIPLE', '三人位', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 颜色码（精简为 15 个准确色系，覆盖风格百科高频颜色）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('color', 'CARAMEL', '焦糖棕', 1),
('color', 'BEIGE', '米白/奶油白/燕麦色', 2),
('color', 'CA', '驼色/奶咖色', 3),
('color', 'DB', '深棕/胡桃木色', 4),
('color', 'NATURAL', '原木色', 5),
('color', 'BLACK', '黑色', 6),
('color', 'GRAY', '灰色', 7),
('color', 'NAVY', '藏青/蓝色系', 8),
('color', 'GN', '绿色系', 9),
('color', 'PR', '紫色系', 10),
('color', 'RD', '红色系', 11),
('color', 'OR', '橙色系', 12),
('color', 'PK', '粉色系', 13),
('color', 'YE', '黄色系', 14),
('color', 'WT', '白色', 15)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 场景标签
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('scene', 'LIVING', '客厅', 1),
('scene', 'STUDY', '书房', 2),
('scene', 'BEDROOM', '卧室', 3),
('scene', 'CAFE', '咖啡厅', 4),
('scene', 'OFFICE', '办公室', 5),
('scene', 'HOTEL', '酒店', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 设备类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('equipment_type', 'CNC', 'CNC五轴加工中心', 1),
('equipment_type', 'CUTTING', '自动裁皮机', 2),
('equipment_type', 'SEWING', '数控缝纫机', 3),
('equipment_type', 'HOT_PRESS', '热压机', 4),
('equipment_type', 'PAINTING', '喷漆房', 5),
('equipment_type', 'DRYING', '烘干房', 6),
('equipment_type', 'OTHER', '其他', 7)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 物流方式
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('logistics_method', 'SPECIAL', '专线物流', 1),
('logistics_method', 'DEPPON', '德邦', 2),
('logistics_method', 'SF', '顺丰', 3),
('logistics_method', 'SELF', '自提', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 包装类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('packaging_type', 'CARTON', '纸箱', 1),
('packaging_type', 'WOODEN', '木架', 2),
('packaging_type', 'WOVEN', '编织袋', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 木材类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('wood_type', 'PINE', '进口松木', 1),
('wood_type', 'OAK', '橡木', 2),
('wood_type', 'MIXED', '杂木', 3),
('wood_type', 'OTHER', '其他', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 空间类型（风格数据库 case 用）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('room_type', 'LIVING_ROOM', '客厅', 1),
('room_type', 'BEDROOM', '卧室', 2),
('room_type', 'DINING_ROOM', '餐厅', 3),
('room_type', 'STUDY_ROOM', '书房', 4),
('room_type', 'OFFICE_EXECUTIVE', '总裁办公室', 5),
('room_type', 'OFFICE_STAFF', '职员办公区', 6),
('room_type', 'OFFICE_MEETING', '会议室', 7),
('room_type', 'CAFE', '咖啡厅', 8),
('room_type', 'HOTEL_ROOM', '酒店客房', 9),
('room_type', 'BAR', '酒吧', 10),
('room_type', 'OUTDOOR', '户外', 11)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- =================== 系统权限与角色 ===================

-- 角色
INSERT INTO sys_role (role_code, role_name) VALUES
('ADMIN', '系统管理员'),
('EDITOR', '编辑员'),
('FACTORY_ADMIN', '工厂管理员'),
('DESIGNER', '设计师'),
('USER', '普通用户')
ON CONFLICT (role_code) DO NOTHING;

-- 权限
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('product:read', '查看产品'),
('product:create', '新品录入'),
('product:update', '编辑产品元数据'),
('product:delete', '删除产品'),
('product:review', '复核产品'),
('product:import', '批量导入产品'),
('factory:read', '查看工厂'),
('factory:create', '创建工厂'),
('factory:update', '编辑工厂'),
('factory:delete', '删除工厂'),
('rsku:read', '查看报价'),
('rsku:create', '新增报价'),
('rsku:update', '编辑报价'),
('rsku:delete', '删除报价'),
('rsku:import', '批量导入报价'),
('quote:read', '查看报价单'),
('quote:generate', '生成报价单'),
('quote:export', '导出报价单'),
('scheme:read', '查看搭配方案'),
('scheme:create', '创建搭配方案'),
('scheme:update', '编辑搭配方案'),
('scheme:delete', '删除搭配方案'),
('dict:create', '创建字典项'),
('dict:update', '编辑字典项'),
('pricing:update', '定价规则管理'),
('user:read', '查看用户'),
('user:create', '创建用户'),
('user:update', '编辑用户'),
('user:delete', '删除用户'),
('user:reset-password', '重置密码'),
('admin:async-metrics', '查看异步线程池指标'),
('admin:vector-backfill', '向量回填'),
('collection:read', '查看产品集'),
('collection:create', '创建产品集'),
('collection:update', '编辑产品集'),
('collection:delete', '删除产品集'),
('capability:read', '查看工厂产品能力'),
('capability:create', '创建工厂产品能力'),
('capability:update', '编辑工厂产品能力'),
('capability:delete', '删除工厂产品能力'),
('designer:profile:read', '查看设计师画像'),
('designer:profile:update', '编辑设计师画像'),
('recommendation:score:config:read', '查看推荐打分配置'),
('recommendation:score:config:update', '编辑推荐打分配置'),
('scheme:candidate:read', '查看 AI 推荐候选'),
('scheme:candidate:create', '创建 AI 推荐候选'),
('scheme:candidate:update', '编辑 AI 推荐候选'),
('scheme:candidate:delete', '删除 AI 推荐候选')
ON CONFLICT (permission_code) DO NOTHING;

-- ADMIN 拥有所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- EDITOR：除用户管理和高级 admin 外的全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'EDITOR'
  AND p.permission_code NOT IN ('user:read', 'user:create', 'user:update', 'user:delete', 'user:reset-password', 'admin:async-metrics', 'admin:vector-backfill', 'recommendation:score:config:read', 'recommendation:score:config:update', 'pricing:update')
ON CONFLICT DO NOTHING;

-- FACTORY_ADMIN：自己工厂产品 + 工厂资料维护 + 报价相关 + 导入 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'FACTORY_ADMIN'
  AND p.permission_code IN ('product:read', 'product:create', 'product:update', 'product:import', 'factory:read', 'factory:update', 'rsku:read', 'rsku:create', 'rsku:update', 'rsku:delete', 'rsku:import', 'capability:read')
ON CONFLICT DO NOTHING;

-- DESIGNER：方案/报价 + 产品集自建 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'quote:generate', 'quote:export', 'scheme:read', 'scheme:create', 'scheme:update', 'scheme:delete', 'collection:read', 'collection:create', 'collection:update', 'collection:delete', 'capability:read', 'designer:profile:read', 'designer:profile:update', 'scheme:candidate:read', 'scheme:candidate:create', 'scheme:candidate:update', 'scheme:candidate:delete')
ON CONFLICT DO NOTHING;

-- USER：只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'scheme:read', 'collection:read', 'capability:read')
ON CONFLICT DO NOTHING;

-- =================== 开发测试账号已移至 database/seed_dev_data.sql ===================
-- 本文件只保留生产必需的种子；弱口令测试账号与演示数据（含 TEST 测试工厂）
-- 统一在 database/seed_dev_data.sql，开发/演示环境用 make seed-dev 导入。
-- （ops/reset_db.sql 作为纯开发重置工具，仍内嵌一份测试账号，重置后可直接登录。）

-- =================== V2 新增字典（工厂模块 + 导入增强，并入） ===================

-- 材质等级字典（对应Excel中的价格列名）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material_grade', 'FABRIC_A', 'A级布', 1),
('material_grade', 'FABRIC_AA', 'AA级布', 2),
('material_grade', 'FABRIC_S', 'S级布', 3),
('material_grade', 'FABRIC_SS', 'SS级进口布', 4),
('material_grade', 'LEATHER_HALF', '半皮', 10),
('material_grade', 'LEATHER_A', 'A级全皮', 11),
('material_grade', 'LEATHER_AA', 'AA级全皮', 12),
('material_grade', 'LEATHER_S', 'S级全皮', 13),
('material_grade', 'LEATHER_SS', 'SS级全皮', 14)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 工艺类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('process_type', 'STANDARD', '标准工艺', 1),
('process_type', 'MODULAR', '模块化组合', 2),
('process_type', 'CUSTOM', '非标定制', 3),
('process_type', 'IRREGULAR', '异形/特殊', 4),
('process_type', 'QUICK', '快单/现货', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 导入行状态字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('import_row_status', 'PENDING', '待处理', 1),
('import_row_status', 'PROCESSING', '处理中', 2),
('import_row_status', 'SUCCESS', '成功', 3),
('import_row_status', 'FAILED', '失败', 4),
('import_row_status', 'SKIPPED', '已跳过', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 映射状态字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('mapping_status', 'ACTIVE', '生效中', 1),
('mapping_status', 'PAUSED', '暂停', 2),
('mapping_status', 'DISCONTINUED', '已终止', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 工厂来源类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('factory_source_type', 'MANUAL', '手动录入', 1),
('factory_source_type', 'EXCEL_IMPORT', 'Excel导入', 2),
('factory_source_type', 'API_SYNC', '接口同步', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 导入行类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('import_row_type', 'PRODUCT', '产品型号行', 1),
('import_row_type', 'MODULE', '模块行', 2),
('import_row_type', 'HEADER', '表头行', 3),
('import_row_type', 'UNKNOWN', '未知', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 项目类型字典（V4 并入）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('project_type', 'whole_house', '全屋', 1),
('project_type', 'space', '单空间', 2),
('project_type', 'custom', '定制', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 项目权限（V4 并入；ADMIN 全量与 EDITOR 排除式映射自动覆盖）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('project:read', '查看设计项目'),
('project:create', '创建设计项目'),
('project:update', '编辑设计项目'),
('project:delete', '删除设计项目')
ON CONFLICT (permission_code) DO NOTHING;

-- DESIGNER：项目全量权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- ADMIN / EDITOR：项目全量权限（通用映射先于本权限插入执行，需显式补插）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'EDITOR')
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- USER：项目只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code = 'project:read'
ON CONFLICT DO NOTHING;

-- 订单全局折扣率（V5 并入）
INSERT INTO sys_config (config_key, config_value, remark) VALUES
('order.price_rate', '1', '订单全局折扣率')
ON CONFLICT (config_key) DO NOTHING;

-- 全局加价倍率（V38 并入）
INSERT INTO sys_config (config_key, config_value, remark) VALUES
('pricing.markup.global', '2.5', '全局加价倍率：标准售价 = 成本 × 倍率（RSPU 已录入建议销售价 retail_price 时优先）；仅影响新订单/新报价')
ON CONFLICT (config_key) DO NOTHING;

-- 订单状态字典（V5 并入）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('design_order_status', 'PENDING', '待确认', 1),
('design_order_status', 'CONFIRMED', '已确认', 2),
('design_order_status', 'PRODUCING', '生产中', 3),
('design_order_status', 'COMPLETED', '已完成', 4),
('design_order_status', 'CANCELLED', '已取消', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 订单权限（V5 并入；方案约定 ADMIN + DESIGNER 授予，显式补插）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('order:read', '查看订单'),
('order:create', '创建订单'),
('order:update', '编辑订单'),
('order:delete', '删除订单')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'DESIGNER')
  AND p.permission_code LIKE 'order:%'
ON CONFLICT DO NOTHING;

-- 收藏夹权限（V9 并入）：所有登录角色均可管理自己的收藏
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('favorite:read', '查看我的收藏'),
('favorite:write', '管理我的收藏')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE p.permission_code IN ('favorite:read', 'favorite:write')
ON CONFLICT DO NOTHING;

-- 企业实体迁移（V13 并入，幂等）：company_name/group_name 文本 → 实体（同名企业合并）
INSERT INTO company (company_id, company_name, owner_id)
SELECT 'COM-' || gen_random_uuid()::text,
       dc.company_name,
       (SELECT u.user_id FROM sys_user u
        WHERE u.company_name = dc.company_name
        ORDER BY u.created_at ASC NULLS LAST, u.user_id ASC
        LIMIT 1)
FROM (SELECT DISTINCT company_name FROM sys_user
      WHERE company_name IS NOT NULL AND btrim(company_name) <> '') dc
WHERE NOT EXISTS (SELECT 1 FROM company c WHERE c.company_name = dc.company_name);

UPDATE sys_user u SET company_id = c.company_id
FROM company c
WHERE u.company_id IS NULL AND u.company_name = c.company_name;

INSERT INTO member_group (group_id, company_id, group_name)
SELECT 'GRP-' || gen_random_uuid()::text, c.company_id, dg.group_name
FROM (SELECT DISTINCT company_name, group_name FROM sys_user
      WHERE company_name IS NOT NULL AND btrim(company_name) <> ''
        AND group_name IS NOT NULL AND btrim(group_name) <> '') dg
JOIN company c ON c.company_name = dg.company_name
WHERE NOT EXISTS (SELECT 1 FROM member_group g
                  WHERE g.company_id = c.company_id AND g.group_name = dg.group_name);

UPDATE sys_user u SET group_id = g.group_id
FROM company c
JOIN member_group g ON g.company_id = c.company_id
WHERE u.group_id IS NULL
  AND u.company_name = c.company_name
  AND u.group_name = g.group_name;

-- 收藏夹文件夹迁移（V14 并入，幂等）：group_name 文本 → 文件夹实体（按用户+名称合并）
INSERT INTO favorite_folder (folder_id, user_id, folder_name)
SELECT 'FAVD-' || gen_random_uuid()::text, d.user_id, d.group_name
FROM (SELECT DISTINCT user_id, group_name FROM user_favorite
      WHERE group_name IS NOT NULL AND btrim(group_name) <> '') d
WHERE NOT EXISTS (SELECT 1 FROM favorite_folder f
                  WHERE f.user_id = d.user_id AND f.folder_name = d.group_name);

UPDATE user_favorite uf SET folder_id = f.folder_id
FROM favorite_folder f
WHERE uf.folder_id IS NULL
  AND uf.user_id = f.user_id
  AND uf.group_name = f.folder_name;

-- 模板标签迁移（V14 并入，幂等）：scheme.template_tags JSON → 标签实体
INSERT INTO template_tag (tag_id, tag_name)
SELECT 'TAG-' || gen_random_uuid()::text, t.tag_name
FROM (
    SELECT DISTINCT jsonb_array_elements_text(s.template_tags::jsonb) AS tag_name
    FROM scheme s
    WHERE s.template_tags IS NOT NULL AND s.template_tags ~ '^\s*\['
) t
WHERE btrim(t.tag_name) <> ''
ON CONFLICT (tag_name) DO NOTHING;

-- 官网内容种子（V15 并入）：服务协议 + 客服咨询（占位文案，运营可在管理端修改）
INSERT INTO platform_content (content_id, code, title, content_type, content) VALUES
('CONT-USER-AGREEMENT', 'platform_user_agreement', '服务协议', 'rich_text',
 '<h3>RSDP 家居全案平台服务协议</h3><p>欢迎使用 RSDP 家居全案平台。请您在使用本平台前仔细阅读本协议。</p><p>1. 本平台提供的产品信息、价格信息仅供参考，实际以双方确认的订单为准。</p><p>2. 您应当妥善保管账号信息，因账号保管不善造成的损失由您自行承担。</p><p>3. 未经许可，不得将平台数据用于任何商业用途。</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中替换为正式协议。）</p>'),
('CONT-CONSULTING-SERVICE', 'platform_consulting_service', '客服咨询', 'rich_text',
 '<h3>联系客服</h3><p>如需产品咨询、报价或售后服务，请通过以下方式联系我们：</p><p>工作时间：周一至周五 9:00 - 18:00</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中配置真实联系方式。）</p>'),
-- 官网首页区块 4/5 内容（V37 并入）：必逛好物 + 服务卡，JSON 数组，与 website 首页静态兜底一致
('CONT-HOME-TRIO-CARDS', 'home_trio_cards', '首页必逛好物', 'rich_text',
 '[{"title":"大减价","desc":"百余款商品 5 折起 · 即日至 8 月 31 日"},{"title":"当季新品","desc":"秋冬系列全新上市 · 探索新材质"},{"title":"更低价格","desc":"同样的设计 · 更可持续的价格"}]'),
('CONT-HOME-SERVICE-CARDS', 'home_service_cards', '首页服务卡', 'rich_text',
 '[{"title":"送货服务","desc":"珠三角 48 小时达，全国物流可追踪"},{"title":"安装服务","desc":"专业师傅上门，安装完毕清理现场"},{"title":"退换保障","desc":"30 天无理由退换（定制款除外）"},{"title":"免费设计","desc":"AI 户型搭配 + 设计师 1v1 复核"}]')
ON CONFLICT (code) DO NOTHING;
