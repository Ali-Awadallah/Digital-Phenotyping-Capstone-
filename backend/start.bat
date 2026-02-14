@echo off
echo ========================================
echo  Digital Phenotyping - Docker Startup
echo ========================================
echo.

:: Kill anything on port 8080
echo [1/3] Clearing port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo       Killing PID %%a on port 8080
    taskkill /F /PID %%a >nul 2>&1
)
echo       Port 8080 is free.
echo.

:: Kill anything on port 8081
echo [2/3] Clearing port 8081...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8081 " ^| findstr "LISTENING"') do (
    echo       Killing PID %%a on port 8081
    taskkill /F /PID %%a >nul 2>&1
)
echo       Port 8081 is free.
echo.

:: Start Docker Compose
echo [3/3] Starting Docker Compose...
docker compose up -d --build
echo.

echo ========================================
echo  All services started!
echo  Dashboard:  http://localhost:8080
echo  MySQL:      localhost:3307
echo ========================================
echo.
docker compose ps
pause
