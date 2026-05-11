param(
    [ValidateSet("Debug", "Release")]
    [string]$BuildType = "Debug",
    [string]$DeviceId = "",
    [switch]$InstallToDevice
)

$ErrorActionPreference = "Stop"

$androidRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $androidRoot

try {
    if (-not $env:JAVA_HOME) {
        $studioJbr = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path $studioJbr) {
            $env:JAVA_HOME = $studioJbr
        }
    }

    if (-not $env:JAVA_HOME) {
        throw "JAVA_HOME is not set. Set JAVA_HOME to your JDK (for example Android Studio jbr)."
    }

    $flavors = @(
        @{ Name = "hypseus"; TaskFlavor = "Hypseus"; AppId = "org.hypseus.singe" },
        @{ Name = "spaceace"; TaskFlavor = "Spaceace"; AppId = "org.hypseus.singe.spaceace" },
        @{ Name = "dle"; TaskFlavor = "Dle"; AppId = "org.hypseus.singe.dle" },
        @{ Name = "dl2e"; TaskFlavor = "Dl2e"; AppId = "org.hypseus.singe.dl2e" }
    )

    $gradleTargets = @()
    foreach ($flavor in $flavors) {
        $gradleTargets += "assemble$($flavor.TaskFlavor)$BuildType"
    }

    Write-Host "Building all APK flavors ($BuildType)..."
    .\gradlew --no-daemon @gradleTargets

    if (-not $InstallToDevice) {
        Write-Host "Build complete. APKs are in app/build/outputs/apk/<flavor>/$($BuildType.ToLower())/."
        return
    }

    $adbArgsPrefix = @()
    if ($DeviceId) {
        $adbArgsPrefix = @("-s", $DeviceId)
    }

    foreach ($flavor in $flavors) {
        $apkPattern = "app/build/outputs/apk/$($flavor.Name)/$($BuildType.ToLower())/*.apk"
        $apk = Get-ChildItem -Path $apkPattern | Select-Object -First 1
        if (-not $apk) {
            throw "APK not found for flavor '$($flavor.Name)' using pattern: $apkPattern"
        }

        Write-Host "Installing $($apk.Name) -> $($flavor.AppId)"
        & adb @adbArgsPrefix install -r $apk.FullName | Out-Host
    }

    Write-Host "All flavor APKs built and installed."
}
finally {
    Pop-Location
}
