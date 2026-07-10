#!/usr/bin/env node
// 对账：MyBatis-Plus 实体字段 vs PostgreSQL 表列（V1 + V2）
// 找出“DB 有 / 实体缺”与“实体有 / DB 无”的字段。
// 用法：node scripts/check_entity_db_fields.js
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const ENTITY_DIR = path.join(ROOT, "server/src/main/java/com/rsdp/entity");
const SQL_FILES = [
  path.join(ROOT, "database/V1__init_db.sql"),
  path.join(ROOT, "database/V2__factory_module.sql"),
];

function camelToSnake(name) {
  return name
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1_$2")
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase();
}

function parseDb() {
  const db = {};
  const add = (t, c) => {
    const k = t.toLowerCase();
    (db[k] = db[k] || []);
    if (c && !db[k].includes(c)) db[k].push(c);
  };
  for (const f of SQL_FILES) {
    const txt = fs.readFileSync(f, "utf8");
    // CREATE TABLE blocks
    const reCreate = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(([\s\S]*?)\);/gi;
    let m;
    while ((m = reCreate.exec(txt))) {
      const table = m[1];
      for (let raw of m[2].split("\n")) {
        let line = raw.split("--")[0].trim().replace(/,$/, "");
        if (!line) continue;
        if (/^(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT|INDEX)\b/i.test(line)) continue;
        const col = line.split(/\s+/)[0];
        add(table, col.toLowerCase());
      }
    }
    // ALTER TABLE ... ADD COLUMN (one or more)
    const reAlter = /ALTER\s+TABLE\s+(\w+)\s+((?:\s*ADD\s+COLUMN\s+[^;]+)+);/gi;
    while ((m = reAlter.exec(txt))) {
      const table = m[1];
      const reAdd = /ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)/gi;
      let c;
      while ((c = reAdd.exec(m[2]))) add(table, c[1].toLowerCase());
    }
  }
  return db;
}

function parseEntity(file) {
  const txt = fs.readFileSync(file, "utf8");
  const tm = txt.match(/@TableName\("(\w+)"\)/);
  if (!tm) return null;
  const table = tm[1].toLowerCase();
  const fields = {}; // column -> field
  let ann = [];
  for (const raw of txt.split("\n")) {
    const s = raw.trim();
    if (s.startsWith("@")) {
      ann.push(s);
      continue;
    }
    const m = s.match(/private\s+(?!static\b)(?!final\b)[\w<>\[\],.?\s]+?\s+(\w+)\s*[;=]/);
    if (m) {
      const fname = m[1];
      const block = ann.join("\n");
      const vm = block.match(/@Table(?:Field|Id)\(\s*(?:value\s*=\s*)?"(\w+)"/);
      const col = (vm ? vm[1] : camelToSnake(fname)).toLowerCase();
      if (!/exist\s*=\s*false/.test(block)) fields[col] = fname;
      ann = [];
    } else if (s === "" || s === "{" || s === "}" || /^(\/\/|\*|\/\*)/.test(s)) {
      // keep annotation buffer across blanks/comments/braces
    } else {
      ann = [];
    }
  }
  return { table, fields };
}

function main() {
  const db = parseDb();
  const files = fs.readdirSync(ENTITY_DIR).filter((f) => f.endsWith(".java")).sort();
  const rows = [];
  for (const f of files) {
    const r = parseEntity(path.join(ENTITY_DIR, f));
    if (!r) continue;
    const dbcols = db[r.table];
    if (dbcols === undefined) {
      rows.push({ table: r.table, fname: f, missing: null });
      continue;
    }
    const eset = new Set(Object.keys(r.fields));
    const dset = new Set(dbcols);
    const dbOnly = [...dset].filter((c) => !eset.has(c)).sort();
    const entityOnly = [...eset].filter((c) => !dset.has(c)).sort();
    if (dbOnly.length || entityOnly.length) rows.push({ table: r.table, fname: f, dbOnly, entityOnly });
  }

  console.log(`DB 表数: ${Object.keys(db).length}   实体数: ${files.length}`);
  console.log("=".repeat(72));
  if (!rows.length) {
    console.log("\n所有实体字段与数据库列完全对应，无差异。");
    return;
  }
  for (const r of rows) {
    console.log(`\n[${r.table}]  (${r.fname})`);
    if (r.missing === null) {
      console.log("   !! 数据库中无此表（@TableName 在 SQL 找不到）");
      continue;
    }
    if (r.dbOnly.length) console.log(`   DB有 / 实体缺 (${r.dbOnly.length}): ${JSON.stringify(r.dbOnly)}`);
    if (r.entityOnly.length) console.log(`   实体有 / DB缺 (${r.entityOnly.length}): ${JSON.stringify(r.entityOnly)}`);
  }
}

main();
