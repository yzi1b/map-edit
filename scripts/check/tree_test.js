// 目录树前端解析回归(不入 jar;勿放 build/ 内,gradle clean 会删除):
// 从 index.html 抽取 findNode/folderNode,对真实 /dev/tree 数据做解析断言。
// 历史回归:findNode 曾在单段目录解析后直接返回根(点任意层目录都显示根内容)。
// 用法:node scripts/check/tree_test.js [tree.json 或留空读 http://127.0.0.1:35565/dev/tree]
// 注意:不可启用 "use strict" —— eval 泄漏 function 声明
const fs = require("fs");
const path = require("path");
const htmlFile = path.join(__dirname, "..", "..", "src", "main", "resources", "web", "index.html");
const html = fs.readFileSync(htmlFile, "utf8");

let seg = html.slice(html.indexOf("function findNode"), html.indexOf("function renderTree"));
let tree = null;
function report(name, ok) { console.log((ok ? "PASS " : "FAIL ") + name); if (!ok) process.exitCode = 1; }

(async () => {
  const arg = process.argv[2];
  if (arg) {
    tree = JSON.parse(fs.readFileSync(arg, "utf8"));
  } else {
    const r = await fetch("http://127.0.0.1:35565/dev/tree");
    if (!r.ok) throw new Error("dev/tree HTTP " + r.status);
    tree = await r.json();
  }
  eval(seg);

  const folders = [];
  const paints = [];
  (function walk(node, prefix) {
    for (const c of node.children || []) {
      const p = prefix + c.name;
      if (c.type === "folder") { folders.push(p); walk(c, p + "/"); }
      else paints.push(p);
    }
  })(tree, "");

  report("根目录可解析(findNode('') 返回树)", findNode("") === tree);
  let ok = true;
  for (const f of folders) {
    const n = findNode(f);
    if (!n || n.type !== "folder" || folderNode(f) !== n) {
      ok = false;
      console.log("  目录解析失败: " + f);
    }
  }
  report("全部文件夹路径逐段解析正确(type=folder 且 folderNode 同)", ok);
  ok = true;
  for (const p of paints) {
    const n = findNode(p);
    if (!n || n.type !== "painting") {
      ok = false;
      console.log("  画解析失败: " + p);
    }
    if (folderNode(p) !== null) {
      ok = false;
      console.log("  画路径不应是文件夹: " + p);
    }
  }
  report("全部画路径解析正确(且非 folderNode)", ok);
  report("不存在的路径返回 null", findNode("__不存在__/x") === null && findNode("不存在") === null);
  report("树的文件夹/画总量非零(数据未空)", folders.length + paints.length > 0);
  console.log("  数据: " + folders.length + " 个文件夹, " + paints.length + " 幅画");
})().catch(e => { console.error("harness fail: " + e.message); process.exitCode = 1; });
