param(
    [string]$SingeDataRef = "master",
    [string]$SingeDataRepo = "https://github.com/DirtBagXon/hypseus_singe_data.git",
    [string]$RuntimeRoot = "../app/src/main/assets/runtime",
    [string]$ManifestPath = "../upstream/dl2e-sync-lock.json",
    [switch]$KeepTemp
)

$ErrorActionPreference = "Stop"

function Get-RelativePathPortable {
    param(
        [Parameter(Mandatory = $true)][string]$BasePath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )

    $baseFull = [System.IO.Path]::GetFullPath($BasePath)
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)

    if ($PSVersionTable.PSVersion.Major -ge 6) {
        return [System.IO.Path]::GetRelativePath($baseFull, $targetFull)
    }

    $trimmedBase = $baseFull.TrimEnd('\').TrimEnd('/')
    $baseUri = New-Object System.Uri(($trimmedBase + [System.IO.Path]::DirectorySeparatorChar))
    $targetUri = New-Object System.Uri($targetFull)
    $relativeUri = $baseUri.MakeRelativeUri($targetUri)
    return [System.Uri]::UnescapeDataString($relativeUri.ToString()).Replace('/', [System.IO.Path]::DirectorySeparatorChar)
}

function Get-FileHashInfo {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $Path
    $item = Get-Item -LiteralPath $Path
    $relative = (Get-RelativePathPortable -BasePath $Root -TargetPath $Path).Replace('\', '/')

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

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hypseus-dl2e-sync-" + [guid]::NewGuid().ToString("N"))
$cloneDir = Join-Path $tempRoot "hypseus_singe_data"

Write-Host "[sync] Runtime root: $runtimeRootAbs"
Write-Host "[sync] Manifest path: $manifestAbs"
Write-Host "[sync] Cloning: $SingeDataRepo @ $SingeDataRef"

New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    git clone $SingeDataRepo $cloneDir --quiet
    git -C $cloneDir checkout $SingeDataRef --quiet

    $commit = (git -C $cloneDir rev-parse HEAD).Trim()

    $frameworkSrc = Join-Path $cloneDir "00-singe2\Framework"
    if (-not (Test-Path -LiteralPath $frameworkSrc)) {
        $frameworkSrc = Join-Path $cloneDir "00-singe2\FrameworkKimmy"
    }
    if (-not (Test-Path -LiteralPath $frameworkSrc)) {
        throw "No Framework or FrameworkKimmy directory found in 00-singe2."
    }

    $frameworkDest = Join-Path $runtimeRootAbs "singe\Framework"

    Write-Host "[sync] Copying framework from: $frameworkSrc"
    if (Test-Path $frameworkDest) { Remove-Item -Recurse -Force $frameworkDest }
    New-Item -ItemType Directory -Path (Split-Path -Parent $frameworkDest) -Force | Out-Null
    Copy-Item -Recurse -Force $frameworkSrc $frameworkDest

    $files = Get-ChildItem -Path $frameworkDest -Recurse -File
    $fileInfos = $files | ForEach-Object { Get-FileHashInfo -Root $runtimeRootAbs -Path $_.FullName }

    $dl2eSrc = @(
        (Join-Path $cloneDir "00-singe2\DL2e\DL2e.singe"),
        (Join-Path $cloneDir "00-singe2\DL2e\dl2e.singe")
    ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $dl2eSrc) {
        throw "No DL2e.singe/dl2e.singe found in 00-singe2/DL2e."
    }

    $dl2eDest = Join-Path $runtimeRootAbs "templates\dl2e\DL2e.singe"

    Write-Host "[sync] Copying DL2e.singe from: $dl2eSrc"
    New-Item -ItemType Directory -Path (Split-Path -Parent $dl2eDest) -Force | Out-Null
    Copy-Item -Force $dl2eSrc $dl2eDest

    $dl2eInfo = Get-FileHashInfo -Root $runtimeRootAbs -Path $dl2eDest
    $fileInfos += $dl2eInfo

    $manifest = [PSCustomObject]@{
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        source = [PSCustomObject]@{
            repo = $SingeDataRepo
            ref = $SingeDataRef
            commit = $commit
            game = "DL2e"
            framework = [System.IO.Path]::GetFileName($frameworkSrc)
        }
        outputs = [PSCustomObject]@{
            runtimeRoot = (Get-RelativePathPortable -BasePath (Split-Path -Parent $scriptDir) -TargetPath $runtimeRootAbs).Replace('\\', '/')
            frameworkPath = "singe/Framework"
            dl2eScriptTemplate = "templates/dl2e/DL2e.singe"
        }
        files = $fileInfos | Sort-Object path
    }

    New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $manifestAbs
    Write-Host "[sync] Manifest written to: $manifestAbs"
}
finally {
    if (-not $KeepTemp -and (Test-Path $tempRoot)) {
        Remove-Item -Recurse -Force $tempRoot
    }
}
