"""代理测速与持续优选：延迟(204)+吞吐(cloudflare) → 评分 → 自动切换。"""
import asyncio
import time

import httpx

from .. import config, db
from ..events import bus
from . import proxy_detector

PROBE_URL = proxy_detector.PROBE_URL
SPEED_URL = "https://speed.cloudflare.com/__down?bytes=10485760"
UA = proxy_detector.UA


def _score(latency_ms: float | None, mbps: float) -> float:
    if latency_ms is None:
        return 0.0
    return round(mbps / (1.0 + latency_ms / 300.0), 2)


def _label(c: dict | None) -> str:
    if not c or c.get("type") == "direct":
        return "直连"
    return f"{c['type']}://{c['host']}:{c['port']}"


class ProxyManager:
    def __init__(self):
        self.candidates: list[dict] = []
        self.best: dict | None = None      # None 视为直连
        self.testing = False
        self._last_full = 0.0
        self._tasks: list[asyncio.Task] = []

    # ---------- 对外 ----------

    def proxy_url(self) -> str | None:
        """当前生效的代理 URL（None = 直连）。"""
        mode = config.get("proxy_mode")
        if mode == "direct":
            return None
        if mode == "manual":
            v = (config.get("manual_proxy") or "").strip()
            return v or None
        if self.best and self.best.get("type") != "direct":
            return f"{self.best['type']}://{self.best['host']}:{self.best['port']}"
        return None

    def alternate_url(self) -> str | None:
        """备用路由：直连失败时换最优代理；代理失败时换直连。"""
        if self.proxy_url() is None:
            alive = [c for c in self.candidates if c.get("alive") and c.get("type") != "direct"]
            if alive:
                c = alive[0]
                return f"{c['type']}://{c['host']}:{c['port']}"
        return None

    def snapshot(self) -> dict:
        return {
            "mode": config.get("proxy_mode"),
            "auto_optimize": config.get("auto_optimize"),
            "test_interval_min": config.get("test_interval_min"),
            "testing": self.testing,
            "best": _label(self.best),
            "best_score": (self.best or {}).get("score", 0),
            "proxy_url": self.proxy_url(),
            "candidates": db.get_candidates(),
        }

    async def start(self):
        self._tasks = [
            asyncio.create_task(self.detect_and_test("启动检测"), name="proxy-init"),
            asyncio.create_task(self._optimizer(), name="proxy-optimizer"),
        ]

    async def detect_and_test(self, reason: str = "手动检测"):
        if self.testing:
            return
        self.testing = True
        self._publish()
        try:
            found = await proxy_detector.detect(config.get("scan_ports"))
            cands = found + [{"type": "direct", "host": "", "port": 0, "source": "直连"}]
            db.clear_candidates()
            self.candidates = []
            for c in cands:
                cid = db.upsert_candidate(c["type"], c["host"], c["port"], c["source"])
                self.candidates.append({**c, "id": cid, "latency_ms": None,
                                        "speed_mbps": None, "score": 0, "alive": 0})
            await self.test_all()
            self._pick_best()
            db.add_proxy_log("检测", f"{reason}: 发现 {len(found)} 个代理候选，"
                                    f"当前最优 {_label(self.best)}（评分 {(self.best or {}).get('score', 0)}）")
        finally:
            self._last_full = time.monotonic()
            self.testing = False
            self._publish()

    async def test_all(self):
        """并发测速全部候选（限流 6）。"""
        sem = asyncio.Semaphore(6)

        async def one(c):
            async with sem:
                await self.test_candidate(c)

        await asyncio.gather(*(one(c) for c in self.candidates))
        self._publish()

    async def test_candidate(self, c: dict):
        proxy = None if c["type"] == "direct" else f"{c['type']}://{c['host']}:{c['port']}"
        try:
            async with httpx.AsyncClient(
                proxy=proxy, timeout=httpx.Timeout(8, connect=5), follow_redirects=True,
            ) as cli:
                lats = []
                for _ in range(2):
                    t0 = time.monotonic()
                    r = await cli.get(PROBE_URL, headers={"User-Agent": UA})
                    if r.status_code >= 400:
                        raise RuntimeError(f"HTTP {r.status_code}")
                    lats.append((time.monotonic() - t0) * 1000)
                lat = sum(lats) / len(lats)
                mbps = await self._throughput(cli)
        except Exception:
            db.update_candidate(c["id"], None, None, 0, 0)
            c.update(latency_ms=None, speed_mbps=None, score=0, alive=0)
            return c
        score = _score(lat, mbps)
        db.update_candidate(c["id"], round(lat, 1), round(mbps, 2), score, 1)
        c.update(latency_ms=round(lat), speed_mbps=round(mbps, 2), score=score, alive=1)
        return c

    def apply_mode(self, mode: str, manual_proxy: str | None = None):
        patch = {"proxy_mode": mode}
        if manual_proxy is not None:
            patch["manual_proxy"] = manual_proxy
        config.set_values(patch)
        db.add_proxy_log("模式", f"切换为 {mode}" + (f"（{manual_proxy}）" if manual_proxy else ""))
        self._publish()

    def set_auto_optimize(self, enabled=None, interval_min=None):
        patch = {}
        if enabled is not None:
            patch["auto_optimize"] = bool(enabled)
        if interval_min is not None:
            patch["test_interval_min"] = max(1, int(interval_min))
        if patch:
            config.set_values(patch)
        db.add_proxy_log("设置", f"自动优选 {'开启' if config.get('auto_optimize') else '关闭'}，"
                                f"间隔 {config.get('test_interval_min')} 分钟")
        self._last_full = 0.0  # 让优化器按新间隔尽快执行
        self._publish()

    # ---------- 内部 ----------

    @staticmethod
    async def _throughput(cli: httpx.AsyncClient, budget=5.0) -> float:
        got, t0 = 0, time.monotonic()
        try:
            async with cli.stream("GET", SPEED_URL) as r:
                async for chunk in r.aiter_bytes(1 << 16):
                    got += len(chunk)
                    if time.monotonic() - t0 > budget:
                        break
        except Exception:
            pass
        dt = time.monotonic() - t0
        return (got / dt / 1e6) if dt > 0.2 else 0.0

    def _pick_best(self):
        alive = [c for c in self.candidates if c.get("alive")]
        self.best = max(alive, key=lambda c: c.get("score", 0), default=None)

    def _publish(self):
        bus.publish("proxy_update", self.snapshot())

    async def _optimizer(self):
        """持续优化：周期全量复测，最优显著变化时记录并广播。"""
        while True:
            try:
                await asyncio.sleep(20)
                interval = max(1, int(config.get("test_interval_min") or 10)) * 60
                if time.monotonic() - self._last_full < interval:
                    continue
                prev = self.best
                await self.detect_and_test("周期复测")
                if config.get("auto_optimize") and self.best and _label(prev) != _label(self.best):
                    msg = (f"自动切换 {_label(prev)} → {_label(self.best)}"
                           f"（评分 {(prev or {}).get('score', 0)} → {self.best.get('score', 0)}）")
                    db.add_proxy_log("切换", msg)
                    bus.publish("toast", {"level": "info", "text": f"代理优选：{msg}"})
            except Exception as e:
                db.add_proxy_log("错误", repr(e))
