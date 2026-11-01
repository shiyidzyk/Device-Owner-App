@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title WifiLock 离线 PIN 重置
rem ---- 寻找可用的 Python（先试 PATH，再试常见安装路径；跳过商店占位符）----
set "PY="
python -c "print(1)" >nul 2>nul && set "PY=python"
if not defined PY python3 -c "print(1)" >nul 2>nul && set "PY=python3"
if not defined PY py -3 -c "print(1)" >nul 2>nul && set "PY=py -3"
if not defined PY for /d %%D in ("%LocalAppData%\Programs\Python\Python*") do if exist "%%D\python.exe" set "PY=%%D\python.exe"
if not defined PY if exist "%LocalAppData%\Python\bin\python3.exe" set "PY=%LocalAppData%\Python\bin\python3.exe"
if not defined PY for /d %%D in ("C:\Python*") do if exist "%%D\python.exe" set "PY=%%D\python.exe"
if not defined PY if exist "C:\Program Files\Python3\python.exe" set "PY=C:\Program Files\Python3\python.exe"
if not defined PY (
    echo [X] 未找到可用的 Python。请安装 Python 并勾选 "Add python.exe to PATH"。
    pause
    exit /b 1
)
rem ---- 依赖检查（缺则自动安装）----
%PY% -c "import cryptography" >nul 2>nul
if errorlevel 1 (
    echo [..] 首次使用：正在安装依赖 cryptography，请稍候 ...
    %PY% -m pip install cryptography
    %PY% -c "import cryptography" >nul 2>nul
    if errorlevel 1 (
        echo [X] 依赖安装失败，请手动执行: "%PY%" -m pip install cryptography
        pause
        exit /b 1
    )
)
if not exist "wifilock_reset_private.key" (
    echo [!] 提示：本目录没有 wifilock_reset_private.key。
    echo     私钥来自 App 启用应用锁时显示的"重置私钥"（只显示一次），
    echo     如保存在别处请复制到本目录，或用 --key 指定路径（见 pin_reset.py）。
    echo.
)
set "CODE="
set /p CODE=粘贴申请码 (WFL1-...) 后回车:
if "%CODE%"=="" (
    echo [X] 未输入申请码。
    pause
    exit /b 1
)
echo.
"%PY%" "%~dp0pin_reset.py" reset "%CODE%" --key "%~dp0wifilock_reset_private.key"
echo.
echo 把上面 WFR1- 开头的重置码整行复制，回 App"忘记 PIN"页粘贴即可。
pause