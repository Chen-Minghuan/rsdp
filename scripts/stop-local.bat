@echo off
chcp 65001 >nul

rem RSDP 本地开发环境一键停止脚本（Windows）
rem 停止由 start-local.bat 启动的 Java / Node 进程

echo 正在停止 RSDP 后端（Java）...
taskkill /FI "WINDOWTITLE eq RSDP Backend :8081" /F >nul 2>&1
taskkill /IM java.exe /FI "WINDOWTITLE eq RSDP Backend :8081" /F >nul 2>&1

echo 正在停止 RSDP 前端（Node）...
taskkill /FI "WINDOWTITLE eq RSDP Frontend :5173" /F >nul 2>&1
taskkill /IM node.exe /FI "WINDOWTITLE eq RSDP Frontend :5173" /F >nul 2>&1

echo.
echo 前后端进程已尝试停止。
pause
