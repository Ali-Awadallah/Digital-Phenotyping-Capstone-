@echo off
echo ============================================
echo  Digital Phenotyping IDS - Server Startup
echo ============================================
echo.

:: Kill any existing process on port 8080
echo Checking for existing process on port 8080...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    echo Found process %%a using port 8080. Terminating...
    taskkill /F /PID %%a >nul 2>&1
)
echo Port 8080 is now available.
echo.

:: Start the server
echo Starting AWARE Micro server...
echo.
call gradlew.bat run
