param(
    [string]$SingeDataRef = "master",
    [string]$SingeDataRepo = "https://github.com/DirtBagXon/hypseus_singe_data.git",
    [string]$RuntimeRoot = "../app/src/dle/assets/runtime/templates/dle",
    [string]$ManifestPath = "../upstream/dle-sync-lock.json",
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

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hypseus-dle-sync-" + [guid]::NewGuid().ToString("N"))
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

    $dleRootSrc = Join-Path $cloneDir "00-singe2\DLe"
    if (-not (Test-Path -LiteralPath $dleRootSrc)) {
        throw "Directory 00-singe2/DLe not found."
    }

    Write-Host "[sync] Copying DLe template tree from: $dleRootSrc"
    if (Test-Path $runtimeRootAbs) { Remove-Item -Recurse -Force $runtimeRootAbs }
    New-Item -ItemType Directory -Path $runtimeRootAbs -Force | Out-Null
    Copy-Item -Path (Join-Path $dleRootSrc "*") -Destination $runtimeRootAbs -Recurse -Force

    $structureDest = Join-Path $runtimeRootAbs "Structure"
    Write-Host "[sync] Refreshing Structure from framework: $frameworkSrc"
    if (Test-Path $structureDest) { Remove-Item -Recurse -Force $structureDest }
    New-Item -ItemType Directory -Path $structureDest -Force | Out-Null
    Copy-Item -Path (Join-Path $frameworkSrc "*") -Destination $structureDest -Recurse -Force

    $files = Get-ChildItem -Path $runtimeRootAbs -Recurse -File
    $fileInfos = $files | ForEach-Object { Get-FileHashInfo -Root $runtimeRootAbs -Path $_.FullName }

    $manifest = [PSCustomObject]@{
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        source = [PSCustomObject]@{
            repo = $SingeDataRepo
            ref = $SingeDataRef
            commit = $commit
            game = "DLe"
            framework = [System.IO.Path]::GetFileName($frameworkSrc)
        }
        outputs = [PSCustomObject]@{
            runtimeRoot = (Get-RelativePathPortable -BasePath (Split-Path -Parent $scriptDir) -TargetPath $runtimeRootAbs).Replace('\\', '/')
            frameworkPath = "Structure"
            dleScriptTemplate = "DLe.singe"
            dleFramefileTemplate = "DLe.txt"
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
