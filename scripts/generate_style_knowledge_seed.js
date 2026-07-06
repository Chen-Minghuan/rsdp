const fs = require('fs');
const path = require('path');

const RAW_DIR = 'data/style-knowledge/raw';
const OUTPUT = 'database/seed_style_knowledge.sql';

// 受控词表映射
const materialMap = [
  { codes: ['TN'], values: ['藤编', '真藤', '竹编', '草编', '编织'] },
  { codes: ['PE'], values: ['PE仿藤', '仿藤'] },
  { codes: ['LE'], values: ['皮革', '真皮', '天然皮革'] },
  { codes: ['NP'], values: ['纳帕皮'] },
  { codes: ['SU'], values: ['磨砂皮'] },
  { codes: ['MA'], values: ['马鞍皮'] },
  { codes: ['LI'], values: ['亚麻', '棉麻'] },
  { codes: ['SF'], values: ['羊羔绒', '泰迪绒', 'bouclé', 'boucle'] },
  { codes: ['VE'], values: ['天鹅绒', '绒布'] },
  { codes: ['WO'], values: ['实木', '胡桃木', '柚木', '橡木', '榆木', '黑胡桃', '原木', '木材'] },
  { codes: ['RK'], values: ['藤编+实木', '藤木'] },
  { codes: ['MT'], values: ['金属', '不锈钢', '黄铜', '钢管', '铁艺', '铜'] },
  { codes: ['WL'], values: ['羊毛'] },
  { codes: ['GL'], values: ['玻璃'] },
  { codes: ['ST'], values: ['石材', '大理石', '洞石', '岩板', '天然石材'] },
  { codes: ['CE'], values: ['水泥', '微水泥', '混凝土'] },
  { codes: ['CL'], values: ['陶瓷', '瓷砖'] },
  { codes: ['GP'], values: ['石膏', 'PU线条', '腻子'] },
  { codes: ['PL'], values: ['塑料', '亚克力', 'PVC'] }
];

const colorMap = [
  { code: 'CARAMEL', values: ['焦糖', '焦糖棕'] },
  { code: 'BEIGE', values: ['米白', '奶油白', '奶白', '象牙白', '燕麦色', '暖白', '米色', '奶咖', '浅驼', '暖米色'] },
  { code: 'CA', values: ['驼色', '奶咖色', '浅驼色', '卡其'] },
  { code: 'DB', values: ['深棕', '胡桃木色', '黑胡桃', '深褐色'] },
  { code: 'NATURAL', values: ['原木色', '橡木色', '木色', '自然木'] },
  { code: 'BLACK', values: ['黑色', '黑'] },
  { code: 'GRAY', values: ['灰色', '灰', '冷灰'] },
  { code: 'NAVY', values: ['藏青', '蓝色', '海军蓝'] },
  { code: 'GN', values: ['绿色', '橄榄绿', '苔藓绿', '芥末绿'] },
  { code: 'PR', values: ['紫色', '紫'] },
  { code: 'RD', values: ['红色'] },
  { code: 'OR', values: ['橘色', '橙色', '橘'] },
  { code: 'PK', values: ['粉色'] },
  { code: 'YE', values: ['黄色', '芥末黄'] },
  { code: 'WT', values: ['白色'] }
];

const categoryMap = [
  { code: 'FS', values: ['休闲椅', '单椅', '躺椅', '圈椅', '扶手椅', '吧椅', '餐椅', 'accent chair', 'lounge chair'] },
  { code: 'SF', values: ['沙发', 'sofa'] },
  { code: 'TB', values: ['茶几', '边几', '咖啡桌', '茶桌', '书桌', '餐桌', '桌子', 'table'] },
  { code: 'FC', values: ['床', '床头柜', '柜体', '书架', '展示柜', '衣柜', '柜', 'cabinet', 'bookcase'] },
  { code: 'BS', values: ['吧椅', 'bar stool'] }
];

const sceneMap = [
  { code: 'LIVING', values: ['客厅', 'living room'] },
  { code: 'BEDROOM', values: ['卧室', 'bedroom'] },
  { code: 'STUDY', values: ['书房', 'study'] },
  { code: 'CAFE', values: ['咖啡厅', 'cafe'] },
  { code: 'OFFICE', values: ['办公室', 'office'] },
  { code: 'HOTEL', values: ['酒店', 'hotel'] }
];

function normalize(text) {
  return String(text || '').toLowerCase().replace(/[\s\/（）()]+/g, '');
}

