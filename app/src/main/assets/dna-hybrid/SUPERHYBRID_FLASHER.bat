@echo off
setlocal EnableExtensions
color 2F
title {{DEVICE_MODEL}} Hybrid Flasher
cd /d %~dp0
set "FASTBOOT=Platform-Tools\fastboot.exe"
if not exist "%FASTBOOT%" set "FASTBOOT=fastboot"
if not exist "probiotics\*.img" echo ERROR: No IMG files found in probiotics.& pause & exit /B 1
echo ================================================
echo {{DEVICE_MODEL}} 线卡一体包刷写工具
echo 移植机型: {{PORT_MODEL}}
echo 制作者: {{SCRIPT_AUTHOR}}
echo ================================================
echo 正在刷写 boot 类分区...
for %%G in (probiotics\*.img) do (
  if /I not "%%~nG"=="super" (
    "%FASTBOOT%" flash --slot=all "%%~nG" "%%G"
    if errorlevel 1 echo 刷写失败: %%~nG & pause & exit /B 1
  )
)
echo 正在重启到 fastbootd，请确认设备已进入 fastbootd...
"%FASTBOOT%" reboot fastboot
if errorlevel 1 pause & exit /B 1
pause
if exist "probiotics\super.img" "%FASTBOOT%" flash super "probiotics\super.img"
"%FASTBOOT%" set-active a
echo 刷写完成。请按需双清数据后重启。
endlocal
pause
