const fs = require("fs");
const path = require("path");

const scenePath = path.resolve(__dirname, "ui-design.excalidraw");
const scene = JSON.parse(fs.readFileSync(scenePath, "utf8"));
const byId = new Map(scene.elements.map((e) => [e.id, e]));

const ALLOWED_SIZES = [16, 20, 28];
const SHAPES = new Set(["rectangle", "ellipse", "diamond", "line", "arrow"]);
const DEFAULT_FAMILIES = [2, "2", 3, "3"];

const stats = { text: 0, stroke: 0, size: 0, align: 0, family: 0, opacity: 0, width: 0, fill: 0 };

for (const el of scene.elements) {
  if (el.type === "text") {
    stats.text += 1;
    if (!DEFAULT_FAMILIES.includes(el.fontFamily)) {
      el.fontFamily = 2;
      stats.family += 1;
    }
    if (!ALLOWED_SIZES.includes(el.fontSize)) {
      const ratio = el.fontSize && el.fontSize < 16 ? 16 / el.fontSize : 1;
      el.fontSize = 16;
      if (ratio !== 1) {
        if (typeof el.height === "number") el.height = Math.ceil(el.height * ratio);
        if (typeof el.width === "number") el.width = Math.ceil(el.width * ratio);
      }
      stats.size += 1;
    }
    const bound = el.containerId && byId.has(el.containerId);
    if (bound) {
      if (el.textAlign !== "center") { el.textAlign = "center"; stats.align += 1; }
      if (el.verticalAlign !== "middle") { el.verticalAlign = "middle"; stats.align += 1; }
    } else {
      if (el.textAlign !== "left") { el.textAlign = "left"; stats.align += 1; }
      if (!el.verticalAlign) el.verticalAlign = "top";
    }
  } else if (SHAPES.has(el.type)) {
    if (!["solid", "dashed"].includes(el.strokeStyle)) {
      el.strokeStyle = "solid";
      stats.stroke += 1;
    }
    if (![1, 2].includes(el.strokeWidth)) el.strokeWidth = 1;
    if (el.opacity !== 100) { el.opacity = 100; stats.opacity += 1; }
    if (el.roughness !== 0) el.roughness = 0;
    if (el.type !== "arrow" && el.fillStyle !== "solid") {
      el.fillStyle = "solid";
      stats.fill += 1;
    }
    if (el.label && typeof el.label === "object") {
      const lb = el.label;
      if (lb.fontSize !== 16) {
        const ratio = lb.fontSize && lb.fontSize < 16 ? 16 / lb.fontSize : 1;
        lb.fontSize = 16;
        if (ratio !== 1) {
          if (typeof lb.height === "number") lb.height = Math.ceil(lb.height * ratio);
          if (typeof lb.width === "number") lb.width = Math.ceil(lb.width * ratio);
        }
        stats.size += 1;
      }
      if (lb.textAlign !== "center") { lb.textAlign = "center"; stats.align += 1; }
      if (lb.verticalAlign !== "middle") { lb.verticalAlign = "middle"; stats.align += 1; }
    }
  }
}

fs.writeFileSync(scenePath, JSON.stringify(scene, null, 2));
console.log(JSON.stringify({ total: scene.elements.length, ...stats }, null, 2));
