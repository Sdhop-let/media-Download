/* 媒体下载器 前端逻辑（Vue 3, 无构建） */
const { createApp } = Vue;

const PLATS = {
  twitter:   { name: "X",        cls: "twitter"   },
  instagram: { name: "Instagram",cls: "instagram" },
  bluesky:   { name: "Bluesky",  cls: "bluesky"   },
  other:     { name: "其他",      cls: "other"     },
};

createApp({
  data() {
    return {
      page: "download",
      pages: [
        { id: "download", name: "下载", icon: "⬇" },
        { id: "library",  name: "素材库", icon: "▦" },
        { id: "proxy",    name: "代理",   icon: "⚡" },
        { id: "settings", name: "设置",   icon: "⚙" },
      ],
      dark: true,

      /* 下载页 */
      pasteBox: "", parsing: false, parsed: [],
      tasks: {}, taskOrder: [],
      taskFilter: "all",
      taskFilters: [
        { v: "all", n: "全部" }, { v: "done", n: "已完成" }, { v: "error", n: "失败" },
      ],

      /* 素材库 */
      lib: {
        sort: "time", platform: "", type: "", q: "",
        items: [], authors: [], offset: 0,
        hasMore: false, hasMoreAuthors: false, loading: false, stats: {},
      },
      lightbox: null,
      failedThumbs: {},

      /* 代理 */
      proxy: { best: "检测中…", testing: false, candidates: [], mode: "auto",
               proxy_url: null, modeLabel: "自动优选" },
      proxyLog: [],
      modeSel: "auto", manualProxy: "",
      autoOpt: true, testInterval: 10,

      /* 设置 */
      settings: {}, settingsSaved: false,

      /* 其它 */
      toasts: [], _tid: 0, _es: null, _searchTimer: null,
    };
  },

  computed: {
    pageTitle() { return this.pages.find(p => p.id === this.page)?.name || ""; },
    taskList() { return this.taskOrder.map(id => this.tasks[id]).filter(Boolean); },
    activeTasks() { return this.taskList.filter(t => ["queued", "running"].includes(t.status)); },
    historyTasks() {
      let l = this.taskList.filter(t => !["queued", "running"].includes(t.status));
      if (this.taskFilter === "done") l = l.filter(t => t.status === "done");
      if (this.taskFilter === "error") l = l.filter(t => ["error", "canceled"].includes(t.status));
      return l;
    },
    activeCount() { return this.activeTasks.length; },
    platChips() {
      return [{ v: "", n: "全部" }, { v: "twitter", n: "X" },
              { v: "instagram", n: "Instagram" }, { v: "bluesky", n: "Bluesky" }];
    },
    typeChips() {
      return [{ v: "", n: "全部" }, { v: "photo", n: "图片" }, { v: "video", n: "视频" }];
    },
    proxyTone() {
      if (this.proxy.testing) return "";
      return this.proxy.proxy_url || this.proxy.mode === "direct" ? "ok" : "err";
    },
    bestCand() {
      const b = this.proxy.best;
      return (this.proxy.candidates || []).find(c =>
        b === "直连" ? c.type === "direct" : `${c.type}://${c.host}:${c.port}` === b);
    },
    bestLatency() { return this.bestCand?.latency_ms ?? null; },
    bestSpeed() { return this.bestCand?.speed_mbps != null ? this.bestCand.speed_mbps.toFixed(1) : null; },
  },

  async mounted() {
    const theme = localStorage.getItem("theme");
    if (theme === "light") this.setTheme(false);
    await this.loadTasks();
    this.connectSSE();
    this.fetchProxySnapshot();
  },

  methods: {
    /* ---------- 基础 ---------- */
    async api(path, opts = {}) {
      const r = await fetch("/api" + path, {
        headers: { "Content-Type": "application/json" }, ...opts,
        body: opts.body ? JSON.stringify(opts.body) : undefined,
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
      return j;
    },
    toast(text, level = "info") {
      const id = ++this._tid;
      this.toasts.push({ id, text, level });
      setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 4200);
    },
    go(page) {
      this.page = page;
      if (page === "download") this.loadTasks();
      if (page === "library") this.loadMedia(true);
      if (page === "proxy") { this.fetchProxySnapshot(); this.loadProxyLog(); }
      if (page === "settings") this.loadSettings();
    },
    openUrl(u) { if (u) window.open(u, "_blank"); },

    /* ---------- SSE ---------- */
    connectSSE() {
      this._es = new EventSource("/api/events");
      this._es.onmessage = (e) => {
        let msg; try { msg = JSON.parse(e.data); } catch { return; }
        const { type, data } = msg;
        if (type === "task_update") this.upsertTask(data);
        else if (type === "task_deleted") this.removeTask(data.id);
        else if (type === "proxy_update") this.applyProxy(data);
        else if (type === "toast") this.toast(data.text, data.level || "info");
      };
      this._es.onerror = () => { /* 断线自动重连 */ };
    },
    upsertTask(t) {
      if (!t || !t.id) return;
      const prev = this.tasks[t.id];
      if (!prev) this.taskOrder.unshift(t.id);
      this.tasks[t.id] = t;
      // 仅在状态实际流转为 done 时提示，避免初次加载误报
      if (prev && prev.status !== "done" && t.status === "done" && t.done_files > 0) {
        this.toast(`任务完成：${t.done_files} 个文件`, "ok");
      }
    },
    removeTask(id) {
      delete this.tasks[id];
      this.taskOrder = this.taskOrder.filter(i => i !== id);
    },

    /* ---------- 下载页 ---------- */
    async loadTasks() {
      try {
        const j = await this.api("/tasks?limit=80");
        this.taskOrder = [];
        for (const t of j.items.slice().reverse()) this.upsertTask(t);
      } catch (e) { this.toast("加载任务失败：" + e.message, "error"); }
    },
    async parsePaste() {
      const urls = [...new Set(this.pasteBox.split(/\s+/).map(s => s.trim()).filter(Boolean))];
      if (!urls.length) return;
      this.parsing = true;
      try {
        const j = await this.api("/tasks/parse", { method: "POST", body: { urls } });
        const seen = new Set(this.parsed.map(p => p.url));
        for (const it of j.items) {
          if (!seen.has(it.url)) { this.parsed.push(it); seen.add(it.url); }
        }
        this.pasteBox = "";
      } catch (e) { this.toast("解析失败：" + e.message, "error"); }
      this.parsing = false;
    },
    async startDownload() {
      const urls = this.parsed.map(p => p.url);
      if (!urls.length) return;
      try {
        const j = await this.api("/tasks", { method: "POST", body: { urls } });
        this.parsed = [];
        this.toast(`已添加 ${j.added.length} 个任务` +
          (j.duplicates.length ? `，${j.duplicates.length} 个重复链接已忽略` : ""), "ok");
      } catch (e) { this.toast("添加任务失败：" + e.message, "error"); }
    },
    async cancelTask(t) { await this.api(`/tasks/${t.id}/cancel`, { method: "POST" }); },
    async retryTask(t) {
      try { await this.api(`/tasks/${t.id}/retry`, { method: "POST" }); }
      catch (e) { this.toast(e.message, "error"); }
    },
    delTask(t) {
      if (!confirm("删除该任务记录？")) return;
      this.api(`/tasks/${t.id}`, { method: "DELETE" }).then(() => this.removeTask(t.id));
    },
    openTaskFolder(t) {
      fetch(`/api/tasks/${t.id}/open-folder`, { method: "POST" })
        .then(() => this.toast("已尝试打开目录"));
    },

    /* ---------- 素材库 ---------- */
    setSort(s) { this.lib.sort = s; this.loadMedia(true); },
    setPlatform(p) { this.lib.platform = p; this.loadMedia(true); },
    setType(t) { this.lib.type = t; this.loadMedia(true); },
    searchDebounce() {
      clearTimeout(this._searchTimer);
      this._searchTimer = setTimeout(() => this.loadMedia(true), 350);
    },
    async loadMedia(reset = true) {
      const L = this.lib;
      if (reset) { L.offset = 0; L.items = []; L.authors = []; }
      L.loading = true;
      try {
        const qs = new URLSearchParams({ sort: L.sort, platform: L.platform, q: L.q,
                                         offset: L.offset, limit: 60 });
        if (L.sort === "time" && L.type) qs.set("type", L.type);
        const j = await this.api("/media?" + qs);
        if (L.sort === "time") {
          L.items.push(...(j.items || [])); L.hasMore = j.has_more;
          L.offset += (j.items || []).length;
        } else {
          L.authors.push(...(j.authors || [])); L.hasMoreAuthors = j.has_more;
          L.offset += (j.authors || []).length;
        }
        L.stats = await this.api("/media/stats");
      } catch (e) { this.toast("加载素材失败：" + e.message, "error"); }
      L.loading = false;
    },
    thumbOf(m) {
      if (this.failedThumbs[m.id]) return "";
      if (m.media_type === "photo") return m.file_path;
      return m.thumb_path || "";
    },
    thumbFail(e, m) { this.failedThumbs[m.id] = true; },
    isVideoFile(p) { return /\.(mp4|webm|mov|m4v)$/i.test(p || ""); },
    fileUrl(path) { return "/api/file?path=" + encodeURIComponent(path); },
    openMedia(m, what) {
      fetch(`/api/media/${m.id}/open?what=${what}`, { method: "POST" });
      this.toast(what === "file" ? "已尝试打开文件" : "已尝试打开目录");
    },
    delMedia(m) {
      if (!confirm("删除该素材（含本地文件）？")) return;
      this.api(`/media/${m.id}`, { method: "DELETE" }).then(() => {
        this.lib.items = this.lib.items.filter(x => x.id !== m.id);
        for (const a of this.lib.authors) a.items = a.items.filter(x => x.id !== m.id);
        this.toast("已删除", "ok");
      });
    },

    /* ---------- 代理页 ---------- */
    applyProxy(snap) {
      if (!snap || !snap.candidates) return;
      this.proxy = { ...this.proxy, ...snap, modeLabel: { auto: "自动优选", manual: "手动指定", direct: "直连" }[snap.mode] || snap.mode };
      if (this.modeSel !== snap.mode) this.modeSel = snap.mode;
    },
    async fetchProxySnapshot() { try { this.applyProxy(await this.api("/proxy/status")); } catch {} },
    async loadProxyLog() {
      try { this.proxyLog = (await this.api("/proxy/log?limit=60")).items; } catch {}
    },
    async detectProxy() {
      await this.api("/proxy/detect", { method: "POST" });
      this.toast("已开始检测，结果稍后自动刷新");
    },
    async testProxy() {
      await this.api("/proxy/test", { method: "POST" });
      this.toast("已开始测速，结果稍后自动刷新");
    },
    async setMode() {
      const body = { mode: this.modeSel };
      if (this.modeSel === "manual") body.manual_proxy = this.manualProxy || this.proxy.proxy_url || "";
      const j = await this.api("/proxy/mode", { method: "POST", body });
      this.applyProxy(j);
      this.toast("代理模式已切换", "ok");
    },
    async saveOptimize() {
      const j = await this.api("/proxy/optimize", { method: "POST",
        body: { enabled: this.autoOpt, interval_min: this.testInterval } });
      this.applyProxy(j);
    },
    isBest(c) {
      return this.proxy.best === "直连" ? c.type === "direct"
        : `${c.type}://${c.host}:${c.port}` === this.proxy.best;
    },
    logTone(ev) { return ["切换", "检测"].includes(ev) ? "accent" : ev === "错误" ? "error" : ""; },

    /* ---------- 设置页 ---------- */
    async loadSettings() {
      try { this.settings = await this.api("/settings");
        this.autoOpt = !!this.settings.auto_optimize;
        this.testInterval = this.settings.test_interval_min || 10;
        this.manualProxy = this.settings.manual_proxy || "";
      } catch (e) { this.toast(e.message, "error"); }
    },
    async saveSettings() {
      try {
        this.settings = await this.api("/settings", { method: "PUT", body: this.settings });
        this.settingsSaved = true;
        setTimeout(() => this.settingsSaved = false, 2500);
        this.toast("设置已保存", "ok");
      } catch (e) { this.toast(e.message, "error"); }
    },

    /* ---------- 通用工具 ---------- */
    platName(p) { return (PLATS[p] || PLATS.other).name; },
    statusText(s) {
      return { queued: "排队中", running: "下载中", done: "已完成", error: "失败",
               canceled: "已取消", partial: "部分完成" }[s] || s;
    },
    shortUrl(u) { return (u || "").replace(/^https?:\/\/(www\.)?/, "").slice(0, 72); },
    taskPct(t) {
      const total = t.total_files || 0;
      if (!total) return t.status === "running" ? 4 : 0;
      return Math.min(100, Math.round(((t.done_files + (t.skipped || 0)) / total) * 100));
    },
    fmtBytes(n) {
      n = n || 0;
      if (n > 1 << 30) return (n / (1 << 30)).toFixed(2) + " GB";
      if (n > 1 << 20) return (n / (1 << 20)).toFixed(1) + " MB";
      if (n > 1 << 10) return (n / (1 << 10)).toFixed(0) + " KB";
      return n + " B";
    },
    fmtSpeed(bps) { return this.fmtBytes(bps) + "/s"; },
    fmtDur(m) { return m.width && m.height ? `${m.width}×${m.height}` : ""; },
    fmtTime(iso) {
      if (!iso) return "—";
      const d = new Date(iso);
      if (isNaN(d)) return iso;
      const p = (x) => String(x).padStart(2, "0");
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
    },
    setTheme(dark) {
      this.dark = dark;
      document.documentElement.dataset.theme = dark ? "dark" : "light";
      localStorage.setItem("theme", dark ? "dark" : "light");
    },
    toggleTheme() { this.setTheme(!this.dark); },
  },
}).mount("#app");
