param(
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryPath,
    [Parameter(Mandatory = $true)]
    [string]$BslPath,
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryVersion,
    [Parameter(Mandatory = $true)]
    [string]$BslVersion,
    [string]$ComplementaryLogPath = 'logs\m6_5_chimera_complementary_log.log',
    [string]$BslLogPath = 'logs\m6_5_chimera_bsl_log.log',
    [string]$ComplementaryCapturePath = 'captures\m6_5_chimera_complementary.rdc',
    [string]$BslCapturePath = 'captures\m6_5_chimera_bsl.rdc',
    [string]$PackRoot = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\instances\CHIMERA\minecraft\shaderpacks',
    [string]$BaselinePath = '',
    [string]$JavaHome = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\java\java-runtime-delta',
    [switch]$EmitBaseline
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-PackPath([string]$value) {
    $candidate = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $PackRoot $value }
    if (-not (Test-Path -LiteralPath $candidate -PathType Any)) {
        throw "M7.0 pack path does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Resolve-EvidencePath([string]$value) {
    $candidate = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $repoRoot $value }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "M7.0 evidence path does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

$complementary = Resolve-PackPath $ComplementaryPath
$bsl = Resolve-PackPath $BslPath
$complementaryLog = Resolve-EvidencePath $ComplementaryLogPath
$bslLog = Resolve-EvidencePath $BslLogPath
$complementaryCapture = Resolve-EvidencePath $ComplementaryCapturePath
$bslCapture = Resolve-EvidencePath $BslCapturePath

if ([string]::IsNullOrWhiteSpace($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot 'testpacks\baselines\m7_0.json'
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
    'm70ConformanceTest',
    "-Pm70Complementary=$complementary",
    "-Pm70Bsl=$bsl",
    "-Pm70ComplementaryVersion=$ComplementaryVersion",
    "-Pm70BslVersion=$BslVersion",
    "-Pm70ComplementaryLog=$complementaryLog",
    "-Pm70BslLog=$bslLog",
    "-Pm70ComplementaryCapture=$complementaryCapture",
    "-Pm70BslCapture=$bslCapture",
    "-Pm70Baseline=$BaselinePath"
)
if ($EmitBaseline) {
    $arguments += '-Pm70EmitBaseline=true'
}
& $gradle @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
