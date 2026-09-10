const fs = require("fs");
const path = require("path");

const scenePath = path.resolve(__dirname, "ui-design.excalidraw");
const scene = JSON.parse(fs.readFileSync(scenePath, "utf8"));

const labelTexts = {
  "p3-cand": "候选代理（评分 = 速度 ÷ (1+延迟/300)）\nJP-1 使用中 42ms 4.2 ｜ HK-2 58ms 2.9 ｜ US-1 120ms 1.6",
  "p4-appear": "外观 · 主题：跟随系统 / 浅色 / 深色 [IosSegmented]\n莫奈动态取色：Android 12+ 壁纸配色 [IosSwitch]",
  "p4-storage": "存储 · [扫描下载目录] 新登记磁盘文件\n回收站（3）：恢复 / 彻底删除"
};

let fixed = 0;
for (const el of scene.elements) {
  if (el.label && labelTexts[el.id]) {
    el.label.text = labelTexts[el.id];
    fixed += 1;
  }
}

fs.writeFileSync(scenePath, JSON.stringify(scene, null, 2));
console.log(JSON.stringify({ fixed }, null, 2));
