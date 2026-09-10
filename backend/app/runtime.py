"""运行时单例：所有服务共享一个 ProxyManager / TaskManager。"""
from .events import bus
from .services.downloader import TaskManager
from .services.proxy_tester import ProxyManager

pm = ProxyManager()
tm = TaskManager(pm)
