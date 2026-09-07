[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryPath,
    [Parameter(Mandatory = $true)]
    [string]$BslPath,
    [Parameter(Mandatory = $true)]
    [string]$ComplementaryVersion,
    [Parameter(Mandatory = $true)]
    [string]$BslVersion,
    [string]$ComplementaryLogPath = 'logs\m7_7_complementary_clean_latest.log',
    [string]$BslLogPath = 'logs\m7_7_bsl_world_latest.log',
    [string]$ComplementaryCapturePath = 'captures\m7_7_complementary_clean.rdc',
    [string]$BslCapturePath = 'captures\m7_7_bsl_world.rdc',
    [string]$IrisComplementaryCapturePath = '',
    [string]$IrisBslCapturePath = '',
    [string]$IrisComplementaryLogPath = '',
    [string]$IrisBslLogPath = '',
    [string]$PackRoot = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\instances\CHIMERA\minecraft\shaderpacks',
    [string]$BaselinePath = '',
    [string]$JavaHome = 'C:\Users\shaya\AppData\Roaming\PrismLauncher\java\java-runtime-delta',
    [switch]$RequireReference,
    [switch]$EmitBaseline
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-PackPath([string]$value) {
    $candidate = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $PackRoot $value }
    if (-not (Test-Path -LiteralPath $candidate -PathType Any)) {
        throw "M7.8 pack path does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Resolve-EvidencePath([string]$value, [bool]$required) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        if ($required) { throw 'M7.8 required evidence path is empty.' }
        return $null
    }
    $candidate = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $repoRoot $value }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        if ($required) { throw "M7.8 evidence path does not exist: $candidate" }
        return $null
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

$complementary = Resolve-PackPath $ComplementaryPath
$bsl = Resolve-PackPath $BslPath
$complementaryLog = Resolve-EvidencePath $ComplementaryLogPath $true
$bslLog = Resolve-EvidencePath $BslLogPath $true
$complementaryCapture = Resolve-EvidencePath $ComplementaryCapturePath $true
$bslCapture = Resolve-EvidencePath $BslCapturePath $true
$irisComplementaryCapture = Resolve-EvidencePath $IrisComplementaryCapturePath $false
$irisBslCapture = Resolve-EvidencePath $IrisBslCapturePath $false
$irisComplementaryLog = Resolve-EvidencePath $IrisComplementaryLogPath $false
$irisBslLog = Resolve-EvidencePath $IrisBslLogPath $false

if ([string]::IsNullOrWhiteSpace($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot 'testpacks\baselines\m7_8.json'
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
    'm78ConformanceTest',
    "-Pm78Complementary=$complementary",
    "-Pm78Bsl=$bsl",
    "-Pm78ComplementaryVersion=$ComplementaryVersion",
    "-Pm78BslVersion=$BslVersion",
    "-Pm78ComplementaryLog=$complementaryLog",
    "-Pm78BslLog=$bslLog",
    "-Pm78ComplementaryCapture=$complementaryCapture",
    "-Pm78BslCapture=$bslCapture",
    "-Pm78Baseline=$BaselinePath"
)
if ($irisComplementaryCapture) { $arguments += "-Pm78IrisComplementaryCapture=$irisComplementaryCapture" }
if ($irisBslCapture) { $arguments += "-Pm78IrisBslCapture=$irisBslCapture" }
if ($irisComplementaryLog) { $arguments += "-Pm78IrisComplementaryLog=$irisComplementaryLog" }
if ($irisBslLog) { $arguments += "-Pm78IrisBslLog=$irisBslLog" }
if ($RequireReference) { $arguments += '-Pm78RequireReference=true' }
if ($EmitBaseline) { $arguments += '-Pm78EmitBaseline=true' }
& $gradle @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
