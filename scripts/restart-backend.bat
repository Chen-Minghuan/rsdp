@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem RSDP 本地开发环境后端单独重启脚本（Windows）
rem 只重启后端，不碰前端 dev server

cd /d "%~dp0\.."
set PROJECT_ROOT=%CD%
set ENV_FILE=%PROJECT_ROOT%\deploy\.env
set LOG_DIR=%PROJECT_ROOT%\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

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
    for /f "tokens=*" %%k in ("!raw_key!") do set "key=%%k"
    if not "!key:~0,1!=="#" (
        if not "!key!=="" (
            set "!key!=!raw_val!"
        )
    )
)

set RSDP_JWT_COOKIE_SECURE=false
if defined POSTGRES_USER (
    set DB_USERNAME=%POSTGRES_USER%
) else (
    set DB_USERNAME=rsdp
)
if defined POSTGRES_PASSWORD (
    set DB_PASSWORD=%POSTGRES_PASSWORD%
) else (
    set DB_PASSWORD=rsdp
)

call :validate_secret RSDP_ENCRYPTION_KEY
if %ERRORLEVEL% neq 0 exit /b 1
call :validate_secret RSDP_JWT_SECRET
if %ERRORLEVEL% neq 0 exit /b 1

echo [1/2] 停止现有后端进程...
taskkill /F /FI "WINDOWTITLE eq RSDP Backend :8081" >nul 2>&1 || true

echo [2/2] 启动后端 Spring Boot（端口 8081）...
start "RSDP Backend :8081" /d "%PROJECT_ROOT%\server" cmd /k "mvn spring-boot:run -Dspring-boot.run.profiles=dev ^>^> "%LOG_DIR%\backend.log" 2^>^&1 & echo 后端已启动，按 Ctrl+C 停止后关闭窗口 & pause"
echo 后端启动中，约需 10~20 秒...
exit /b 0

:validate_secret
if not defined %1 (
    echo [错误] 环境变量 %1 未设置
    pause
    exit /b 1
)
set "val=!%1!"
if "!val!=="<CHANGE_ME>" (
    echo [错误] 环境变量 %1 不能为默认值 ^<CHANGE_ME^>
    pause
    exit /b 1
)
exit /b 0
