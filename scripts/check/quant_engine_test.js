// 量化引擎回归(不入 jar;放在 scripts/ 下,勿放 build/ 内,否则 gradle clean 会删除):
// 从 index.html 抽取真实实现运行,验证:
// 可续跑引擎分块(1/5/32 行)与整跑、quantData 同步封装输出逐字节一致;
// 量化输出全部为色板色(α=0 除外)。
// 用法:node scripts/check/quant_engine_test.js
// 注意:不可启用 "use strict" —— 非严格模式下 eval 中的 function 声明泄漏到本作用域供测试使用
const fs = require("fs");
const path = require("path");
const htmlFile = path.join(__dirname, "..", "..", "src", "main", "resources", "web", "index.html");
const html = fs.readFileSync(htmlFile, "utf8");

global.ImageData = class { constructor(data, w, h) { this.data = data; this.width = w; this.height = h; } };

const segA = html.slice(html.indexOf("function nearestIdx"), html.indexOf("\nfunction findNode"));
const segB = html.slice(html.indexOf("function quantData"), html.indexOf("async function sliceTiles"));

let palette = new Array(256).fill(null);
let transparentSlot = new Array(256).fill(false);
let palLab = null, lutBuf = null, distMode = "rgb", lutEpoch = 0, lutReady = 0;
let lutFormula = null, lutBuilding = null;
eval(segA);
eval(segB);

const colors = [0x000000, 0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0x7fb238, 0xf7e9a3, 0xc7c7c7,
  0x993333, 0x00d93a, 0xa0a0ff, 0x31302f, 0x3f76e4, 0x800080, 0xffff00, 0x00ffff, 0x8040cc,
  0x5e0f15, 0xb0a67f, 0x9c9c9c, 0x4a4a4a, 0x6b6b6b, 0xe5e5e5, 0xd87f33, 0xb24cd8, 0x76add8];
for (let i = 4; i < 4 + colors.length; i++) palette[i + 128] = colors[i - 4];
for (let i = 0; i < 4; i++) transparentSlot[i + 128] = true;

function randSrc(W, H, transparentP) {
  const a = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < a.length; i += 4) {
    if (Math.random() < transparentP) continue;
    a[i] = (Math.random() * 256) | 0; a[i + 1] = (Math.random() * 256) | 0; a[i + 2] = (Math.random() * 256) | 0;
    a[i + 3] = 255;
  }
  return a;
}
function runChunked(W, H, mode, src, rows) {
  const j = newQuantJob(W, H, mode, src);
  while (!jobStep(j, rows)) {}
  return j.out;
}
function eq(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
function report(name, ok) { console.log((ok ? "PASS " : "FAIL ") + name); if (!ok) process.exitCode = 1; }

let ok1 = true;
for (const mode of ["smooth", "maxdetail"]) {
  for (const [W, H] of [[40, 27], [7, 33]]) {
    for (let t = 0; t < 3; t++) {
      const src = randSrc(W, H, 0.08);
      const whole = runChunked(W, H, mode, src, H);
      for (const rows of [1, 5, 32]) {
        const chunk = runChunked(W, H, mode, src, rows);
        if (!eq(whole, chunk)) { ok1 = false; console.log("  chunk mismatch", mode, W + "x" + H, "rows", rows); }
      }
      const qd = quantData(W, H, mode, src).data;
      if (!eq(whole, qd)) { ok1 = false; console.log("  quantData wrapper mismatch", mode, W + "x" + H); }
    }
  }
}
report("1) chunked(1/5/32行) == 整跑 == quantData 同步,逐字节一致(smooth/maxdetail)", ok1);

// 2) 量化输出合法性:非透明像素必须落在色板颜色集合内(逐字节为色板色)
function paletteColorSet() {
  const s = new Set();
  for (let i = 0; i < 256; i++) {
    const c = palette[i];
    if (c == null || transparentSlot[i]) continue;
    s.add(c);
  }
  return s;
}
let ok2 = true;
for (const mode of ["smooth", "maxdetail"]) {
  for (let t = 0; t < 3; t++) {
    const W = 53, H = 19;
    const src = randSrc(W, H, 0.05);
    const out = runChunked(W, H, mode, src, 8);
    const pset = paletteColorSet();
    for (let p = 0; p < W * H; p++) {
      const i = p * 4;
      if (out[i + 3] === 0) continue;
      const c = (out[i] << 16) | (out[i + 1] << 8) | out[i + 2];
      if (!pset.has(c)) { ok2 = false; console.log("  off-palette pixel", mode, c.toString(16)); break; }
    }
  }
}
report("2) 量化输出全部为色板色(α=0 除外)", ok2);
