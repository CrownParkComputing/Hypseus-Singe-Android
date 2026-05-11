param(
    [string]$NdkVersion = "26.1.10909125",
    [string]$Abi = "arm64-v8a",
    [string]$Api = "29"
)

$ErrorActionPreference = "Stop"

$sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$ndkRoot = Join-Path $sdkRoot "ndk\$NdkVersion"
$toolchain = Join-Path $ndkRoot "build\cmake\android.toolchain.cmake"
$ninja = Join-Path $sdkRoot "cmake\3.30.3\bin\ninja.exe"

if (-not (Test-Path $toolchain)) {
    throw "NDK toolchain not found: $toolchain"
}
if (-not (Test-Path $ninja)) {
    throw "Ninja not found: $ninja"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$srcDir = Join-Path $repoRoot "src"
$buildDir = Join-Path $repoRoot "build-android-$Abi"

cmake -S "$srcDir" -B "$buildDir" -G Ninja `
    -DCMAKE_MAKE_PROGRAM="$ninja" `
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" `
    -DANDROID_ABI="$Abi" `
    -DANDROID_PLATFORM="$Api"

Write-Host "Configured: $buildDir"
