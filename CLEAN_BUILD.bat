@echo off
setlocal enabledelayedexpansion

 echo ============================================
 echo Dr Indexer Search v4.0 - Clean Build
 echo ============================================
 echo Requires JDK 17 + Android SDK Platform 34.
 echo.

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set to JDK 17.
    pause
    exit /b 1
)
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: Android SDK not found.
        pause
        exit /b 1
    )
)

echo JAVA_HOME: %JAVA_HOME%
echo ANDROID_HOME: !ANDROID_HOME!
echo sdk.dir=!ANDROID_HOME:\=\\!> local.properties

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle-wrapper.jar is missing. This script does not download executable build tooling from third-party mirrors.
    pause
    exit /b 1
)

if exist "output" rd /s /q "output"

 echo.
 echo Cleaning, testing, and rebuilding...
call gradlew.bat clean testDebugUnitTest assembleDebug assembleRelease --no-daemon --no-build-cache
if errorlevel 1 (
    echo.
    echo CLEAN BUILD OR TEST FAILED. Review Gradle output above.
    pause
    exit /b 1
)

if not exist "output" mkdir output
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "output\DrIndexerSearch_v4_0-debug.apk" >nul 2>&1
copy /Y "app\build\outputs\apk\release\app-release-unsigned.apk" "output\DrIndexerSearch_v4_0-release-unsigned.apk" >nul 2>&1

 echo.
 echo CLEAN BUILD + TESTS SUCCESSFUL
 echo Debug APK: output\DrIndexerSearch_v4_0-debug.apk
 echo Unsigned release APK: output\DrIndexerSearch_v4_0-release-unsigned.apk
 echo.
pause
