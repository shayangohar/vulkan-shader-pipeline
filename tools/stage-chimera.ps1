param(
    [string]$BuildDirectory = ''
)

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($BuildDirectory)) {
    $BuildDirectory = Join-Path $repoRoot 'build\libs'
} else {
    $BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
}

$jarName = 'chimera-0.1.0+mvp.jar'
$jarPath = Join-Path $BuildDirectory $jarName
$hashPath = Join-Path $BuildDirectory 'LATEST-SHA256.txt'
$modsPath = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\instances\CHIMERA\minecraft\mods'

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Required build artifact is missing: $jarPath"
}
if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) {
    throw "CHIMERA mods directory is missing: $modsPath"
}

$artifactHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $hashPath -Value "$artifactHash  $jarName" -Encoding ascii

Get-ChildItem -LiteralPath $modsPath -File |
    Where-Object { $_.Name -like 'chimera-*.jar' -and $_.Name -ne $jarName } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

$installedPath = Join-Path $modsPath $jarName
Copy-Item -LiteralPath $jarPath -Destination $installedPath -Force
$installedHash = (Get-FileHash -LiteralPath $installedPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installedHash -ne $artifactHash) {
    throw "Installed Chimera hash does not match the build artifact: $installedHash"
}

Write-Output "staged $jarName"
Write-Output "sha256 $artifactHash"
