/* ============================================================
   app.js — 状态管理 + 渲染 + 交互
   数据全部来自 mock.js（经 api.js 桩访问），视图只做呈现。
   ============================================================ */
(function () {
  "use strict";

  /* ---------- 图标（Lucide 内联 SVG，离线可用） ---------- */
  const I = {
    play: '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
    check: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
    alert: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" x2="12" y1="9" y2="13"/><line x1="12" x2="12.01" y1="17" y2="17"/></svg>',
    download: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/></svg>',
    link: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" x2="21" y1="14" y2="3"/></svg>',
    trash: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/></svg>',
    retry: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>'
  };

  const PLATFORM = {
    x:    { label: "X",         cls: "x" },
    ig:   { label: "Instagram", cls: "ig" },
    bsky: { label: "Bluesky",   cls: "bsky" }
  };

  /* ---------- 全局状态 ---------- */
  const state = {
    tab: "inbox",
    themeMode: localStorage.getItem("md-theme-mode") || "light",
    themeApplied: "light",
    monet: true,
    platform: "all",
    sort: "recent",
    proxyMode: "auto",      // auto | manual | direct
    manualAddr: "http://127.0.0.1:7897",
    busy: { rescan: false, speedtest: false, scan: false }
  };

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  /* ============================================================
     主题
     ============================================================ */
  function resolveTheme() {
    if (state.themeMode === "system") {
      return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }
    return state.themeMode;
  }

  function applyTheme() {
    state.themeApplied = resolveTheme();
    document.documentElement.dataset.theme = state.themeApplied;
    localStorage.setItem("md-theme-mode", state.themeMode);
    syncThemeUI();
  }

  function syncThemeUI() {
    $$("#demo-theme-seg [data-theme-choice]").forEach((b) =>
      b.classList.toggle("active", b.dataset.themeChoice === state.themeApplied)
    );
    $$("#settings-theme-seg [data-mode]").forEach((b) =>
      b.classList.toggle("active", b.dataset.mode === state.themeMode)
    );
  }

  function setThemeMode(mode) {
    state.themeMode = mode;
    applyTheme();
  }

  /* ============================================================
     视图切换（底栏永不重绘，仅切换 .active）
     ============================================================ */
  function switchView(name) {
    state.tab = name;
    $$(".view").forEach((v) => v.classList.toggle("active", v.dataset.view === name));
    $$(".tab").forEach((t) => {
      const on = t.dataset.tab === name;
      t.classList.toggle("active", on);
      t.setAttribute("aria-selected", String(on));
    });
    if (name === "library") renderLibrary();
    if (name === "proxy") renderCandidates();
    if (name === "inbox") updateTaskMeta();
  }

  /* ============================================================
     Toast
     ============================================================ */
  function toast(msg, isErr) {
    const zone = $("#toasts");
    const el = document.createElement("div");
    el.className = "toast" + (isErr ? " err" : "");
    el.innerHTML = (isErr ? I.alert : I.check) + "<span>" + msg + "</span>";
    zone.appendChild(el);
    setTimeout(() => {
      el.classList.add("out");
      setTimeout(() => el.remove(), 320);
    }, 2400);
  }

  /* ============================================================
     01 收件箱
     ============================================================ */
  const STATUS_META = {
    queued:      { label: "排队中", cls: "queued" },
    downloading: { label: "下载中", cls: "downloading" },
    done:        { label: "已完成", cls: "done" },
    error:       { label: "错误",   cls: "error" }
  };

  function renderTasks() {
    const list = $("#task-list");
    list.innerHTML = window.DB.tasks.map((t) => {
      const st = STATUS_META[t.status];
      const files = t.files ? `<span class="files">${t.files[0]}/${t.files[1]} 个文件</span>` : "";
      const badge = `<span class="badge ${PLATFORM[t.platform].cls}">${PLATFORM[t.platform].label}</span>`;
      let extra = "";
      if (t.status === "downloading" && t.progress != null) {
        extra = `<div class="prog"><div class="progress"><div class="fill" style="width:${t.progress}%"></div></div></div>`;
      }
      if (t.status === "downloading" && t.progress == null) {
        extra = `<div class="prog"><div class="progress indeterminate"><div class="fill"></div></div></div>`;
      }
      if (t.status === "error") {
        extra = `<div class="err-msg">${t.error || "下载失败"} · <button class="btn ghost sm" data-retry="${t.id}" style="padding:2px 10px;font-size:11px;">${I.retry}重试</button></div>`;
      }
      return `
        <div class="glass glass-card task" data-task="${t.id}">
          <img class="thumb" src="${t.cover}" alt="" />
          <div class="body">
            <div class="url">${t.url}</div>
            <div class="row2">${badge}<span class="author">${t.author}</span>${files}<span style="margin-left:auto" class="pill ${st.cls}">${st.label}</span></div>
            ${extra}
          </div>
        </div>`;
    }).join("");
  }

  function updateTaskMeta() {
    const active = window.DB.tasks.filter((t) => t.status === "queued" || t.status === "downloading").length;
    $("#task-meta").textContent = "· " + active + " 进行中";
    $("#inbox-count").textContent = window.DB.tasks.length + " 项待捕获";
  }

  /* 捕获：粘贴链接 → 排队 → 下载中 → 完成（模拟） */
  async function onCapture() {
    const input = $("#capture-input");
    const urls = input.value.trim().split(/\s+/).filter(Boolean);
    if (!urls.length) {
      input.classList.add("invalid");
      toast("请先粘贴至少一个链接", true);
      setTimeout(() => input.classList.remove("invalid"), 1800);
      return;
    }
    const btn = $("#capture-btn");
    btn.disabled = true;
    for (const u of urls.slice(0, 3)) {
      await API.captureLink(u);
      toast("已加入队列：" + u.slice(0, 28) + (u.length > 28 ? "…" : ""));
    }
    input.value = "";
    btn.disabled = false;
    renderTasks();
    updateTaskMeta();
  }

  /* 后台模拟：排队→下载中→完成 */
  function simulateTick() {
    let changed = false;
    const downloading = window.DB.tasks.filter((t) => t.status === "downloading").length;
    for (const t of window.DB.tasks) {
      if (t.status === "queued" && downloading < 3 && Math.random() < 0.45) {
        t.status = "downloading";
        t.progress = 4;
        changed = true;
      } else if (t.status === "downloading" && t.progress != null) {
        t.progress = Math.min(100, t.progress + 6 + Math.round(Math.random() * 14));
        if (t.progress >= 100) {
          t.status = "done";
          t.files = [t.files[1], t.files[1]];
          changed = true;
        } else {
          changed = true;
        }
      }
    }
    if (changed && state.tab === "inbox") {
      renderTasks();
      updateTaskMeta();
    }
  }

  async function onRetry(id) {
    const t = window.DB.tasks.find((x) => x.id === id);
    if (!t) return;
    t.status = "downloading";
    t.progress = 6;
    t.error = null;
    renderTasks();
    updateTaskMeta();
    toast("已重新开始下载");
  }

  /* ============================================================
     02 素材库
     ============================================================ */
  async function renderLibrary() {
    const grid = $("#lib-grid");
    grid.innerHTML = '<div class="cell skeleton"></div>'.repeat(6);
    const res = await API.fetchLibrary({ platform: state.platform, sort: state.sort });
    const items = res.data.items;
    $("#lib-count").textContent = items.length + " 项 · 1.2 GB";
    if (!items.length) {
      grid.innerHTML = '<div class="empty-hint">没有匹配的素材，换个筛选条件试试</div>';
      return;
    }
    grid.innerHTML = items.map((m) => {
      const isV = m.type === "video";
      const dur = isV ? `<span class="dur">${m.duration}</span>` : "";
      const play = isV ? `<span class="play">${I.play}</span>` : "";
      return `
        <div class="cell" data-id="${m.id}" role="button" tabindex="0" aria-label="${m.title}">
          <img src="${m.cover}" alt="${m.title}" loading="lazy" />
          <span class="badge ${PLATFORM[m.platform].cls}" style="position:absolute;top:6px;left:6px;">${PLATFORM[m.platform].label}</span>
          ${play}${dur}
          <div class="cap">${m.title}</div>
        </div>`;
    }).join("");
  }

  /* 底部弹层 */
  function openSheet(id) {
    const m = window.DB.library.find((x) => x.id === id);
    if (!m) return;
    const p = PLATFORM[m.platform];
    $("#sheet").innerHTML = `
      <div class="grabber"></div>
      <div class="sheet-media">
        <img class="cover" src="${m.cover}" alt="" />
        <div class="meta">
          <div class="title">${m.title}</div>
          <div class="author"><span class="badge ${p.cls}">${p.label}</span> ${m.author}</div>
          <div class="kv">
            <span>${m.type === "video" ? "视频" : m.type === "image" ? "图片" : "音频"}</span>
            <span>${m.ts}</span>
            <span>${m.size}</span>
            ${m.duration ? `<span>${m.duration}</span>` : ""}
          </div>
        </div>
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn" data-sheet-act="play">${I.download}${m.type === "video" ? "播放" : "保存到相册"}</button>
        <button type="button" class="btn ghost" data-sheet-act="open">${I.link}打开原帖</button>
        <button type="button" class="btn ghost" data-sheet-act="favorite">${m.favorite ? "取消收藏" : "收藏"}</button>
        <button type="button" class="btn ghost" data-sheet-act="delete" style="color:var(--err);">${I.trash}删除（回收站）</button>
      </div>`;
    $("#scrim").classList.add("show");
    $("#sheet").classList.add("show");
  }

  function closeSheet() {
    $("#scrim").classList.remove("show");
    $("#sheet").classList.remove("show");
  }

  function onSheetAction(act) {
    if (!state._sheetMedia) return;
    const m = state._sheetMedia;
    if (act === "play") toast("演示：开始播放《" + m.title + "》");
    if (act === "open") toast("演示：将打开原帖链接");
    if (act === "favorite") {
      m.favorite = !m.favorite;
      toast(m.favorite ? "已收藏" : "已取消收藏");
      closeSheet();
      renderLibrary();
    }
    if (act === "delete") {
      window.DB.library = window.DB.library.filter((x) => x.id !== m.id);
      window.DB.recycleBin += 1;
      $("#trash-count").textContent = window.DB.recycleBin + " 项";
      toast("已移入回收站");
      closeSheet();
      renderLibrary();
    }
  }

  /* ============================================================
     03 代理
     ============================================================ */
  function renderCandidates() {
    const cur = window.DB.proxies.find((p) => p.inUse);
    if (state.proxyMode === "direct") {
      $("#cand-list").innerHTML = `
        <div class="glass glass-card" style="text-align:center;color:var(--text-sub);padding:26px;">
          直连模式 · 未使用代理服务器
        </div>`;
      return;
    }
    $("#cand-list").innerHTML = window.DB.proxies.map((p) => `
      <div class="glass glass-card cand${p.inUse ? " inuse" : ""}">
        <span class="flag">${p.region === "jp" ? "🇯🇵" : p.region === "hk" ? "🇭🇰" : "🇺🇸"}</span>
        <div class="info">
          <div class="nm">${p.name} ${p.inUse ? '<span class="inuse-tag">使用中</span>' : ""}</div>
          <div class="ms">${p.latency} ms · ${p.speed} Mbps · ${p.status === "inuse" ? "连接正常" : "可用"}</div>
        </div>
        <span class="score">${p.score.toFixed(1)}</span>
      </div>`).join("");
  }

  function setProxyMode(mode) {
    state.proxyMode = mode;
    $$("#proxy-seg [data-mode]").forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
    $("#manual-box").style.display = mode === "manual" ? "block" : "none";
    const card = $(".proxy-now");
    if (mode === "direct") {
      card.style.display = "none";
      $("#px-scanline").style.display = "none";
    } else {
      card.style.display = "block";
      if (mode === "auto") {
        $("#px-scanline").style.display = "flex";
        setTimeout(() => { $("#px-scanline").style.display = "none"; }, 2200);
      }
    }
    renderCandidates();
    toast(mode === "auto" ? "自动优选已开启" : mode === "manual" ? "已切换手动模式" : "已切换直连");
  }

  async function onRescan() {
    if (state.busy.rescan) return;
    state.busy.rescan = true;
    const btn = $("#px-rescan");
    btn.disabled = true;
    $("#px-scanline").style.display = "flex";
    await API.rescanProxies();
    for (const p of window.DB.proxies) {
      p.latency = Math.max(18, Math.round(p.latency * (0.7 + Math.random() * 0.6)));
      p.speed = Math.max(3, +(p.speed * (0.9 + Math.random() * 0.35)).toFixed(1));
      p.score = +(p.speed / (1 + p.latency / 300)).toFixed(1);
    }
    const cur = window.DB.proxies.find((p) => p.inUse);
    $("#px-latency").innerHTML = cur.latency + '<span style="font-size:11px;font-weight:600;">ms</span>';
    $("#px-speed").innerHTML = cur.speed + '<span style="font-size:11px;font-weight:600;">Mbps</span>';
    $("#px-score").textContent = cur.score.toFixed(1);
    $("#px-scanline").style.display = "none";
    btn.disabled = false;
    state.busy.rescan = false;
    renderCandidates();
    toast("检测完成 · 已更新候选评分");
  }

  async function onSpeedtest() {
    if (state.busy.speedtest) return;
    state.busy.speedtest = true;
    const btn = $("#px-speedtest");
    btn.disabled = true;
    await API.speedtest();
    const cur = window.DB.proxies.find((p) => p.inUse);
    cur.speed = +(12 + Math.random() * 9).toFixed(1);
    cur.latency = Math.max(20, cur.latency + Math.round(Math.random() * 16) - 8);
    cur.score = +(cur.speed / (1 + cur.latency / 300)).toFixed(1);
    $("#px-latency").innerHTML = cur.latency + '<span style="font-size:11px;font-weight:600;">ms</span>';
    $("#px-speed").innerHTML = cur.speed + '<span style="font-size:11px;font-weight:600;">Mbps</span>';
    $("#px-score").textContent = cur.score.toFixed(1);
    btn.disabled = false;
    state.busy.speedtest = false;
    renderCandidates();
    toast("测速完成 · " + cur.name + " " + cur.speed + " Mbps");
  }

  async function onApplyManual() {
    const input = $("#manual-addr");
    const res = await API.applyManualProxy(input.value);
    if (res.code !== 0) {
      input.classList.add("invalid");
      toast(res.message, true);
      setTimeout(() => input.classList.remove("invalid"), 1800);
      return;
    }
    state.manualAddr = res.data.addr;
    toast("已应用手动代理：" + res.data.addr);
  }

  /* ============================================================
     04 设置
     ============================================================ */
  async function onScanStorage() {
    if (state.busy.scan) return;
    state.busy.scan = true;
    const btn = $("#scan-btn");
    btn.disabled = true;
    btn.textContent = "扫描中…";
    await API.scanStorage();
    btn.textContent = "扫描";
    btn.disabled = false;
    state.busy.scan = false;
    toast("扫描完成 · 新登记 128 项素材");
  }

  async function onEmptyTrash() {
    await API.emptyTrash();
    $("#trash-count").textContent = "0 项";
    toast("回收站已清空");
  }

  /* ============================================================
     事件绑定 & 初始化
     ============================================================ */
  function bind() {
    /* 底栏 Tab */
    $$(".tab").forEach((t) => t.addEventListener("click", () => switchView(t.dataset.tab)));

    /* 主题 */
    $$("#demo-theme-seg [data-theme-choice]").forEach((b) =>
      b.addEventListener("click", () => setThemeMode(b.dataset.themeChoice))
    );
    $$("#settings-theme-seg [data-mode]").forEach((b) =>
      b.addEventListener("click", () => setThemeMode(b.dataset.mode))
    );
    $("#monet-switch").addEventListener("click", (e) => {
      state.monet = !state.monet;
      e.currentTarget.classList.toggle("on", state.monet);
      e.currentTarget.setAttribute("aria-checked", String(state.monet));
      toast(state.monet ? "莫奈动态取色已开启" : "已使用固定品牌配色");
    });

    /* 收件箱 */
    $("#capture-btn").addEventListener("click", onCapture);
    $("#task-list").addEventListener("click", (e) => {
      const retry = e.target.closest("[data-retry]");
      if (retry) onRetry(retry.dataset.retry);
    });

    /* 素材库 */
    $("#lib-chips").addEventListener("click", (e) => {
      const chip = e.target.closest(".chip");
      if (!chip) return;
      if (chip.dataset.platform) {
        state.platform = chip.dataset.platform;
        $$("#lib-chips [data-platform]").forEach((c) => c.classList.toggle("active", c === chip));
      }
      if (chip.dataset.sort) {
        state.sort = chip.dataset.sort;
        $$("#lib-chips [data-sort]").forEach((c) => c.classList.toggle("active", c === chip));
      }
      renderLibrary();
    });
    $("#lib-grid").addEventListener("click", (e) => {
      const cell = e.target.closest(".cell");
      if (!cell) return;
      state._sheetMedia = window.DB.library.find((m) => m.id === cell.dataset.id);
      openSheet(cell.dataset.id);
    });
    $("#scrim").addEventListener("click", closeSheet);
    $("#sheet").addEventListener("click", (e) => {
      const act = e.target.closest("[data-sheet-act]");
      if (act) onSheetAction(act.dataset.sheetAct);
    });

    /* 代理 */
    $$("#proxy-seg [data-mode]").forEach((b) =>
      b.addEventListener("click", () => setProxyMode(b.dataset.mode))
    );
    $("#px-rescan").addEventListener("click", onRescan);
    $("#px-speedtest").addEventListener("click", onSpeedtest);
    $("#manual-apply").addEventListener("click", onApplyManual);
    $("#manual-addr").addEventListener("keydown", (e) => { if (e.key === "Enter") onApplyManual(); });

    /* 设置 */
    $("#scan-btn").addEventListener("click", onScanStorage);
    $("#empty-trash").addEventListener("click", (e) => {
      e.stopPropagation();
      onEmptyTrash();
    });
    $("#trash-row").addEventListener("click", () => toast("回收站：恢复 / 彻底删除（演示）"));
  }

  function init() {
    applyTheme();
    bind();
    renderTasks();
    renderLibrary();
    renderCandidates();
    updateTaskMeta();
    setProxyMode("auto", true);
    $("#monet-switch").classList.add("on");
    setInterval(simulateTick, 1400);
  }

  document.addEventListener("DOMContentLoaded", init);
})();
