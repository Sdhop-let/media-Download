"""镜像 Chaquopy 16.0.0 运行时组件到本地 Maven 仓库（绕开 GFW 黑洞）。
下载源经 Clash 7897 代理；输出目录 D:/AndroidDev/maven-local。
"""
import os
import re
import urllib.request

BASE = "https://repo.maven.apache.org/maven2/com/chaquo/python"
LOCAL = r"D:/AndroidDev/maven-local/com/chaquo/python"
PROXY = {"https": "http://127.0.0.1:7897", "http": "http://127.0.0.1:7897"}
opener = urllib.request.build_opener(urllib.request.ProxyHandler(PROXY))
urllib.request.install_opener(opener)

SKIP_EXT = (".asc", ".sha256", ".sha512", ".md5")  # 校验文件由 Gradle 侧容忍缺失


def get(url):
    return urllib.request.urlopen(url, timeout=60).read()


def entries(url):
    """目录下全部条目（文件 + 子目录）。"""
    html = get(url).decode()
    return [m for m in re.findall(r'href="([^"]+)"', html) if m != "../"]


def fetch(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    total = None
    for attempt in range(6):
        try:
            req = urllib.request.Request(url)
            if have:
                req.add_header("Range", f"bytes={have}-")
            resp = urllib.request.urlopen(req, timeout=120)
            if total is None:
                cr = resp.headers.get("Content-Range")
                cl = resp.headers.get("Content-Length")
                if cr and "/" in cr:
                    total = int(cr.rsplit("/", 1)[1])
                elif cl:
                    total = int(cl)
            mode = "ab" if have else "wb"
            with open(part, mode) as f:
                while True:
                    chunk = resp.read(1 << 16)
                    if not chunk:
                        break
                    f.write(chunk)
                    have += len(chunk)
            if total is None or have >= total:
                os.replace(part, dest)
                print(f"ok {have//1024}KB", os.path.basename(dest))
                return
        except Exception as e:
            print(f"retry{attempt} ({have//1024}KB) {e}", flush=True)
    raise RuntimeError(f"download failed: {url}")


def mirror_dir(group_dir, version):
    """镜像 com/chaquo/python/<group_dir>/<version>/ 下全部非签名文件。"""
    url = f"{BASE}/{group_dir}/{version}/"
    for name in entries(url):
        if name.endswith(SKIP_EXT) or name.endswith("/"):
            continue
        fetch(url + name, os.path.join(LOCAL, group_dir, version, name))


def latest_version(group_dir, prefer="16.0.0"):
    """列组件全部版本；优先精确 16.0.0，否则取最高版本。"""
    url = f"{BASE}/{group_dir}/"
    vs = [v.rstrip("/") for v in entries(url)
          if not v.startswith("maven-metadata")]
    if prefer in vs:
        return prefer
    def key(v):
        return tuple(int(x) if x.isdigit() else (-1, x) for x in re.split(r"[.-]", v))
    return sorted(vs, key=key)[-1]


def main():
    os.makedirs(LOCAL, exist_ok=True)
    # target：3.12 系列（镜像最新版 3.12.12-0；如插件选别的版本，报错后补）
    for v in ["3.12.12-0"]:
        mirror_dir("target", v)
    for f in ["maven-metadata.xml", "maven-metadata.xml.sha1"]:
        try:
            fetch(f"{BASE}/target/{f}", os.path.join(LOCAL, "target", f))
        except Exception as e:
            print("metadata skip:", e)
    # runtime：五个组件，各取 16.0.0（无则最新版）
    for comp in ["runtime/bootstrap", "runtime/chaquopy", "runtime/chaquopy_android",
                 "runtime/chaquopy_java", "runtime/libchaquopy_java"]:
        try:
            v = latest_version(comp)
            print(f"[{comp}] use version {v}")
            mirror_dir(comp, v)
        except Exception as e:
            print(f"[{comp}] FAILED: {e}")
    print("DONE")


if __name__ == "__main__":
    main()
