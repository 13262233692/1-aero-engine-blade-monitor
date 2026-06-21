@echo off
chcp 65001 >nul
title Aero-Engine Blade Monitor - 启动脚本

echo.
echo ========================================================
echo   民航发动机试车台叶片动应力实时遥测系统
echo   Aero-Engine Blade Dynamic Stress Telemetry System
echo ========================================================
echo.

set PROJECT_ROOT=%~dp0
set BACKEND_DIR=%PROJECT_ROOT%backend
set FRONTEND_DIR=%PROJECT_ROOT%frontend

echo [1/3] 启动 Spring Boot 后端 (端口 8080, UDP 9000)...
echo.

cd /d "%BACKEND_DIR%"

if not exist "target\blade-monitor-1.0.0.jar" (
    echo 未找到预编译 JAR，正在执行 Maven 构建...
    call mvnw.cmd clean package -DskipTests -q 2>nul
    if errorlevel 1 (
        echo Maven Wrapper 失败，尝试系统 mvn...
        call mvn clean package -DskipTests
        if errorlevel 1 (
            echo [错误] 后端编译失败，请检查 Maven 环境
            pause
            exit /b 1
        )
    )
)

start "Backend - Blade Monitor" cmd /k "cd /d "%BACKEND_DIR%" && java -jar target\blade-monitor-1.0.0.jar"

echo.
echo [2/3] 等待后端启动 15 秒...
timeout /t 15 /nobreak >nul

echo.
echo [3/3] 启动 React 前端开发服务器 (端口 3000)...
echo.

cd /d "%FRONTEND_DIR%"

if not exist "node_modules" (
    echo 首次启动，安装前端依赖...
    call npm install
    if errorlevel 1 (
        echo [错误] npm install 失败，请检查 Node.js 环境
        pause
        exit /b 1
    )
)

echo.
echo ========================================================
echo   启动完成！
echo   - 后端 API:    http://localhost:8080
echo   - WebSocket:   ws://localhost:8080/ws/telemetry
echo   - UDP 接收:    127.0.0.1:9000
echo   - 前端界面:    http://localhost:3000
echo ========================================================
echo.

start "Frontend - React Dev Server" cmd /k "cd /d "%FRONTEND_DIR%" && npm run dev"

echo.
echo 正在打开浏览器...
timeout /t 5 /nobreak >nul
start http://localhost:3000

echo.
echo 如需停止，请关闭此窗口或对应的后端/前端命令行窗口。
pause
