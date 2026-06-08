@echo off
setlocal
title Compilar NextNodes Permissions
cd /d "%~dp0"

echo ==================================================
echo   Compilando NextNodes Permissions (NeoForge 1.21.1)
echo ==================================================
echo.

call "%~dp0gradlew.bat" build --console=plain
set "BUILD_RESULT=%errorlevel%"

echo.
if not "%BUILD_RESULT%"=="0" (
    echo [ERROR] La compilacion fallo ^(codigo %BUILD_RESULT%^).
    echo Revisa los mensajes de arriba.
    echo.
    pause
    exit /b %BUILD_RESULT%
)

echo ==================================================
echo   BUILD CORRECTO
echo ==================================================
echo.
echo Jar listo para el servidor (uno solo, con MongoDB ya embebido):
for %%F in ("build\libs\*.jar") do echo    build\libs\%%~nxF
echo.
pause
endlocal
