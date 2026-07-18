$ErrorActionPreference = "Stop"
$env:GRADLE_USER_HOME = "D:\gradle-home"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$adb = "C:\AndroidSDK\platform-tools\adb.exe"
Set-Location "D:\UnityProjects\AynThorTasks"

Write-Host "==> assembleDebug" -ForegroundColor Cyan
& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Get-ChildItem "app\build\outputs\apk\debug\*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { Write-Error "APK not found"; exit 1 }
Write-Host ("APK=" + $apk.FullName + " size=" + $apk.Length + " time=" + $apk.LastWriteTime)

Write-Host "==> force-stop + install -r" -ForegroundColor Cyan
& $adb shell am force-stop com.aynthor.taskswap
& $adb install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Disable Odin "press Home again to return to launcher" (blocks first Home)
Write-Host "==> disable prevent_press_home_accidentally" -ForegroundColor Cyan
& $adb shell settings put system prevent_press_home_accidentally 0
Write-Host ("prevent_press_home_accidentally=" + (& $adb shell settings get system prevent_press_home_accidentally).Trim())

# Put our accessibility service FIRST
Write-Host "==> a11y order: TaskSwap first" -ForegroundColor Cyan
$svc = "com.aynthor.taskswap/com.aynthor.taskswap.TaskSwapService"
$odin = "com.odin.gameassistant/com.ro.gameassistant.service.ForegroundAppMonitorV4Service"
& $adb shell settings put secure enabled_accessibility_services "$svc`:$odin"
& $adb shell settings put secure accessibility_enabled 1
Write-Host ("a11y: " + (& $adb shell settings get secure enabled_accessibility_services).Trim())

Write-Host "==> launch MainActivity" -ForegroundColor Cyan
& $adb shell am start -n com.aynthor.taskswap/.MainActivity
Write-Host "DONE - press Back/Home/AYN and check last key line in the app" -ForegroundColor Green
