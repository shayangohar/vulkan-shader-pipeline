param(
    [string]$FixtureRoot = "testpacks",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    if ($JavaHome -ne "") {
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    }
    & .\gradlew.bat m75ConformanceTest "-Dchimera.fixtureRoot=$FixtureRoot" --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "M7.5 conformance failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
