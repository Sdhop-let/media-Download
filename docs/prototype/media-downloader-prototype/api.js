/* ============================================================
   api.js — API 桩（stub）层
   函数签名与未来真实接口保持一致；接入后端时仅替换内部实现。
   每个桩标注了预期的 HTTP 方法与路径。
   ============================================================ */
(function () {
  const delay = (ms) => new Promise((r) => setTimeout(r, ms));
  const ok = (data, ms = 400) => delay(ms).then(() => ({ code: 0, data }));

  window.API = {
    /** GET /api/tasks —— 任务列表 */
    fetchTasks() {
      // TODO: replace with fetch("/api/tasks")
      return ok({ items: window.DB.tasks.slice(), count: window.DB.tasks.length });
    },

    /** POST /api/tasks —— 提交链接捕获 */
    captureLink(url) {
      // TODO: replace with fetch("/api/tasks", { method: "POST", body: JSON.stringify({ url }) })
      const platform = url.includes("instagram")
        ? "ig"
        : url.includes("bsky.app")
          ? "bsky"
          : "x";
      const cover = { x: "cover-tech", ig: "cover-travel", bsky: "cover-music" }[platform];
      const task = {
        id: "t" + Date.now(),
        url,
        platform,
        author: platform === "ig" ? "@new_capture" : platform === "bsky" ? "@new.writer" : "@new_poster",
        cover: "assets/covers/" + cover + ".jpg",
        status: "queued",
        progress: 0,
        files: [0, 3],
        ts: "刚刚"
      };
      window.DB.tasks.unshift(task);
      return ok({ task }, 600);
    },

    /** GET /api/library?platform=&sort= —— 素材库 */
    fetchLibrary({ platform = "all", sort = "recent" } = {}) {
      // TODO: replace with fetch(`/api/library?platform=${platform}&sort=${sort}`)
      let items = window.DB.library.slice();
      if (platform !== "all") items = items.filter((m) => m.platform === platform);
      if (sort === "size") items = items.slice().sort((a, b) => parseFloat(b.size) - parseFloat(a.size));
      if (sort === "favorite") items = items.slice().filter((m) => m.favorite);
      return ok({ items, count: items.length });
    },

    /** GET /api/proxy/status —— 当前代理与候选 */
    fetchProxyStatus() {
      // TODO: replace with fetch("/api/proxy/status")
      return ok({ current: window.DB.proxies.find((p) => p.inUse), candidates: window.DB.proxies });
    },

    /** POST /api/proxy/rescan —— 重新检测候选 */
    rescanProxies() {
      // TODO: replace with fetch("/api/proxy/rescan", { method: "POST" })
      return ok({ scanning: true }, 900);
    },

    /** POST /api/proxy/speedtest —— 立即测速 */
    speedtest() {
      // TODO: replace with fetch("/api/proxy/speedtest", { method: "POST" })
      return ok({ ok: true }, 800);
    },

    /** POST /api/proxy/manual —— 应用手动代理地址 */
    applyManualProxy(addr) {
      // TODO: replace with fetch("/api/proxy/manual", { method: "POST", body: JSON.stringify({ addr }) })
      if (!/^[a-z]+:\/\/[\w.:-]+$/i.test(addr.trim())) {
        return delay(300).then(() => ({ code: 1, message: "地址格式无效，示例：http://127.0.0.1:7897" }));
      }
      return ok({ addr: addr.trim() }, 500);
    },

    /** POST /api/storage/scan —— 扫描下载目录 */
    scanStorage() {
      // TODO: replace with fetch("/api/storage/scan", { method: "POST" })
      return ok({ scanned: 128 }, 1100);
    },

    /** DELETE /api/recyclebin —— 清空回收站 */
    emptyTrash() {
      // TODO: replace with fetch("/api/recyclebin", { method: "DELETE" })
      window.DB.recycleBin = 0;
      return ok({ removed: 3 }, 600);
    }
  };
})();
