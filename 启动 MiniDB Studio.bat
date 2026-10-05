@echo off
rem MiniDB Studio 一键启动：检查 Java -> 启动 Web 服务器 -> 等待端口 -> 打开浏览器
chcp 65001 >nul
title MiniDB Studio
cd /d "%~dp0"

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
  pause
  exit /b 1
)

echo 正在启动 MiniDB Studio（数据目录：当前文件夹）...
start "MiniDB-Studio-Server" /min cmd /c ""%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --port 8080 --data-dir "%~dp0""

rem 等待端口就绪（最多 30 秒）
powershell -NoProfile -Command "for($i=0;$i -lt 60;$i++){try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',8080);$c.Close();exit 0}catch{Start-Sleep -m 500}};exit 1" >nul 2>nul
if errorlevel 1 (
  echo [错误] 服务器未能启动，请重试或检查端口 8080 是否被占用。
  pause
  exit /b 1
)

echo 就绪！正在打开浏览器：http://127.0.0.1:8080
start "" "http://127.0.0.1:8080"
echo.
echo 提示：关闭本窗口不会停止服务；在任务栏关闭 "MiniDB-Studio-Server" 窗口即可停止。
echo 仅本机可访问（127.0.0.1）。
pause
