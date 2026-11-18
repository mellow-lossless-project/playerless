@echo off
rem Playerless per PC: compila build\Playerless.exe. Vedi build.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 pause
