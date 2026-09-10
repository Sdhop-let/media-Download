@echo off
chcp 65001 >nul
title 媒体下载器
cd /d "%~dp0"

if not exist .venv\Scripts\python.exe (
  echo [首次运行] 正在创建虚拟环境并安装依赖，可能需要几分钟...
  python -m venv .venv || goto :err
  .venv\Scripts\python -m pip install --disable-pip-version-check -r requirements.txt || goto :err
)

echo 启动中... 浏览器将自动打开 http://127.0.0.1:8765/
start "" "http://127.0.0.1:8765/"
.venv\Scripts\python run_server.py
goto :eof

:err
echo 启动失败：请确认已安装 Python 3.10+ 并加入 PATH
pause
