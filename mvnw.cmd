@echo off
rem Minimal Maven bootstrap: downloads Maven once into %USERPROFILE%\.m2\wrapper and runs it.
setlocal
set "MVN_VER=3.9.9"
set "MVN_BASE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVN_VER%"
set "MVN_CMD=%MVN_BASE%\apache-maven-%MVN_VER%\bin\mvn.cmd"
if not exist "%MVN_CMD%" (
  echo Downloading Maven %MVN_VER% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; New-Item -ItemType Directory -Force '%MVN_BASE%' | Out-Null; $z = Join-Path '%MVN_BASE%' 'maven.zip'; Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VER%/apache-maven-%MVN_VER%-bin.zip' -OutFile $z; Expand-Archive -Force $z '%MVN_BASE%'; Remove-Item $z"
  if errorlevel 1 exit /b 1
)
"%MVN_CMD%" %*
