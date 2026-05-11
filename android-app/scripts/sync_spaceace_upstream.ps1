param(
    [string]$SingeDataRef = "master",
    [string]$SingeDataRepo = "https://github.com/DirtBagXon/hypseus_singe_data.git",
    [string]$RuntimeRoot = "../app/src/main/assets/runtime",
    [string]$ManifestPath = "../upstream/spaceace-sync-lock.json",
    [switch]$KeepTemp
)

$ErrorActionPreference = "Stop"

function Get-FileHashInfo {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $Path
    $item = Get-Item -LiteralPath $Path
    $relative = [System.IO.Path]::GetRelativePath($Root, $Path).Replace('\\', '/')

    return [PSCustomObject]@{
        path = $relative
        sha256 = $hash.Hash.ToLowerInvariant()
        size = [int64]$item.Length
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeRootAbs = [System.IO.Path]::GetFullPath((Join-Path $scriptDir $RuntimeRoot))
$manifestAbs = [System.IO.Path]::GetFullPath((Join-Path $scriptDir $ManifestPath))
$manifestDir = Split-Path -Parent $manifestAbs

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hypseus-spaceace-sync-" + [guid]::NewGuid().ToString("N"))
$cloneDir = Join-Path $tempRoot "hypseus_singe_data"

Write-Host "[sync] Runtime root: $runtimeRootAbs"
Write-Host "[sync] Manifest path: $manifestAbs"
Write-Host "[sync] Cloning: $SingeDataRepo @ $SingeDataRef"

New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    git clone $SingeDataRepo $cloneDir | Out-Host
    git -C $cloneDir checkout $SingeDataRef | Out-Host

    $commit = (git -C $cloneDir rev-parse HEAD).Trim()

    $singe2Root = Join-Path $cloneDir "00-singe2"
    $frameworkSource = Join-Path $singe2Root "Framework"
    $frameworkKimmySource = Join-Path $singe2Root "FrameworkKimmy"
    $saeFolder = Join-Path $singe2Root "SAe"

    if (-not (Test-Path -LiteralPath $frameworkSource) -and -not (Test-Path -LiteralPath $frameworkKimmySource)) {
        throw "Neither Framework nor FrameworkKimmy exists in 00-singe2."
    }
    if (-not (Test-Path -LiteralPath $saeFolder)) {
        throw "SAe folder does not exist in 00-singe2."
    }

    $frameworkToUse = if (Test-Path -LiteralPath $frameworkSource) { $frameworkSource } else { $frameworkKimmySource }

    $saeScript = @(
        (Join-Path $saeFolder "SAe.singe"),
        (Join-Path $saeFolder "sae.singe")
    ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

    if (-not $saeScript) {
        throw "No SAe.singe/sae.singe found in 00-singe2/SAe."
    }

    $destFramework = Join-Path $runtimeRootAbs "singe/Framework"
    $destSpaceAceScript = Join-Path $runtimeRootAbs "templates/spaceace/SAe.singe"

    New-Item -ItemType Directory -Path $runtimeRootAbs -Force | Out-Null

    if (Test-Path -LiteralPath $destFramework) {
        Remove-Item -LiteralPath $destFramework -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destFramework -Force | Out-Null

    Write-Host "[sync] Copying framework from: $frameworkToUse"
    Copy-Item -LiteralPath (Join-Path $frameworkToUse "*") -Destination $destFramework -Recurse -Force

    $destSpaceAceScriptDir = Split-Path -Parent $destSpaceAceScript
    New-Item -ItemType Directory -Path $destSpaceAceScriptDir -Force | Out-Null
    Copy-Item -LiteralPath $saeScript -Destination $destSpaceAceScript -Force

    $trackedFiles = @()
    $frameworkFiles = Get-ChildItem -LiteralPath $destFramework -Recurse -File
    foreach ($f in $frameworkFiles) {
        $trackedFiles += Get-FileHashInfo -Root $runtimeRootAbs -Path $f.FullName
    }
    $trackedFiles += Get-FileHashInfo -Root $runtimeRootAbs -Path $destSpaceAceScript
    $trackedFiles = $trackedFiles | Sort-Object path

    New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null

    $manifest = [PSCustomObject]@{
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        source = [PSCustomObject]@{
            repo = $SingeDataRepo
            ref = $SingeDataRef
            commit = $commit
            game = "SAe"
            framework = [System.IO.Path]::GetFileName($frameworkToUse)
        }
        outputs = [PSCustomObject]@{
            runtimeRoot = [System.IO.Path]::GetRelativePath((Split-Path -Parent $scriptDir), $runtimeRootAbs).Replace('\\', '/')
            frameworkPath = "singe/Framework"
            spaceAceScriptTemplate = "templates/spaceace/SAe.singe"
        }
        files = $trackedFiles
    }

    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestAbs -Encoding utf8

    Write-Host "[sync] Completed successfully."
    Write-Host "[sync] Upstream commit: $commit"
    Write-Host "[sync] Files synced: $($trackedFiles.Count)"
    Write-Host "[sync] Manifest: $manifestAbs"
}
finally {
    if (-not $KeepTemp -and (Test-Path -LiteralPath $tempRoot)) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
