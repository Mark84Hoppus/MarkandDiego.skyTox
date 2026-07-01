@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" (
  echo [skyTox server] .env not found. Copy .env.example to .env and edit it first.
  pause
  exit /b 1
)
node src\server.js
pause
