<#
Checks whether Chimera still works on a new VulkanMod Reforged jar, before moving the host to it.
chimera.mixins.json sets defaultRequire 1, so every Chimera mixin must apply: one injector that finds no
target, or one shadowed member that is gone, stops the game at launch. That includes Chimera's injectors
into vanilla methods whose body a Reforged mixin overwrites: they must find their target in Reforged's body,
and Mixin 0.8.7 accepts one there only at HEAD, as a whole-method wrap, or from a higher priority. It also
applies no injector in an interface mixin. Run it after adding such a hook, with the pinned jar as -NewJar.

  .\tools\check-reforged.ps1 -NewJar <jar>                   the new Reforged jar against the pinned one
  .\tools\check-reforged.ps1 -NewJar <jar> -OldJar <jar>     a control other than the pinned version
  .\tools\check-reforged.ps1 -NewJar <jar> -Verbose          also every check that passed

Compiles tools\reforged with Prism's Java 21 into a temp folder and runs its inject, merged and refs modes,
with the ASM jars in the Gradle cache. It reads Chimera's compiled classes in build\classes\java\main, so
build first. The old jar, by default the reforged_version gradle.properties pins, is the control: it must
report no failures, or the build or the checker is out of date and the new jar's results cannot be trusted.
Exits 0 when the new jar passes, 1 when it fails, and 2 when only the control fails.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$NewJar,
    [string]$OldJar = ''
)

$ErrorActionPreference = 'Stop'
# -Verbose asks for every check's line; the cmdlets below stay quiet either way.
$detail = $VerbosePreference -ne 'SilentlyContinue'
$VerbosePreference = 'SilentlyContinue'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

# Prism's Java 21, found as gradlew-prism.ps1 finds it; its javac compiles the checker.
$javaHome = if ([string]::IsNullOrWhiteSpace($env:CHIMERA_PRISM_JAVA_HOME)) {
    Join-Path $env:APPDATA 'PrismLauncher\java\java-runtime-delta'
} else {
    $env:CHIMERA_PRISM_JAVA_HOME
}
$javaPath = Join-Path $javaHome 'bin\java.exe'
$javacPath = Join-Path $javaHome 'bin\javac.exe'
foreach ($tool in @($javaPath, $javacPath)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Prism Java 21 was not found: $tool is missing"
    }
}

# The checker reads class files, not sources: without a build there is nothing to check.
$classes = Join-Path $repoRoot 'build\classes\java\main'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'net\chimera\mixin') -PathType Container)) {
    throw "Chimera's compiled classes are missing from $classes; build first: .\tools\gradlew-prism.ps1 compileJava"
}

$gradleHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    Join-Path $env:USERPROFILE '.gradle'
} else {
    $env:GRADLE_USER_HOME
}
$moduleCache = Join-Path $gradleHome 'caches\modules-2\files-2.1'

function Find-CachedJar([string]$group, [string]$module, [string]$version) {
    $directory = Join-Path $moduleCache "$group\$module\$version"
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return $null }
    return Get-ChildItem -LiteralPath $directory -Recurse -File -Filter "$module-$version.jar" |
        Select-Object -First 1 -ExpandProperty FullName
}

# ASM from the Gradle cache: the newest version that has both asm and asm-tree.
$asmJars = $null
$asmRoot = Join-Path $moduleCache 'org.ow2.asm\asm'
if (Test-Path -LiteralPath $asmRoot -PathType Container) {
    $asmVersions = Get-ChildItem -LiteralPath $asmRoot -Directory |
        Where-Object { $_.Name -match '^\d+(\.\d+){1,3}$' } |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($version in $asmVersions) {
        $asm = Find-CachedJar 'org.ow2.asm' 'asm' $version.Name
        $tree = Find-CachedJar 'org.ow2.asm' 'asm-tree' $version.Name
        if ($asm -and $tree) {
            $asmJars = @($asm, $tree)
            break
        }
    }
}
if (-not $asmJars) {
    throw "No ASM with ASM tree in $moduleCache\org.ow2.asm; run any Gradle build once to fetch them"
}

