const { execSync } = require("child_process");

const cli = "C:/Users/LENOVO/.trae-cn/plugins/trae-remote-official/whiteboard/0.2.0/skills/whiteboard-design/scripts/canvas-cli.mjs";
const out = execSync(`node "${cli}" query --type rectangle`, { encoding: "utf8" });
const arr = JSON.parse(out);
for (const t of arr.slice(0, 3)) {
  console.log("---");
  console.log(JSON.stringify(t, null, 1).slice(0, 1600));
}
