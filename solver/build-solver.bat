@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "JAVAC=javac"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for %%i in (javac.exe) do if not "%%~$PATH:i"=="" set "JAVAC=%%~$PATH:i"

if not exist "jocl.jar" (
  echo [!] 缺少 jocl.jar —— 从发布包里复制过来，或下载 org.jocl:jocl:2.0.4
  pause
  exit /b 1
)

if not exist "out" mkdir out
"%JAVAC%" --release 17 -encoding UTF-8 -cp "jocl.jar" -d out ui\SolverCore.java ui\SolverGui.java
if errorlevel 1 (
  echo [!] 编译失败
  pause
  exit /b 1
)

jar cfe gtnh-seed-solver.jar SolverGui -C out .
if errorlevel 1 (
  echo [!] 打包失败
  pause
  exit /b 1
)

echo.
echo 编译完成: gtnh-seed-solver.jar
echo 运行: java -cp "gtnh-seed-solver.jar;jocl.jar" SolverGui
pause
