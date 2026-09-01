param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments
)

$javaHome = if ([string]::IsNullOrWhiteSpace($env:CHIMERA_PRISM_JAVA_HOME)) {
    'C:\Users\shaya\AppData\Roaming\PrismLauncher\java\java-runtime-delta'
} else {
    $env:CHIMERA_PRISM_JAVA_HOME
}
$javaPath = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
    throw "Prism Java 21 was not found at $javaPath"
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
$wrapper = Join-Path $PSScriptRoot '..\gradlew.bat'
& $wrapper @GradleArguments
exit $LASTEXITCODE
