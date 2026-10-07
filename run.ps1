# Script de ejecución en PowerShell para PlayMatch
$ErrorActionPreference = "Stop"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "      INICIANDO PLATAFORMA PLAYMATCH (FASE 2 - MVP)      " -ForegroundColor Magenta
Write-Host "=========================================================" -ForegroundColor Cyan

$javaBin = "C:\Users\igvil\.vscode\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64\bin"
$javac = Join-Path $javaBin "javac.exe"
$java = Join-Path $javaBin "java.exe"

$fase2Dir = Join-Path $PSScriptRoot "Fase_2"
Set-Location $fase2Dir

Write-Host "[1/3] Compilando código fuente Java..." -ForegroundColor Yellow
if (-not (Test-Path "bin")) { New-Item -ItemType Directory -Path "bin" | Out-Null }
& $javac -d "bin" -sourcepath "src" "src/com/playmatch/PlayMatchServer.java"

Write-Host "[2/3] Compilación exitosa." -ForegroundColor Green
Write-Host "[3/3] Abriendo navegador en http://localhost:8080 ..." -ForegroundColor Cyan
Start-Process "http://localhost:8080"

& $java -cp "bin" com.playmatch.PlayMatchServer 8080