# The control defaults to the Reforged build Chimera compiles against.
if ([string]::IsNullOrWhiteSpace($OldJar)) {
    $properties = Join-Path $repoRoot 'gradle.properties'
    $pin = Select-String -LiteralPath $properties -Pattern '^\s*reforged_version\s*=\s*(\S+)' | Select-Object -First 1
    if (-not $pin) {
        throw 'gradle.properties has no reforged_version; pass -OldJar'
    }
    $pinnedVersion = $pin.Matches[0].Groups[1].Value
    $OldJar = Find-CachedJar 'maven.modrinth' 'vulkanmod-reforged' $pinnedVersion
    if (-not $OldJar) {
        throw "Reforged $pinnedVersion is not in the Gradle cache; pass -OldJar, or run a Gradle build to fetch it"
    }
}
foreach ($jar in @($NewJar, $OldJar)) {
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Jar not found: $jar"
    }
}
$NewJar = (Resolve-Path -LiteralPath $NewJar).Path
$OldJar = (Resolve-Path -LiteralPath $OldJar).Path
$newName = Split-Path -Leaf $NewJar
$oldName = Split-Path -Leaf $OldJar

# A jar's mod version names it in the report, such as 0.3.5-ALPHA+1.21.1; the file name when it has none.
function Get-ModVersion([string]$directory, [string]$jar) {
    $toml = Join-Path $directory 'META-INF\neoforge.mods.toml'
    if (Test-Path -LiteralPath $toml -PathType Leaf) {
        $line = Select-String -LiteralPath $toml -Pattern '^\s*version\s*=\s*"([^"$]+)"' | Select-Object -First 1
        if ($line) { return $line.Matches[0].Groups[1].Value }
    }
    return [IO.Path]::GetFileNameWithoutExtension($jar)
}

# Runs javac or java and returns its output lines; a non-zero exit throws. Both report errors on stderr:
# under 'Stop' a redirected run would end at the first such line, so this scope checks the exit code instead.
function Invoke-Jdk([string]$tool, [string]$name, [string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $tool @arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "$name exited with code $LASTEXITCODE"
    }
    return $output
}

# Runs one mode of the compiled checker.
function Invoke-MixinCheck([string[]]$CheckArguments) {
    $command = @('-cp', $classPath, 'reforged.MixinCheck') + $CheckArguments
    return Invoke-Jdk $javaPath "MixinCheck $($CheckArguments[0])" $command
}

# Prints a mode's SUMMARY lines without the tag, after a label.
function Write-Summary([string]$label, [string[]]$lines) {
    foreach ($line in $lines) {
        if ($line -match '^SUMMARY\t') { Write-Output ('  ' + $label.PadRight(16) + $line.Substring(8)) }
    }
}

# merged prints no SUMMARY: counts its HIT lines (Reforged bodies a Chimera injector selects) and FAIL lines.
function Write-MergedSummary([string]$label, [string[]]$lines) {
    $hits = @($lines | Where-Object { $_ -match '^HIT\t' }).Count
    $failing = @($lines | Where-Object { $_ -match '^FAIL\t' }).Count
    Write-Output ('  ' + $label.PadRight(16) + "Reforged bodies hit=$hits failing=$failing")
}

# Prints every line of a mode's output under a heading, for -Verbose.
function Write-Section([string]$heading, [string[]]$lines) {
    Write-Output ''
    Write-Output $heading
    foreach ($line in $lines) { Write-Output "  $line" }
}

function Select-Failures([string[]]$lines) {
    $lines | Where-Object { $_ -match '^FAIL\t' }
}

