#!/usr/bin/env node
/**
 * 户型图识别评分脚本（户型图识别优化第一期）。
 *
 * 用法：node scripts/floorplan_eval.js <图片路径> [label]
 *
 * 流程：上传图片到公开实测接口 POST localhost:8081/api/v1/public/ai-match/analyze
 * （multipart 字段 file），解析 rooms（含归一化 bbox），与内置真值按 roomType
 * 贪心匹配（IoU 降序分配），输出每房间 IoU 明细 + 均值 + 漏识别/多余框清单。
 *
 * 真值：仅内置主测图（按文件名匹配）；其他图片只输出原始识别结果不算分。
 * 结果 JSON 存 logs/floorplan-iter/eval-<label>-<时间戳>.json。
 */

const fs = require('fs');
const path = require('path');

const API_URL = process.env.FLOORPLAN_EVAL_URL || 'http://localhost:8081/api/v1/public/ai-match/analyze';

/** 主测图真值（人工标注，全图归一化 xywh）。key = 文件名。 */
const GROUND_TRUTH = {
  'IMG-D03F4375-1BE1-434F-9D8A-13A1A5D6CD92.png': [
    { label: '卧室3(左上,床1600)', roomType: 'bedroom', x: 0.36, y: 0.07, w: 0.14, h: 0.23 },
    { label: '卫生间(上中)', roomType: 'bathroom', x: 0.50, y: 0.07, w: 0.07, h: 0.23 },
    { label: '次卧(右上,床1500)', roomType: 'bedroom', x: 0.62, y: 0.07, w: 0.16, h: 0.23 },
    { label: '玄关(入户)', roomType: 'hallway', x: 0.32, y: 0.36, w: 0.15, h: 0.14 },
    { label: '过道(中部走廊)', roomType: 'hallway', x: 0.48, y: 0.31, w: 0.14, h: 0.19 },
    { label: '卫生间2(中右马桶)', roomType: 'bathroom', x: 0.56, y: 0.36, w: 0.10, h: 0.16 },
    { label: '厨房(左中灶台)', roomType: 'kitchen', x: 0.25, y: 0.52, w: 0.08, h: 0.18 },
    { label: '餐厅(左下圆桌)', roomType: 'dining_room', x: 0.27, y: 0.55, w: 0.18, h: 0.17 },
    { label: '客厅(中下沙发)', roomType: 'living_room', x: 0.45, y: 0.50, w: 0.19, h: 0.24 },
    { label: '主卧(右下大床2100)', roomType: 'bedroom', x: 0.62, y: 0.55, w: 0.18, h: 0.33 },
    { label: '阳台(左下躺椅)', roomType: 'balcony', x: 0.33, y: 0.76, w: 0.19, h: 0.12 },
  ],
};

/** IoU（xywh 归一化坐标）。 */
function iou(a, b) {
  const x1 = Math.max(a.x, b.x);
  const y1 = Math.max(a.y, b.y);
  const x2 = Math.min(a.x + a.w, b.x + b.w);
  const y2 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  const union = a.w * a.h + b.w * b.h - inter;
  return union > 0 ? inter / union : 0;
}

/** 同类型贪心匹配：所有 (真值, 预测) 对按 IoU 降序，依次分配未占用的双方。 */
function greedyMatch(gtList, predList) {
  const pairs = [];
  gtList.forEach((gt, gi) => {
    predList.forEach((pred, pi) => {
      if (pred.roomType !== gt.roomType) return;
      if (pred.x == null || pred.y == null || pred.w == null || pred.h == null) return;
      pairs.push({ gi, pi, iou: iou(gt, pred) });
    });
  });
  pairs.sort((a, b) => b.iou - a.iou);
  const gtUsed = new Set();
  const predUsed = new Set();
  const matches = [];
  for (const p of pairs) {
    if (gtUsed.has(p.gi) || predUsed.has(p.pi)) continue;
    gtUsed.add(p.gi);
    predUsed.add(p.pi);
    matches.push(p);
  }
  return { matches, missedGt: gtList.map((_, i) => i).filter(i => !gtUsed.has(i)),
    extraPred: predList.map((_, i) => i).filter(i => !predUsed.has(i)) };
}

