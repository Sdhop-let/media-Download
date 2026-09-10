"""系统代理自动检测：环境变量 → Windows 注册表(WinINET/PAC) → 常见端口扫描（协议自动归类）。"""
import asyncio
import re
from urllib.request import getproxies

import httpx

try:
    import winreg  # Windows only
except ImportError:
    winreg = None

REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
PROBE_URL = "https://cp.cloudflare.com/generate_204"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def _mk(type_, host, port, source):
    return {"type": type_, "host": host, "port": int(port), "source": source}


def candidates_from_env() -> list[dict]:
    out = []
    for _scheme, val in (getproxies() or {}).items():
        m = re.match(r"^(socks5h?|socks4|http)://([^/:]+):(\d+)/?$", str(val).strip())
        if not m:
            m = re.match(r"^([^/:]+):(\d+)$", str(val).strip())  # 裸 host:port
            if not m:
                continue
            out.append(_mk("http", m.group(1), m.group(2), "环境变量"))
            continue
        proto, host, port = m.groups()
        t = "socks5" if proto.startswith("socks") else "http"
        out.append(_mk(t, host, port, "环境变量"))
    return out


def candidates_from_registry() -> tuple[list[dict], list[str]]:
    """返回 (候选列表, PAC URL 列表)。"""
    cands, pacs = [], []
    if winreg is None:
        return cands, pacs
    try:
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_PATH)
    except OSError:
        return cands, pacs
    try:
        def q(name):
            try:
                return winreg.QueryValueEx(k, name)[0]
            except OSError:
                return None

        if q("ProxyEnable") == 1:
            server = (q("ProxyServer") or "").strip()
            if server:
                if ";" in server:  # 分协议格式: http=...;https=...;socks=...
                    for part in server.split(";"):
                        pm = re.match(r"^(http|https|socks)=(.+):(\d+)$", part.strip(), re.I)
                        if pm:
                            t = "socks5" if pm.group(1).lower() == "socks" else "http"
                            cands.append(_mk(t, pm.group(2), pm.group(3), "注册表"))
                else:
                    sm = re.match(r"^([^/:]+):(\d+)$", server)
                    if sm:
                        cands.append(_mk("http", sm.group(1), sm.group(2), "注册表"))
        acu = q("AutoConfigURL")
        if acu:
            pacs.append(acu)
    finally:
        winreg.CloseKey(k)
    return cands, pacs


async def candidates_from_pac(url: str) -> list[dict]:
    """拉取 PAC 文本，正则提取 PROXY/SOCKS 声明作为候选（活不活着交给测速验证）。"""
    out = []
    try:
        async with httpx.AsyncClient(timeout=4, follow_redirects=True) as cli:
            r = await cli.get(url, headers={"User-Agent": UA})
            text = r.text
    except Exception:
        return out
    for m in re.finditer(r"PROXY\s+([^;:\s]+):(\d+)", text, re.I):
        out.append(_mk("http", m.group(1), m.group(2), "PAC"))
    for m in re.finditer(r"SOCKS5?\s+([^;:\s]+):(\d+)", text, re.I):
        out.append(_mk("socks5", m.group(1), m.group(2), "PAC"))
    return out


async def _tcp_open(host: str, port: int, timeout=0.8) -> bool:
    try:
        _r, _w = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        _w.close()
        return True
    except Exception:
        return False


async def classify(host: str, port: int, source: str) -> dict | None:
    """探测端口是 HTTP 代理还是 SOCKS5 代理（都通不动则返回 None）。"""
    if not await _tcp_open(host, port):
        return None
    for t in ("http", "socks5"):
        try:
            async with httpx.AsyncClient(
                proxy=f"{t}://{host}:{port}",
                timeout=httpx.Timeout(4, connect=3),
                follow_redirects=True,
            ) as cli:
                r = await cli.get(PROBE_URL, headers={"User-Agent": UA})
                if r.status_code < 500:
                    return _mk(t, host, port, source)
        except Exception:
            continue
    return None


async def detect(scan_ports: list[int]) -> list[dict]:
    """三级检测，按 (host,port) 去重（来源优先级：环境变量 > 注册表 > PAC > 扫描）。"""
    prio = {"环境变量": 0, "注册表": 1, "PAC": 2, "端口扫描": 3}
    found: dict[tuple, dict] = {}

    def add(c: dict):
        key = (c["host"], c["port"])
        if key not in found or prio[c["source"]] < prio[found[key]["source"]]:
            found[key] = c

    for c in candidates_from_env():
        add(c)
    reg, pac_urls = candidates_from_registry()
    for c in reg:
        add(c)
    for u in pac_urls[:2]:
        for c in await candidates_from_pac(u):
            add(c)

    # 端口扫描兜底：只探测还没被上面发现的端口
    known_ports = {p for (_h, p) in found}
    open_ports = [p for p in dict.fromkeys(scan_ports) if p not in known_ports
                  and await _tcp_open("127.0.0.1", p)]
    scanned = await asyncio.gather(
        *[classify("127.0.0.1", p, "端口扫描") for p in open_ports])
    for c in scanned:
        if c:
            add(c)

    return sorted(found.values(), key=lambda c: prio[c["source"]])
