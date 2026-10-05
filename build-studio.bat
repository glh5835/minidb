@echo off
rem 构建 MiniDB Studio：前端 npm build -> Java resources -> fat JAR
chcp 65001 >nul
cd /d "%~dp0"

echo [1/4] 构建前端（npm run build）...
cd frontend
if not exist node_modules (
  echo   安装前端依赖...
  call npm install || goto :fail
)
call npm run build || goto :fail
cd ..

echo [2/4] 复制前端产物到 Java resources...
if exist src\main\resources\webroot rmdir /s /q src\main\resources\webroot
xcopy frontend\dist src\main\resources\webroot\ /e /i /q >nul || goto :fail

echo [3/4] 设置构建环境...
set "JAVA_HOME=%~dp0tools\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%~dp0tools\apache-maven-3.9.16\bin;%PATH%"

echo [4/4] Maven 打包（含 Studio fat jar）...
call mvn -s tools\mvn-settings.xml -DskipTests package || goto :fail

echo.
echo 构建完成：target\minidb-0.1.0-SNAPSHOT-studio.jar
echo 双击 "启动 MiniDB Studio.bat" 即可使用。
pause
exit /b 0

:fail
echo.
echo 构建失败，请检查上方错误信息。
pause
exit /b 1