async function main() {
  const [imagePath, label = 'run'] = process.argv.slice(2);
  if (!imagePath) {
    console.error('用法: node scripts/floorplan_eval.js <图片路径> [label]');
    process.exit(1);
  }
  const absPath = path.resolve(imagePath);
  if (!fs.existsSync(absPath)) {
    console.error('图片不存在: ' + absPath);
    process.exit(1);
  }
  const fileName = path.basename(absPath);

  const form = new FormData();
  const mime = /\.png$/i.test(fileName) ? 'image/png'
    : /\.jpe?g$/i.test(fileName) ? 'image/jpeg'
    : /\.pdf$/i.test(fileName) ? 'application/pdf' : 'application/octet-stream';
  form.append('file', new Blob([fs.readFileSync(absPath)], { type: mime }), fileName);

  console.log(`上传 ${fileName} → ${API_URL}`);
  const t0 = Date.now();
  const resp = await fetch(API_URL, { method: 'POST', body: form });
  const costMs = Date.now() - t0;
  const body = await resp.json();
  if (!resp.ok || body.code !== 200) {
    console.error(`接口调用失败: HTTP ${resp.status}`, JSON.stringify(body).slice(0, 500));
    process.exit(2);
  }
  const rooms = (body.data && body.data.rooms) || [];
  console.log(`识别完成，耗时 ${costMs}ms，共 ${rooms.length} 个空间`);

  const report = {
    image: fileName,
    label,
    apiUrl: API_URL,
    costMs,
    evaluatedAt: new Date().toISOString(),
    rawRooms: rooms,
    analysisId: body.data && body.data.analysisId,
  };

  const gt = GROUND_TRUTH[fileName];
  if (!gt) {
    console.log('非主测图（无内置真值），仅输出原始识别结果：');
    console.log(JSON.stringify(rooms, null, 2));
  } else {
    const { matches, missedGt, extraPred } = greedyMatch(gt, rooms);
    const details = [];
    for (const m of matches) {
      const g = gt[m.gi];
      const p = rooms[m.pi];
      details.push({
        gtLabel: g.label, roomType: g.roomType,
        predRoomName: p.roomName, dimensionText: p.dimensionText ?? null,
        gt: { x: g.x, y: g.y, w: g.w, h: g.h },
        pred: { x: p.x, y: p.y, w: p.w, h: p.h },
        iou: Number(m.iou.toFixed(4)),
      });
    }
    const matchedIouSum = matches.reduce((s, m) => s + m.iou, 0);
    const meanMatched = matches.length ? matchedIouSum / matches.length : 0;
    // 全量均值：漏识别真值按 IoU=0 计入，衡量整体覆盖率
    const meanAll = matchedIouSum / gt.length;

    report.eval = {
      gtCount: gt.length,
      predCount: rooms.length,
      matchedCount: matches.length,
      meanIouMatched: Number(meanMatched.toFixed(4)),
      meanIouAll: Number(meanAll.toFixed(4)),
      details,
      missed: missedGt.map(i => `${gt[i].label}(${gt[i].roomType})`),
      extra: extraPred.map(i => {
        const p = rooms[i];
        return `${p.roomName || p.roomType}(${p.roomType})` +
          (p.x != null ? ` [${p.x.toFixed(2)},${p.y.toFixed(2)},${p.w.toFixed(2)},${p.h.toFixed(2)}]` : ' [无bbox]');
      }),
    };

    console.log('\n===== 每房间 IoU 明细 =====');
    for (const d of details.sort((a, b) => b.iou - a.iou)) {
      console.log(`  ${d.gtLabel} [${d.roomType}] IoU=${d.iou.toFixed(3)} pred=${d.predRoomName} dim=${d.dimensionText}`);
    }
    if (report.eval.missed.length) console.log('漏识别: ' + report.eval.missed.join(', '));
    if (report.eval.extra.length) console.log('多余框: ' + report.eval.extra.join(', '));
    console.log(`\n匹配 ${matches.length}/${gt.length}，匹配均值 IoU=${meanMatched.toFixed(4)}，全量均值 IoU=${meanAll.toFixed(4)}`);
  }

  const outDir = path.resolve(__dirname, '..', 'logs', 'floorplan-iter');
  fs.mkdirSync(outDir, { recursive: true });
  const ts = new Date().toISOString().replace(/[:.]/g, '').replace('T', '-').slice(0, 15);
  const outFile = path.join(outDir, `eval-${label}-${ts}.json`);
  fs.writeFileSync(outFile, JSON.stringify(report, null, 2));
  console.log(`结果已保存: ${outFile}`);
}

main().catch(e => {
  console.error('评分脚本执行失败:', e.message);
  process.exit(3);
});
