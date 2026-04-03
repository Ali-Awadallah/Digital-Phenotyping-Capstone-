@echo off
setlocal

set PY_EXE=python
where %PY_EXE% >nul 2>nul
if errorlevel 1 (
  echo Python not found in PATH.
  exit /b 1
)

%PY_EXE% rotate_keys.py %*
if errorlevel 1 exit /b 1

echo.
echo Keys rotated. Restart backend/signature-engine containers.
exit /b 0

