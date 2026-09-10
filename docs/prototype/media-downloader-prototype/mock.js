/* ============================================================
   mock.js — 单一数据源（跨页面数据一致）
   后续接入真实后端时，仅替换 api.js 实现，数据结构保持不变
   ============================================================ */
window.DB = {
  tasks: [
    {
      id: "t1",
      url: "x.com/author/status/123",
      platform: "x",
      author: "@techguy_zh",
      cover: "assets/covers/cover-tech.jpg",
      status: "downloading",   // queued | downloading | done | error
      progress: 62,            // 0-100, null = 不确定
      files: [2, 3],
      ts: "09-06 18:02"
    },
    {
      id: "t2",
      url: "instagram.com/p/ABC",
      platform: "ig",
      author: "@travel_diary",
      cover: "assets/covers/cover-travel.jpg",
      status: "queued",
      progress: 0,
      files: [0, 4],
      ts: "09-06 17:58"
    },
    {
      id: "t3",
      url: "bsky.app/profile/me/post/xyz",
      platform: "bsky",
      author: "@night.writer",
      cover: "assets/covers/cover-music.jpg",
      status: "done",
      progress: 100,
      files: [3, 3],
      ts: "09-06 17:41"
    },
    {
      id: "t4",
      url: "instagram.com/reel/DEF",
      platform: "ig",
      author: "@foodhunter",
      cover: "assets/covers/cover-food.jpg",
      status: "queued",
      progress: 0,
      files: [0, 1],
      ts: "09-06 17:20"
    },
    {
      id: "t5",
      url: "x.com/thread/status/456",
      platform: "x",
      author: "@photo_land",
      cover: "assets/covers/cover-landscape.jpg",
      status: "done",
      progress: 100,
      files: [6, 6],
      ts: "09-06 16:52"
    },
    {
      id: "t6",
      url: "instagram.com/p/GHI",
      platform: "ig",
      author: "@stylist.jin",
      cover: "assets/covers/cover-fashion.jpg",
      status: "error",
      progress: 34,
      files: [1, 2],
      ts: "09-06 16:10",
      error: "连接超时，请重试"
    }
  ],

  library: [
    { id: "m1",  platform: "x",    author: "@techguy_zh",  type: "video", title: "Liquid Glass 设计系统实践分享", cover: "assets/covers/cover-tech.jpg",      size: "86.4 MB", duration: "18:24", ts: "09-06 18:02", favorite: true  },
    { id: "m2",  platform: "ig",   author: "@travel_diary",type: "video", title: "悬崖海岸的金色黄昏",         cover: "assets/covers/cover-travel.jpg",    size: "24.1 MB", duration: "00:42", ts: "09-05 21:18", favorite: false },
    { id: "m3",  platform: "bsky", author: "@night.writer",type: "video", title: "霓虹录音室 · 现场片段",       cover: "assets/covers/cover-music.jpg",     size: "45.8 MB", duration: "03:07", ts: "09-05 19:44", favorite: true  },
    { id: "m4",  platform: "ig",   author: "@foodhunter",  type: "image", title: "一碗热腾腾的豚骨拉面",       cover: "assets/covers/cover-food.jpg",      size: "6.2 MB",  duration: null,  ts: "09-05 12:03", favorite: false },
    { id: "m5",  platform: "x",    author: "@photo_land",  type: "image", title: "晨雾中的高山湖",             cover: "assets/covers/cover-landscape.jpg", size: "11.7 MB", duration: null,  ts: "09-04 08:36", favorite: false },
    { id: "m6",  platform: "ig",   author: "@stylist.jin", type: "image", title: "极简米色 · 编辑风大片",       cover: "assets/covers/cover-fashion.jpg",   size: "8.9 MB",  duration: null,  ts: "09-04 15:27", favorite: false },
    { id: "m7",  platform: "x",    author: "@techguy_zh",  type: "image", title: "开源协议速查表（v2）",       cover: "assets/covers/cover-tech.jpg",      size: "2.1 MB",  duration: null,  ts: "09-03 10:11", favorite: false },
    { id: "m8",  platform: "ig",   author: "@foodhunter",  type: "video", title: "街头小吃探店 · 全记录",       cover: "assets/covers/cover-food.jpg",      size: "96.0 MB", duration: "06:51", ts: "09-03 13:40", favorite: false },
    { id: "m9",  platform: "bsky", author: "@night.writer",type: "image", title: "午夜的灯塔",                 cover: "assets/covers/cover-landscape.jpg", size: "4.8 MB",  duration: null,  ts: "09-02 23:55", favorite: true  },
    { id: "m10", platform: "ig",   author: "@travel_diary",type: "image", title: "旅拍胶片 · 一组九张",         cover: "assets/covers/cover-travel.jpg",    size: "31.5 MB", duration: null,  ts: "09-02 09:02", favorite: false },
    { id: "m11", platform: "x",    author: "@photo_land",  type: "video", title: "延时摄影 · 云海翻涌",         cover: "assets/covers/cover-landscape.jpg", size: "72.3 MB", duration: "01:12", ts: "09-01 07:19", favorite: false },
    { id: "m12", platform: "bsky", author: "@night.writer",type: "audio", title: "未命名 Demo · 电子氛围",      cover: "assets/covers/cover-music.jpg",     size: "15.6 MB", duration: "04:33", ts: "08-31 21:08", favorite: false }
  ],

  proxies: [
    { id: "p1", name: "JP-1", region: "jp", latency: 42,  speed: 18.5, score: 4.2, inUse: true,  status: "inuse" },
    { id: "p2", name: "HK-2", region: "hk", latency: 58,  speed: 12.3, score: 2.9, inUse: false, status: "ok"    },
    { id: "p3", name: "US-1", region: "us", latency: 120, speed: 8.1,  score: 1.6, inUse: false, status: "ok"    }
  ],

  recycleBin: 3
};
