<#
Runs the Gradle wrapper on Prism's Java 21.

By default a governor runs beside the build (gradle-governor.ps1): while a game holds the
foreground, and for 15 seconds after, the build runs at below-normal priority on the upper half of
the logical processors (cores 4-7); otherwise at full speed. Windows hands both to every process
the build starts, so the Gradle daemon and each test JVM follow, and a test JVM sizes its workers
from the processors it may use when it starts (build.gradle maxParallelForks, PackWorkers).

  $env:CHIMERA_GRADLE_CPUS = 'all'     full speed throughout, no governor
  $env:CHIMERA_GRADLE_CPUS = 'low'     low priority on the upper half throughout
  $env:CHIMERA_GRADLE_CPUS = '0xF000'  low priority on these processors throughout

A governed build uses its own daemon (a marker in its JVM arguments), which the governor finds and
holds to the mode; a full-speed build uses a daemon without it.
#>
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
$marker = 'chimera.gradle.governed'

# The daemon JVM arguments: gradle.properties' own, plus the governor's marker.
function Get-GovernedJvmArgs {
    $properties = Join-Path $PSScriptRoot '..\gradle.properties'
    $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*org\.gradle\.jvmargs\s*=' } |
            Select-Object -First 1
    $jvmArgs = if ($line) { ($line -split '=', 2)[1].Trim() } else { '' }
    return "$jvmArgs -D$marker=true".Trim()
}

# Starts the governor and waits until it has put this process in its first mode, so the Gradle client
# and any daemon it starts inherit that mode.
function Start-Governor([string]$setting) {
    $ready = Join-Path $env:TEMP "chimera-gradle-governor-$PID.ready"
    Remove-Item -LiteralPath $ready -ErrorAction SilentlyContinue
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $PSScriptRoot 'gradle-governor.ps1')`"",
            '-WrapperPid', $PID, '-Marker', $marker, '-ReadyFile', "`"$ready`"")
    if ($setting -eq 'low') { $arguments += '-AlwaysRestricted' }
    elseif ($setting) { $arguments += @('-AlwaysRestricted', '-RestrictedMask', [Convert]::ToInt64($setting, 16)) }
    $governor = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path -LiteralPath $ready) -and -not $governor.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 100
    }
    Remove-Item -LiteralPath $ready -ErrorAction SilentlyContinue
    if ($governor.HasExited) { Write-Warning 'gradle-governor did not start; the build runs at full speed' }
    return $governor
}

$setting = $env:CHIMERA_GRADLE_CPUS
if ($setting -eq 'auto') { $setting = $null }
$self = Get-Process -Id $PID
$savedAffinity = $self.ProcessorAffinity
$savedPriority = $self.PriorityClass
$governor = $null
$extra = @()
try {
    if ($setting -ne 'all') {
        $governor = Start-Governor $setting
        $extra = @("-Dorg.gradle.jvmargs=$(Get-GovernedJvmArgs)")
    }
    & $wrapper @GradleArguments @extra
    $code = $LASTEXITCODE
} finally {
    if ($governor -and -not $governor.HasExited) { Stop-Process -Id $governor.Id -Force -ErrorAction SilentlyContinue }
    $self.ProcessorAffinity = $savedAffinity
    $self.PriorityClass = $savedPriority
}
exit $code
