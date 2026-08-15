@echo off
setlocal enabledelayedexpansion

 echo ============================================
 echo Dr Indexer Search v4.0 - Build + Tests
 echo ============================================
 echo.

REM AGP 8.4 / Gradle 8.6 require JDK 17.
if not defined JAVA_HOME (
    where java >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Java not found. Install JDK 17 and set JAVA_HOME.
        pause
        exit /b 1
    )
) else (
    echo JAVA_HOME: %JAVA_HOME%
)

if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: Android SDK not found. Install Android SDK Platform 34.
        pause
        exit /b 1
    )
)
echo ANDROID_HOME: !ANDROID_HOME!

echo sdk.dir=!ANDROID_HOME:\=\\!> local.properties

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle-wrapper.jar is missing from this source package.
    echo Restore the wrapper from the official project package or open the project in Android Studio.
    pause
    exit /b 1
)

 echo.
 echo Running unit tests and building Debug + unsigned Release APKs...
 echo.
call gradlew.bat testDebugUnitTest assembleDebug assembleRelease --no-daemon
if errorlevel 1 (
    echo.
    echo ============================================
    echo BUILD OR TEST FAILED
    echo ============================================
    echo Review the Gradle output above. JDK 17 and Android SDK Platform 34 are required.
    pause
    exit /b 1
)

if not exist "output" mkdir output
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "output\DrIndexerSearch_v4_0-debug.apk" >nul 2>&1
copy /Y "app\build\outputs\apk\release\app-release-unsigned.apk" "output\DrIndexerSearch_v4_0-release-unsigned.apk" >nul 2>&1

 echo.
 echo ============================================
 echo BUILD + TESTS SUCCESSFUL
 echo ============================================
if exist "output\DrIndexerSearch_v4_0-debug.apk" echo Debug APK: output\DrIndexerSearch_v4_0-debug.apk
if exist "output\DrIndexerSearch_v4_0-release-unsigned.apk" echo Unsigned release APK: output\DrIndexerSearch_v4_0-release-unsigned.apk
 echo.
pause
