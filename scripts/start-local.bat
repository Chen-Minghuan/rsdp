@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem RSDP 本地开发环境一键启动脚本（Windows）
rem 会在两个新 CMD 窗口中分别启动后端和前端 dev server

cd /d "%~dp0\.."
set PROJECT_ROOT=%CD%
set LOG_DIR=%PROJECT_ROOT%\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

rem 本地开发默认密钥：仅在开发环境使用，生产环境必须通过环境变量注入真实密钥
if not defined RSDP_ENCRYPTION_KEY set RSDP_ENCRYPTION_KEY=WYs8rXkOCYazXad8RLS2lP5qmWI6fYPCd0HQ72fXSnY=
if not defined RSDP_JWT_SECRET set RSDP_JWT_SECRET=g3g6Ryj6ty4Dsw0jm1ImR59dbRAOI98q3qKVf7gz0jU=

echo ==========================================
echo  RSDP 本地开发环境启动
echo ==========================================
echo.
echo 项目根目录: %PROJECT_ROOT%
echo.

rem 检查必要命令是否存在
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到 mvn 命令，请确认 Maven 已安装并加入系统 PATH
    pause
    exit /b 1
)

where pnpm >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到 pnpm 命令，请确认 pnpm 已安装并加入系统 PATH
    pause
    exit /b 1
)

echo [1/2] 启动后端 Spring Boot（端口 8081）...
echo 后端日志: %LOG_DIR%\backend.log
start "RSDP Backend :8081" /d "%PROJECT_ROOT%\server" cmd /k "mvn spring-boot:run -Dspring-boot.run.profiles=dev ^>^> "%LOG_DIR%\backend.log" 2^>^&1 & echo 后端已启动，按 Ctrl+C 停止后关闭窗口 & pause"

echo [2/2] 等待 10 秒后启动前端 Vite（端口 5173）...
timeout /t 10 /nobreak >nul
start "RSDP Frontend :5173" /d "%PROJECT_ROOT%\web" cmd /k "pnpm dev ^>^> "%LOG_DIR%\frontend.log" 2^>^&1 & echo 前端已启动，按 Ctrl+C 停止后关闭窗口 & pause"

echo.
echo ==========================================
echo  启动命令已发送
echo ==========================================
echo 后端地址: http://localhost:8081
echo 前端地址: http://localhost:5173
echo 日志目录: %LOG_DIR%
echo.
echo 如果新窗口一闪而过，请查看上述日志文件
echo 关闭服务请直接关闭对应的 CMD 窗口，或运行 scripts/stop-local.bat
echo.
pause
