@echo off
rem MiniDB Studio 一键启动：检查 Java/端口 -> 启动 Web 服务器 -> 验证健康接口 -> 打开浏览器
chcp 65001 >nul
title MiniDB Studio
cd /d "%~dp0"

set "STUDIO_URL=http://127.0.0.1:8080"
set "HEALTH_URL=%STUDIO_URL%/api/v1/health"

set JAVA_EXE=tools\jdk-17.0.20.1+1\bin\java.exe
if not exist "%JAVA_EXE%" (
  where java >nul 2>nul && set "JAVA_EXE=java" || (
    echo [错误] 未找到 Java。请确认 tools\jdk-17.0.20.1+1 存在，或先运行 build-studio.bat。
    pause
    exit /b 1
  )
)

set JAR=target\minidb-0.1.0-SNAPSHOT-studio.jar
if not exist "%JAR%" (
  echo [错误] 未找到 %JAR%。
  echo 请先运行 build-studio.bat 完成构建（或确认 JAR 名称后修改本脚本）。
  call :pause_if_needed
  exit /b 1
)

rem 启动前先确认 8080 没有被其他进程占用，避免把无关服务误判为 MiniDB Studio。
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient;try{$t=$c.ConnectAsync('127.0.0.1',8080);if($t.Wait(1000)-and $c.Connected){exit 1}}catch{}finally{$c.Dispose()};exit 0" >nul 2>nul
if errorlevel 1 (
  echo [错误] 端口 8080 已被占用，MiniDB Studio 未启动。
  echo 请关闭占用该端口的程序后重试。可运行：
  echo   netstat -ano ^| findstr ":8080"
  call :pause_if_needed
  exit /b 1
)

echo 正在启动 MiniDB Studio（数据目录：当前文件夹）...
start "MiniDB-Studio-Server" /min cmd.exe /d /c call "%JAVA_EXE%" "-Dfile.encoding=UTF-8" -jar "%JAR%" --port 8080 --data-dir "."

rem 最多等待 30 秒；只有健康接口明确返回 MiniDB Studio 身份才算启动成功。
rem MINIDB_STUDIO_STARTUP_TIMEOUT 仅供自动验证时缩短等待，合法范围为 1..300 秒。
powershell -NoProfile -Command "$timeout=30;$n=0;if([int]::TryParse($env:MINIDB_STUDIO_STARTUP_TIMEOUT,[ref]$n)-and $n-ge 1-and $n-le 300){$timeout=$n};$deadline=(Get-Date).AddSeconds($timeout);while((Get-Date)-lt $deadline){try{$r=Invoke-RestMethod -Uri '%HEALTH_URL%' -Method Get -TimeoutSec 2;if($r.success-eq $true-and $r.data.status-eq 'ok'-and $r.data.service-eq 'minidb-studio'){exit 0};exit 2}catch{if($_.Exception.Response){exit 2}};Start-Sleep -Milliseconds 500};exit 1" >nul 2>nul
if errorlevel 2 (
  echo [错误] 端口 8080 有响应，但健康信息不属于 MiniDB Studio。
  echo 为避免打开错误页面，浏览器不会启动。请关闭占用端口的程序后重试。
  call :pause_if_needed
  exit /b 1
)
if errorlevel 1 (
  echo [错误] MiniDB Studio 未能在限定时间内通过健康检查。
  echo 服务可能启动失败或仍未就绪；请查看 "MiniDB-Studio-Server" 窗口中的错误信息后重试。
  echo 健康检查地址：%HEALTH_URL%
  call :pause_if_needed
  exit /b 1
)

echo 就绪！已确认 MiniDB Studio 健康接口：%HEALTH_URL%
if not "%MINIDB_STUDIO_NO_BROWSER%"=="1" (
  echo 正在打开浏览器：%STUDIO_URL%
  start "" "%STUDIO_URL%"
)
echo.
echo 提示：关闭本窗口不会停止服务；在任务栏关闭 "MiniDB-Studio-Server" 窗口即可停止。
echo 仅本机可访问（127.0.0.1）。
call :pause_if_needed
exit /b 0

:pause_if_needed
if not "%MINIDB_STUDIO_NO_PAUSE%"=="1" pause
exit /b 0
