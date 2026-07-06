@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem RSDP 本地开发环境一键启动脚本（Windows）
rem 会在两个新 CMD 窗口中分别启动后端和前端 dev server

cd /d "%~dp0\.."
set PROJECT_ROOT=%CD%
set ENV_FILE=%PROJECT_ROOT%\deploy\.env
set LOG_DIR=%PROJECT_ROOT%\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

rem 检查环境变量文件
if not exist "%ENV_FILE%" (
    echo [错误] 找不到环境变量文件 %ENV_FILE%
    echo 请先复制 deploy\.env.example 为 deploy\.env 并填写真实密钥
    pause
    exit /b 1
)

rem 读取 .env 文件（跳过注释与空行）
for /f "usebackq tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    set "raw_key=%%a"
    set "raw_val=%%b"
    rem 去除 key 首尾空格
    for /f "tokens=*" %%k in ("!raw_key!") do set "key=%%k"
    if not "!key:~0,1!"=="#" (
        if not "!key!"=="" (
            set "!key!=!raw_val!"
        )
    )
)

rem 本地开发使用 http，必须关闭 Cookie 的 Secure 属性
set RSDP_JWT_COOKIE_SECURE=false

rem 校验关键敏感变量
call :validate_secret RSDP_ENCRYPTION_KEY
if %ERRORLEVEL% neq 0 exit /b 1
call :validate_secret RSDP_JWT_SECRET
if %ERRORLEVEL% neq 0 exit /b 1

rem 若配置了 DASHSCOPE_API_KEY，则不允许使用占位符
if defined DASHSCOPE_API_KEY (
    if "!DASHSCOPE_API_KEY!"=="<CHANGE_ME>" (
        echo [错误] 环境变量 DASHSCOPE_API_KEY 不能为默认值 ^<CHANGE_ME^>
        echo 请在 %ENV_FILE% 中填入真实的 DashScope API Key，或将其删除/留空
        pause
        exit /b 1
    )
)

echo ==========================================
echo  RSDP 本地开发环境启动
echo ==========================================
echo.
echo 项目根目录: %PROJECT_ROOT%
echo 环境文件: %ENV_FILE%
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
echo 关闭服务请直接关闭对应的 CMD 窗口，或运行 scripts/stop-local.bat
echo.
pause
exit /b 0

:validate_secret
if not defined %1 (
    echo [错误] 环境变量 %1 未设置
    echo 请在 %ENV_FILE% 中设置强随机值后再启动
    pause
    exit /b 1
)
set "val=!%1!"
if "!val!"=="<CHANGE_ME>" (
    echo [错误] 环境变量 %1 不能为默认值 ^<CHANGE_ME^>
    echo 请在 %ENV_FILE% 中设置强随机值后再启动
    pause
    exit /b 1
)
exit /b 0
