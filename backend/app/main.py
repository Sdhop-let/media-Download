"""媒体下载器后端入口：python -m backend.app.main 或 run_server.py。"""
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from . import db
from .routes import router
from .runtime import pm, tm

STATIC = Path(__file__).parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.connect()
    await pm.start()
    await tm.start()
    yield
    for t in pm._tasks:
        t.cancel()
    for w in tm._workers:
        w.cancel()


app = FastAPI(title="社交媒体媒体下载器", lifespan=lifespan)
app.include_router(router)
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


def run(host: str = "127.0.0.1", port: int = 8765):
    uvicorn.run(app, host=host, port=port, log_level="warning")


if __name__ == "__main__":
    run()
