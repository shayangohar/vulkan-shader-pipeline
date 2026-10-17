param(
    [string]$BuildDirectory = '',
    [string]$Instance = 'Chimera NeoForge 1.21.1',
    [switch]$DryRun
)

# Stages the newest Chimera build into a Prism instance's mods folder. The NeoForge 1.21.1
# instance is the port's target; the frozen Fabric 1.21.11 line (CHIMERA) stages from the
# fabric/1.21.11 branch's copy of this script.

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($BuildDirectory)) {
    # The mod subproject's output once the build is split; the root project's before.
    $BuildDirectory = @('neoforge\build\libs', 'build\libs') |
        ForEach-Object { Join-Path $repoRoot $_ } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Container } |
        Select-Object -First 1
    if (-not $BuildDirectory) {
        throw 'No build output: neither neoforge\build\libs nor build\libs exists'
    }
} else {
    $BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
}

$instancePath = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance"
$modsPath = Join-Path $instancePath 'minecraft\mods'
if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) {
    throw "Instance mods directory is missing: $modsPath"
}

$jar = Get-ChildItem -LiteralPath $BuildDirectory -Filter 'chimera-*.jar' -File |
    Where-Object { $_.BaseName -notmatch '-(sources|dev|javadoc)$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Required build artifact is missing: no chimera-*.jar in $BuildDirectory"
}

# A Fabric jar in a NeoForge instance is silently not loaded; refuse it instead.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $isNeoForge = $null -ne ($archive.Entries |
        Where-Object { $_.FullName.Replace('\', '/') -eq 'META-INF/neoforge.mods.toml' })
} finally {
    $archive.Dispose()
}
if (-not $isNeoForge) {
    throw "$($jar.Name) has no META-INF/neoforge.mods.toml; it is not a NeoForge build"
}

# Prism starts every game through org.prismlauncher.EntryPoint, with the instance's natives
# folder on java.library.path; Gradle daemons match neither.
$instanceMarker = ('instances/' + $Instance + '/').ToLowerInvariant()
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object {
        $_.CommandLine -match 'org\.prismlauncher\.EntryPoint' -and
        $_.CommandLine.Replace('\', '/').ToLowerInvariant().Contains($instanceMarker)
    }
if ($running) {
    throw "The '$Instance' game is running (pid $($running.ProcessId -join ', ')); close it before staging"
}

$artifactHash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($DryRun) {
    Write-Output "would stage $($jar.Name) into $modsPath"
    Write-Output "sha256 $artifactHash"
    return
}
Set-Content -LiteralPath (Join-Path $BuildDirectory 'LATEST-SHA256.txt') -Value "$artifactHash  $($jar.Name)" -Encoding ascii

Get-ChildItem -LiteralPath $modsPath -File |
    Where-Object { $_.Name -like 'chimera-*.jar' -and $_.Name -ne $jar.Name } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

$installedPath = Join-Path $modsPath $jar.Name
Copy-Item -LiteralPath $jar.FullName -Destination $installedPath -Force
$installedHash = (Get-FileHash -LiteralPath $installedPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installedHash -ne $artifactHash) {
    throw "Installed Chimera hash does not match the build artifact: $installedHash"
}

Write-Output "staged $($jar.Name) into $Instance"
Write-Output "sha256 $artifactHash"
