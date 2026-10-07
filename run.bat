@echo off
chcp 65001 > nul
echo =========================================================
echo       INICIANDO PLATAFORMA PLAYMATCH (FASE 2 - MVP)      
echo =========================================================

set JAVA_BIN=C:\Users\igvil\.vscode\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64\bin
set JAVAC_EXE="%JAVA_BIN%\javac.exe"
set JAVA_EXE="%JAVA_BIN%\java.exe"

cd /d "%~dp0Fase_2"

echo [1/3] Compilando codigo Java...
if not exist "bin" mkdir "bin"
%JAVAC_EXE% -d bin -sourcepath src src/com/playmatch/PlayMatchServer.java
if %errorlevel% neq 0 (
    echo [ERROR] La compilacion ha fallado.
    pause
    exit /b %errorlevel%
)

echo [2/3] Compilacion completada con exito.
echo [3/3] Iniciando servidor web en http://localhost:8080 ...
start http://localhost:8080
%JAVA_EXE% -cp bin com.playmatch.PlayMatchServer 8080
pause
