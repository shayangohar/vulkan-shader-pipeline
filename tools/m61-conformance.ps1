param(
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryPath,
    [Parameter(Mandatory = $true)]
    [string]$IndependentPath,
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryVersion,
    [Parameter(Mandatory = $true)]
    [string]$IndependentVersion,
    [string]$PackRoot = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\instances\CHIMERA\minecraft\shaderpacks',
    [string]$BaselinePath = '',
    [string]$JavaHome = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\java\java-runtime-delta'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-PackPath([string]$value) {
    $candidate = if ([IO.Path]::IsPathRooted($value)) {
        $value
    } else {
        Join-Path $PackRoot $value
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Any)) {
        throw "M6.1 pack path does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

$complementary = Resolve-PackPath $ComplementaryPath
$independent = Resolve-PackPath $IndependentPath
if ([string]::IsNullOrWhiteSpace($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot 'testpacks\baselines\m6_1.json'
} elseif (-not [IO.Path]::IsPathRooted($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot $BaselinePath
}
$BaselinePath = (Resolve-Path -LiteralPath $BaselinePath).Path

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
    throw "Prism Java 21 was not found under $JavaHome"
}
$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path

$gradle = Join-Path $repoRoot 'gradlew.bat'
$arguments = @(
    'm61ConformanceTest',
    "-Pm61Complementary=$complementary",
    "-Pm61Independent=$independent",
    "-Pm61ComplementaryVersion=$ComplementaryVersion",
    "-Pm61IndependentVersion=$IndependentVersion",
    "-Pm61Baseline=$BaselinePath"
)
& $gradle @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
