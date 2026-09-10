"""进程内事件总线：SSE 实时推送（任务进度 / 代理变更）。"""
import asyncio


class EventBus:
    def __init__(self):
        self._subs: dict[int, asyncio.Queue] = {}

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        self._subs[id(q)] = q
        return q

    def unsubscribe(self, q: asyncio.Queue):
        self._subs.pop(id(q), None)

    def publish(self, etype: str, data: dict | None = None):
        msg = {"type": etype, "data": data or {}}
        for q in list(self._subs.values()):
            try:
                q.put_nowait(msg)
            except asyncio.QueueFull:
                pass


bus = EventBus()