function findMappings(text, maps) {
  const norm = normalize(text);
  const results = [];
  for (const m of maps) {
    const codes = m.codes || [m.code];
    for (const v of m.values) {
      if (norm.includes(normalize(v))) {
        results.push({ code: codes[0], value: v });
        break;
      }
    }
  }
  return results;
}

function dedupe(arr, keyFn) {
  const seen = new Set();
  return arr.filter(x => {
    const k = keyFn(x);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

function extractAll(items, mapper) {
  const results = [];
  for (const item of items) {
    results.push(...mapper(item));
  }
  return dedupe(results, x => x.code || x.value);
}

function truncate(text, max = 500) {
  if (!text) return '';
  return text.length > max ? text.substring(0, max - 3) + '...' : text;
}

function pgString(text) {
  if (text === null || text === undefined) return "''";
  return "'" + String(text).replace(/'/g, "''") + "'";
}

function pgJsonb(obj) {
  return '$STYLE_JSON$' + JSON.stringify(obj || {}) + '$STYLE_JSON$::jsonb';
}

function pgArrayJsonb(arr) {
  return '$STYLE_JSON$' + JSON.stringify(arr || []) + '$STYLE_JSON$::jsonb';
}

// 读取风格百科
const styleFiles = fs.readdirSync(RAW_DIR)
  .filter(f => f.endsWith('.json') && f !== '风格失败案例分析.json')
  .sort();

const styles = styleFiles.map(f => JSON.parse(fs.readFileSync(path.join(RAW_DIR, f), 'utf8')));

// 读取失败案例
const failureData = JSON.parse(fs.readFileSync(path.join(RAW_DIR, '风格失败案例分析.json'), 'utf8'));

let sql = `-- RSDP 风格数据库种子数据
-- 自动生成来源：data/style-knowledge/raw/*.json
-- 覆盖：11 个风格百科正向案例 + 9 个失败案例反向案例 + 搭配公式
-- 执行方式：psql -h localhost -U postgres -d rsdp -f database/seed_style_knowledge.sql

`;

// ========== style_case 正向案例 ==========
const cases = [];
const elements = [];
let elementSeq = 0;

function addElement(caseId, type, value, code, isPrimary = false, notes = '') {
  elementSeq++;
  const id = `ELEM-${String(elementSeq).padStart(4, '0')}`;
  elements.push({
    element_id: id,
    case_id: caseId,
    element_type: type,
    element_value: value,
    normalized_code: code || '',
    is_primary: isPrimary,
    confidence: 'high',
    notes
  });
}

for (const s of styles) {
  const styleCode = s.style_code;
  const caseId = `CASE-${styleCode}-001`;
  const caseName = `${s.style_name}风格典型空间`;
  const desc = truncate(s.core_philosophy, 400);

  cases.push({
    case_id: caseId,
    case_name: caseName,
    dict_type: 'style',
    style_code: styleCode,
    room_type: 'LIVING_ROOM',
    is_success: true,
    source_type: '风格百科',
    source_url: '',
    description: desc,
    image_url: '',
    ai_raw_output: s,
    negative_lesson: '',
    review_status: '已确认',
    created_by: 'system'
  });

  // style 元素
  addElement(caseId, 'style', s.style_name, styleCode, true);

  // mood 元素（从情感氛围提取关键词）
  const moodKeywords = ['温暖', '怀旧', '治愈', '宁静', '柔和', '理性', '简洁', '精致', '冷静', '浪漫', '雅致', '质朴', '活泼', '个性', '奢华', '粗犷'];
  const atmos = s.emotional_atmosphere || '';
  for (const kw of moodKeywords) {
    if (atmos.includes(kw)) {
      addElement(caseId, 'mood', kw, '', true);
      break;
    }
  }

  // 颜色元素
  const kc = s.key_characteristics || {};
  const colorTexts = kc.color_palette || [];
  const colors = extractAll(colorTexts, t => findMappings(t, colorMap));
  for (let i = 0; i < colors.length; i++) {
    addElement(caseId, 'color', colors[i].value, colors[i].code, i === 0);
  }

  // 材质元素
  const materialTexts = kc.materials || [];
  const materials = extractAll(materialTexts, t => findMappings(t, materialMap));
  for (let i = 0; i < materials.length; i++) {
    addElement(caseId, 'material', materials[i].value, materials[i].code, i < 2);
  }

  // 家具类别元素
  const furnitures = s.typical_furniture || [];
  const categories = [];
  for (const f of furnitures) {
    const mapped = findMappings(f.category, categoryMap);
    categories.push(...mapped);
  }
  const uniqCats = dedupe(categories, x => x.code);
  for (let i = 0; i < uniqCats.length; i++) {
    addElement(caseId, 'category', uniqCats[i].value.replace(/（.*?）/g, ''), uniqCats[i].code, i < 2);
  }

  // 场景元素
  addElement(caseId, 'scene', '客厅', 'LIVING', true);
}

// ========== style_case 失败案例 ==========
const failCases = [];
for (const fc of (failureData.case_analysis || [])) {
  const caseId = `FAIL-${String(fc.case_id).padStart(3, '0')}`;
  const desc = `${fc.image}。问题：${fc.problems.join('；')}。根因：${fc.root_cause}`;

  // 推断涉及风格：从问题文本中匹配
  const styleMatches = [];
  for (const s of styles) {
    const allText = `${fc.failure_type} ${fc.problems.join(' ')} ${fc.root_cause}`;
    if (allText.includes(s.style_name)) {
      styleMatches.push(s);
    }
  }
  // 如果没匹配到，默认 HH（混搭风）或 MC
  const mainStyle = styleMatches[0] || styles.find(s => s.style_code === 'HH') || styles[0];

  failCases.push({
    case_id: caseId,
    case_name: `失败案例${fc.case_id}：${fc.failure_type}`,
    dict_type: 'style',
    style_code: mainStyle.style_code,
    room_type: 'LIVING_ROOM',
    is_success: false,
    source_type: '风格失败案例库',
    source_url: '',
    description: truncate(desc, 400),
    image_url: '',
    ai_raw_output: fc,
    negative_lesson: fc.root_cause,
    review_status: '已确认',
    created_by: 'system'
  });

  addElement(caseId, 'style', mainStyle.style_name, mainStyle.style_code, true);

  // 从问题中提取材质
  const allProblemText = fc.problems.join(' ');
  const failMaterials = findMappings(allProblemText, materialMap);
  for (const m of failMaterials) {
    addElement(caseId, 'material', m.value, m.code, true);
  }

  // 从 failure_type 提取元素类型
  if (fc.failure_type.includes('色彩')) {
    addElement(caseId, 'mood', '色彩冲突', '', true);
  }
  if (fc.failure_type.includes('材质')) {
    addElement(caseId, 'mood', '材质滥用', '', true);
  }
  if (fc.failure_type.includes('风格')) {
    addElement(caseId, 'mood', '风格混杂', '', true);
  }
  if (fc.failure_type.includes('元素')) {
    addElement(caseId, 'mood', '元素堆砌', '', true);
  }
  if (fc.failure_type.includes('品质')) {
    addElement(caseId, 'mood', '品质感缺失', '', true);
  }
  if (fc.failure_type.includes('尺度')) {
    addElement(caseId, 'mood', '尺度失衡', '', true);
  }
}

// 合并 cases
const allCases = [...cases, ...failCases];

// ========== 生成 case SQL ==========
sql += '-- =================== 1. 风格百科正向案例 ===================\n\n';
sql += 'INSERT INTO style_case (case_id, case_name, dict_type, style_code, room_type, is_success, source_type, source_url, description, image_url, ai_raw_output, negative_lesson, review_status, created_by, created_at, updated_at) VALUES\n';
const caseValues = allCases.map(c => {
  return `('${c.case_id}', ${pgString(c.case_name)}, 'style', '${c.style_code}', '${c.room_type}', ${c.is_success}, ${pgString(c.source_type)}, ${pgString(c.source_url)}, ${pgString(c.description)}, ${pgString(c.image_url)}, ${pgJsonb(c.ai_raw_output)}, ${pgString(c.negative_lesson)}, '已确认', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`;
}).join(',\n');
sql += caseValues + '\nON CONFLICT (case_id) DO NOTHING;\n\n';

// ========== 生成 element SQL ==========
sql += '-- =================== 2. 元素拆解 ===================\n\n';
sql += 'INSERT INTO style_element (element_id, case_id, element_type, element_value, normalized_code, is_primary, confidence, notes, created_at) VALUES\n';
const elementValues = elements.map((e, idx) => {
  const isLast = idx === elements.length - 1;
  return `('${e.element_id}', '${e.case_id}', '${e.element_type}', ${pgString(e.element_value)}, ${pgString(e.normalized_code)}, ${e.is_primary}, '${e.confidence}', ${pgString(e.notes)}, CURRENT_TIMESTAMP)${isLast ? '' : ','}`;
}).join('\n');
sql += elementValues + '\nON CONFLICT (element_id) DO NOTHING;\n\n';

// ========== 生成 formula SQL ==========
sql += '-- =================== 3. 搭配公式 ===================\n\n';
sql += 'INSERT INTO style_matching_formula (formula_id, name, dict_type, style_code, room_type, priority, formula_json, source_case_ids, negative_case_ids, success_count, fail_count, status, created_by, created_at, updated_at) VALUES\n';

const formulas = [];
for (const s of styles) {
  const styleCode = s.style_code;
  const kc = s.key_characteristics || {};
  const materials = extractAll(kc.materials || [], t => findMappings(t, materialMap));
  const colors = extractAll(kc.color_palette || [], t => findMappings(t, colorMap));
  const furnitures = s.typical_furniture || [];
  const categories = [];
  for (const f of furnitures) {
    categories.push(...findMappings(f.category, categoryMap));
  }
  const uniqCats = dedupe(categories, x => x.code);

  const mustHave = [];
  if (materials.length > 0) {
    mustHave.push({ type: 'material', values: materials.slice(0, 2).map(m => m.value), role: 'primary' });
  }

  const compatible = [];
  if (materials.length > 2) {
    compatible.push({ type: 'material', values: materials.slice(2).map(m => m.value), weight: 0.25, reason: '风格标志性材质组合' });
  }
  if (colors.length > 0) {
    compatible.push({ type: 'color', relation: 'analogous', values: colors.slice(0, 3).map(c => c.value), weight: 0.30, reason: '核心配色体系' });
  }
  if (uniqCats.length > 0) {
    compatible.push({ type: 'category', values: uniqCats.slice(0, 3).map(c => c.value.replace(/（.*?）/g, '')), weight: 0.25, reason: '典型家具组合' });
  }
  compatible.push({ type: 'scene', values: ['客厅', '书房'], weight: 0.10 });
  if (compatible.length > 0) {
    // 重新归一化权重使其总和约等于1
    const total = compatible.reduce((sum, x) => sum + x.weight, 0);
    for (const c of compatible) {
      c.weight = Math.round((c.weight / total) * 100) / 100;
    }
  }

  const avoid = (s.avoid_elements || []).slice(0, 4).map(a => {
    const mappedMats = findMappings(a, materialMap);
    const mappedColors = findMappings(a, colorMap);
    if (mappedMats.length > 0) {
      return { type: 'material', values: mappedMats.map(m => m.value), reason: truncate(a, 80) };
    }
    if (mappedColors.length > 0) {
      return { type: 'color', values: mappedColors.map(c => c.value), reason: truncate(a, 80) };
    }
    return { type: 'mood', values: [truncate(a, 40)], reason: '风格禁忌' };
  });

  const formulaJson = {
    must_have: mustHave,
    compatible: compatible,
    avoid: avoid,
    spatial_hint: { note: truncate(s.design_principles ? s.design_principles[0] : '', 200) }
  };

  const sourceCaseIds = [`CASE-${styleCode}-001`];
  const negativeCaseIds = failCases.filter(f => f.style_code === styleCode).map(f => f.case_id);

  formulas.push({
    formula_id: `FORM-${styleCode}-001`,
    name: `${s.style_name}风格搭配公式`,
    style_code: styleCode,
    room_type: 'LIVING_ROOM',
    priority: 10,
    formula_json: formulaJson,
    source_case_ids: sourceCaseIds,
    negative_case_ids: negativeCaseIds,
    success_count: 10,
    fail_count: negativeCaseIds.length || 1,
    status: 'active'
  });
}

const formulaValues = formulas.map((f, idx) => {
  const isLast = idx === formulas.length - 1;
  return `('${f.formula_id}', ${pgString(f.name)}, 'style', '${f.style_code}', '${f.room_type}', ${f.priority}, ${pgJsonb(f.formula_json)}, ${pgArrayJsonb(f.source_case_ids)}, ${pgArrayJsonb(f.negative_case_ids)}, ${f.success_count}, ${f.fail_count}, 'active', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)${isLast ? '' : ','}`;
}).join('\n');
sql += formulaValues + '\nON CONFLICT (formula_id) DO NOTHING;\n';

fs.writeFileSync(OUTPUT, sql, 'utf8');
console.log(`Generated ${OUTPUT}`);
console.log(`Cases: ${allCases.length}, Elements: ${elements.length}, Formulas: ${formulas.length}`);
