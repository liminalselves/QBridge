@echo off
setlocal
rem ============================================================
rem  QBridge 一键构建
rem  用法:  build.bat [debug|release]    默认 release
rem  本机默认路径写在下面，机器不同直接改；也可用同名环境变量覆盖。
rem ============================================================

if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Windows8.1\jdk21"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=F:\env\gradle_home"
if "%ANDROID_USER_HOME%"=="" set "ANDROID_USER_HOME=F:\env\android-user-home"
set "GRADLE_CMD=F:\env\gradle-8.7\bin\gradle.bat"
if not "%GRADLE_HOME%"=="" set "GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat"

rem ANDROID_SDK_HOME 与 ANDROID_USER_HOME 指向不同目录时 AGP 会拒绝启动，这里强制移除前者。
set ANDROID_SDK_HOME=

cd /d "%~dp0"

if not exist "local.properties" (
    echo [错误] 缺少 local.properties，请参照 README.md「构建」一节创建。
    pause
    exit /b 1
)

if not exist "%GRADLE_CMD%" (
    echo [错误] 未找到 Gradle: %GRADLE_CMD%
    pause
    exit /b 1
)

set "TASK=assembleRelease"
if /i "%1"=="debug" set "TASK=assembleDebug"
if /i "%1"=="release" set "TASK=assembleRelease"

if exist "..\dat\preload" (
    echo [预置包] 检测到 ..\dat\preload，产物将内置离线资源
) else (
    echo [预置包] 未找到 ..\dat\preload，产物不含离线资源，初始化时联网下载
)

echo [构建] 开始 %TASK% ...
call "%GRADLE_CMD%" :app:%TASK% --console=plain
if errorlevel 1 (
    echo [构建] 失败
    pause
    exit /b 1
)

set "APK=app\build\outputs\apk\release\app-release.apk"
if /i "%TASK%"=="assembleDebug" set "APK=app\build\outputs\apk\debug\app-debug.apk"
echo [构建] 成功
if exist "%APK%" for %%F in ("%APK%") do echo [产物] %APK%  %%~zF 字节

pause
