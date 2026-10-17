<#
Runs Gradle with the 'Chimera NeoForge 1.21.1' instance's locked shader packs as test inputs.
The frozen Fabric 1.21.11 line (CHIMERA instance) runs from the fabric/1.21.11 branch.

  .\tools\check-packs.ps1                          the full gate: check build
  .\tools\check-packs.ps1 packTest                 one task
  .\tools\check-packs.ps1 evidenceTest --tests '*ParityBaselineEvidenceTest' `
      -ComplementaryVersion r5.9.3 -BslVersion v10.1.8 `
      -Evidence @{ complementaryLog = 'logs\c.log'; complementaryCapture = 'captures\c.rdc' }

Every value becomes a -Pchimera.* property, which build.gradle passes to the tests.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$PackRoot = (Join-Path $env:APPDATA 'PrismLauncher\instances\Chimera NeoForge 1.21.1\minecraft\shaderpacks'),
    [string]$ComplementaryVersion = '',
    [string]$BslVersion = '',
    [hashtable]$Evidence = @{},
    [switch]$PrintBaselines,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments
)

$ErrorActionPreference = 'Stop'

function Find-Pack([string]$filter) {
    $packs = @(Get-ChildItem -LiteralPath $PackRoot -Filter $filter)
    if ($packs.Count -ne 1) {
        throw "Expected one $filter in $PackRoot, found $($packs.Count)"
    }
    return $packs[0].FullName
}

$extra = @(Get-ChildItem -LiteralPath $PackRoot -Filter '*.zip' |
        Where-Object { $_.Name -match 'Bliss|MakeUp|photon|Unbound|Solas' }).FullName -join ';'
$properties = @(
    "-Pchimera.pack.complementary=$(Find-Pack 'ComplementaryReimagined*.zip')",
    "-Pchimera.pack.bsl=$(Find-Pack 'BSL*.zip')",
    "-Pchimera.pack.extra=$extra",
    '-Pchimera.gpu=true'
)
if ($ComplementaryVersion) { $properties += "-Pchimera.pack.complementaryVersion=$ComplementaryVersion" }
if ($BslVersion) { $properties += "-Pchimera.pack.bslVersion=$BslVersion" }
foreach ($name in $Evidence.Keys) {
    $properties += "-Pchimera.evidence.$name=$((Resolve-Path -LiteralPath $Evidence[$name]).Path)"
}
if ($PrintBaselines) { $properties += '-Pchimera.printBaselines=true' }
if (-not $GradleArguments) { $GradleArguments = @('check', 'build') }

& (Join-Path $PSScriptRoot 'gradlew-prism.ps1') @GradleArguments @properties --continue
exit $LASTEXITCODE
