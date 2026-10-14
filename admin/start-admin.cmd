@echo off
cd /d %~dp0
echo Starting Hinterlay admin on http://localhost:8099 (close this window to stop)
start http://localhost:8099/index.html
python -m http.server 8099
