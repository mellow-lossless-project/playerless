@echo off
rem Playerless: porta le playlist di Spotify sul telefono. Vedi importa-spotify.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0importa-spotify.ps1" %*
pause
