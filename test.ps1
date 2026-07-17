# Запуск unit-тестов на Windows (без устройства).
# Использование: .\test.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        return $env:JAVA_HOME
    }
    $candidates = @(
        "${env:ProgramFiles}\Android\Android Studio\jbr",
        "${env:ProgramFiles}\Android\Android Studio1\jbr",
        "${env:LOCALAPPDATA}\Programs\Android Studio\jbr",
        "${env:ProgramFiles}\Java\jdk-17",
        "${env:ProgramFiles}\Eclipse Adoptium\jdk-17*"
    )
    foreach ($c in $candidates) {
        $resolved = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path "$($resolved.FullName)\bin\java.exe")) {
            return $resolved.FullName
        }
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        # ...\bin\java.exe → parent of bin
        return (Split-Path (Split-Path $java.Source))
    }
    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    Write-Error "JDK не найден. Установи Android Studio (JBR) или задай JAVA_HOME."
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Write-Host "JAVA_HOME=$env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "==> .\gradlew.bat test" -ForegroundColor Cyan

& .\gradlew.bat test
exit $LASTEXITCODE
