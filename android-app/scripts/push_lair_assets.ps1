param(
    [string]$Package   = "org.hypseus.singe",
    [string]$AssetRoot = "D:\hypseus_singe_DLe\DLe"
)

$ErrorActionPreference = "Stop"

# ── Validate source files ──────────────────────────────────────────────────
$framefile = Join-Path $AssetRoot "DLe.txt"
$romZip    = Join-Path $AssetRoot "DLe.zip"
$videoFile = Join-Path $AssetRoot "Video\dle.m2v"
$audioFile = Join-Path $AssetRoot "Video\dle.ogg"

foreach ($f in $framefile, $romZip, $videoFile, $audioFile) {
    if (-not (Test-Path $f)) { throw "Missing asset: $f" }
}

# ── Check adb is reachable ─────────────────────────────────────────────────
$devices = adb devices 2>&1 | Select-String "device$"
if (-not $devices) { throw "No Android device found. Connect device and enable USB debugging." }

$remoteBase = "/sdcard/Android/data/$Package/files"

Write-Host "Creating remote directories..."
adb shell "mkdir -p $remoteBase/vldp/lair/Video"
adb shell "mkdir -p $remoteBase/roms"

# Framefile
Write-Host "Pushing framefile..."
adb push $framefile "$remoteBase/vldp/lair/lair.txt"

# ROMs
Write-Host "Pushing ROMs (~2 MB)..."
adb push $romZip "$remoteBase/roms/lair.zip"

# Video — framefile expects 'lair.m2v' but local file is 'dle.m2v'
Write-Host "Pushing video (~1.1 GB, this will take a while)..."
adb push $videoFile "$remoteBase/vldp/lair/Video/lair.m2v"

# Audio — renamed to match game name convention
Write-Host "Pushing audio (~15 MB)..."
adb push $audioFile "$remoteBase/vldp/lair/Video/lair.ogg"

Write-Host ""
Write-Host "Done. Assets pushed to $remoteBase"
Write-Host ""
Write-Host "Remote layout:"
adb shell "ls -lh $remoteBase/vldp/lair/ ; ls -lh $remoteBase/vldp/lair/Video/ ; ls -lh $remoteBase/roms/"
