# Script de ejecución en PowerShell para PlayMatch (PostgreSQL / SQLite)
$ErrorActionPreference = "Stop"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  INICIANDO PLATAFORMA PLAYMATCH (POSTGRESQL / SQLITE)   " -ForegroundColor Magenta
Write-Host "=========================================================" -ForegroundColor Cyan

$javaBin = "C:\Users\igvil\.vscode\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64\bin"
$javac = Join-Path $javaBin "javac.exe"
$java = Join-Path $javaBin "java.exe"

Set-Location $PSScriptRoot

Write-Host "[1/3] Compilando código Java + Drivers PostgreSQL (HikariCP) y SQLite..." -ForegroundColor Yellow
if (-not (Test-Path "bin")) { New-Item -ItemType Directory -Path "bin" | Out-Null }
& $javac -encoding UTF-8 -cp "lib/*" -d "bin" -sourcepath "src" "src/com/playmatch/ServidorPlayMatch.java"

Write-Host "[2/3] Compilación exitosa." -ForegroundColor Green
Write-Host "[3/3] Abriendo navegador en http://localhost:8080/login.html ..." -ForegroundColor Cyan
Start-Process "http://localhost:8080/login.html"

& $java -cp "bin;lib/*" com.playmatch.ServidorPlayMatch 8080
