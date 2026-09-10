const { execSync } = require("child_process");
const path = require("path");

const cli = "C:/Users/LENOVO/.trae-cn/plugins/trae-remote-official/whiteboard/0.2.0/skills/whiteboard-design/scripts/canvas-cli.mjs";

function run(args) {
  return JSON.parse(execSync(`node "${cli}" ${args}`, { encoding: "utf8" }));
}

const texts = run("query --type text").filter((t) => t.containerId);
const rects = run("query --type rectangle");

const byId = new Map(rects.map((r) => [r.id, r]));
console.log("bound texts:", texts.length);
for (const t of texts) {
  const c = byId.get(t.containerId);
  console.log(JSON.stringify({
    id: t.id,
    containerId: t.containerId,
    containerBg: c ? c.backgroundColor : "?",
    color: t.strokeColor,
    text: (t.text || "").replace(/\n/g, " ").slice(0, 22),
  }));
}