# The checker and both jars go to a fresh folder under TEMP, removed at the end.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$work = Join-Path $env:TEMP ('check-reforged-' + [guid]::NewGuid().ToString('N'))
$checkerClasses = Join-Path $work 'checker'
$oldClasses = Join-Path $work 'old'
$newClasses = Join-Path $work 'new'
New-Item -ItemType Directory -Path $checkerClasses | Out-Null
$exitCode = 0
try {
    # The checker is a package of several files, which the single-file source launcher cannot run.
    $asmClassPath = $asmJars -join ';'
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'reforged') -Filter '*.java' -File |
        ForEach-Object { $_.FullName })
    $null = Invoke-Jdk $javacPath 'javac' (@('-cp', $asmClassPath, '-d', $checkerClasses) + $sources)
    $classPath = $checkerClasses + ';' + $asmClassPath

    [IO.Compression.ZipFile]::ExtractToDirectory($OldJar, $oldClasses)
    [IO.Compression.ZipFile]::ExtractToDirectory($NewJar, $newClasses)
    $oldLabel = Get-ModVersion $oldClasses $OldJar
    $newLabel = Get-ModVersion $newClasses $NewJar

    $controlInject = @(Invoke-MixinCheck @('inject', $classes, $oldClasses))
    $newArguments = @('inject', $classes, $newClasses)
    if ($detail) { $newArguments += '-v' }
    $newInject = @(Invoke-MixinCheck $newArguments)
    $controlMerged = @(Invoke-MixinCheck @('merged', $classes, $oldClasses))
    $newMerged = @(Invoke-MixinCheck @('merged', $classes, $newClasses))
    $refs = @(Invoke-MixinCheck @('refs', $classes, $oldClasses, $newClasses, $oldLabel, $newLabel))

    # What breaks Chimera on the new jar: a missed injection or member (inject, or merged for vanilla methods
    # Reforged overwrites), an unresolved reference or type, an override of a now-final method, or an
    # abstract method a Chimera subclass leaves unimplemented.
    $refsFailures = @($refs | Where-Object {
        $_ -match '^(FAIL|TYPEMISSING|OVERRIDE-FINAL)\t' -or ($_ -match '^SUBTYPE\t' -and $_ -notmatch '\)=\[\]$')
    })
    $failures = @(Select-Failures $newInject) + @(Select-Failures $newMerged) + $refsFailures
    # The same against the control, which Chimera is built for: refs reports it as CTRLFAIL, as a type
    # the old jar lacks too, or as an abstract method left unimplemented there.
    $refsControlFailures = @($refs | Where-Object {
        $_ -match '^CTRLFAIL\t' -or ($_ -match '^TYPEMISSING\t' -and $_ -match ' lacks\)\t') -or
            ($_ -match '^SUBTYPE\t' -and $_ -notmatch '\)=\[\]\t')
    })
    $controlFailures = @(Select-Failures $controlInject) + @(Select-Failures $controlMerged) + $refsControlFailures
    # Worth a look, but not failures: ambiguous selectors, changed flags, overrides gained or lost.
    $reviewTags = '^(INFO|CHANGED|OVERRIDE-LOST|OVERRIDE-NEW|SUPER-ACCESS)\t'
    $review = @(($newInject + $refs) | Where-Object { $_ -match $reviewTags })

    Write-Output "Reforged check: $newLabel ($newName) against the control $oldLabel ($oldName)"
    if ($detail) {
        Write-Section "inject, $newLabel" $newInject
        Write-Section "merged, $newLabel" $newMerged
        Write-Section "refs, $oldLabel to $newLabel" $refs
    }
    Write-Output ''
    Write-Output 'Summary'
    Write-Summary 'control inject:' $controlInject
    Write-Summary 'new inject:' $newInject
    Write-MergedSummary 'control merged:' $controlMerged
    Write-MergedSummary 'new merged:' $newMerged
    Write-Summary 'refs:' $refs

    if ($controlFailures.Count -gt 0) {
        Write-Warning ("CONTROL FAILED: $($controlFailures.Count) failure(s) against $oldLabel, which should have" +
            " none. Chimera as built does not launch on it, or its build or MixinCheck is out of date; fix that" +
            " before trusting the results for $newLabel.")
        foreach ($line in $controlFailures) { Write-Warning $line }
        $exitCode = 2
    }
    if ($review.Count -gt 0) {
        Write-Output ''
        Write-Output "To review in $newLabel (not failures):"
        $review | ForEach-Object { Write-Output "  $_" }
    }
    Write-Output ''
    if ($failures.Count -gt 0) {
        Write-Output "Failures in ${newLabel}:"
        $failures | ForEach-Object { Write-Output "  $_" }
        Write-Output ''
        Write-Output "FAILED: $($failures.Count) problem(s); Chimera as built does not work on $newName"
        $exitCode = 1
    } else {
        Write-Output "OK: every Chimera mixin and Reforged reference resolves in $newName"
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force
}
exit $exitCode
