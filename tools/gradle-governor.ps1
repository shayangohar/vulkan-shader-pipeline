<#
Keeps a Gradle build out of a game's way; gradlew-prism.ps1 starts it beside each build. Every two
seconds it asks whether a game holds the foreground: a window whose own process keeps the GPU's 3D
engine busy, at 10% or more for two looks running, or 2% for a window with no title bar covering
its monitor (fullscreen or borderless, which a game holds in its menus too). A game renders in its
own process; a browser or an Electron app (Discord, Claude) renders in a separate GPU process, so
its window reads 0% however it is sized. While a game holds the foreground, and for 15 seconds
after, the build's processes run at below-normal priority on the upper half of the logical
processors (cores 4-7 of 8); otherwise at normal priority on all of them. The desktop, the lock
screen and the shell's own windows are never a game. Each change is logged to
build\gradle-governor.log.

The build's processes: the wrapper and its descendants (the Gradle client), and every Gradle daemon
whose JVM arguments carry the marker, with its descendants (the test JVMs). A process the build
starts inherits its parent's priority and processors, so a new test JVM starts in the current mode.

  .\tools\gradle-governor.ps1 -Probe    prints what it sees in the foreground now, and exits
#>
param(
    [int]$WrapperPid,
    [string]$Marker = 'chimera.gradle.governed',
    [long]$RestrictedMask = 0,
    [switch]$AlwaysRestricted,
    [string]$ReadyFile,
    [switch]$Probe
)

$ErrorActionPreference = 'Stop'
$ShellProcesses = @('explorer', 'LockApp', 'ShellExperienceHost', 'StartMenuExperienceHost', 'SearchHost',
        'SearchApp', 'TextInputHost', 'ScreenClippingHost', 'ApplicationFrameHost')
$GpuThreshold = 10
$FullscreenGpuThreshold = 2
$ReleaseSeconds = 15
$count = [Environment]::ProcessorCount
$AllMask = ([long]1 -shl $count) - 1
if ($RestrictedMask -eq 0) {
    $RestrictedMask = $AllMask -bxor (([long]1 -shl [math]::Floor($count / 2)) - 1)
}
$LogPath = Join-Path $PSScriptRoot '..\build\gradle-governor.log'

if (-not ('ChimeraForeground' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class ChimeraForeground {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct MonitorInfo {
        public int Size; public Rect Monitor; public Rect Work; public uint Flags;
    }
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr window, int index);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr window, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetClassName(IntPtr window, StringBuilder name, int count);

    public static uint ProcessId() {
        IntPtr window = GetForegroundWindow();
        if (window == IntPtr.Zero) return 0;
        uint processId;
        GetWindowThreadProcessId(window, out processId);
        return processId;
    }

    /** A foreground window with no title bar that covers its whole monitor, the desktop's aside. */
    public static bool Fullscreen() {
        IntPtr window = GetForegroundWindow();
        if (window == IntPtr.Zero) return false;
        StringBuilder name = new StringBuilder(64);
        GetClassName(window, name, name.Capacity);
        string type = name.ToString();
        if (type == "Progman" || type == "WorkerW" || type == "Shell_TrayWnd") return false;
        const int Caption = 0x00C00000;
        if ((GetWindowLong(window, -16) & Caption) == Caption) return false;
        Rect rect;
        if (!GetWindowRect(window, out rect)) return false;
        MonitorInfo info = new MonitorInfo();
        info.Size = Marshal.SizeOf(typeof(MonitorInfo));
        if (!GetMonitorInfo(MonitorFromWindow(window, 2), ref info)) return false;
        return rect.Left <= info.Monitor.Left && rect.Top <= info.Monitor.Top
                && rect.Right >= info.Monitor.Right && rect.Bottom >= info.Monitor.Bottom;
    }
}
'@
}

$script:gpuCounters = @{}
$script:gpuStreak = 0
$script:gpuProcess = 0

# The process's 3D-engine use since the last look, in percent, summed over its engines: 0 where it
# has none, and null while its counters are new (a counter's first sample reads nothing).
function Get-Gpu3DPercent([int]$processId) {
    try {
        $names = @([System.Diagnostics.PerformanceCounterCategory]::new('GPU Engine').GetInstanceNames() |
                Where-Object { $_ -like "pid_$($processId)_*engtype_3D" })
    } catch {
        return 0
    }
    $total = 0.0
    $sampled = $false
    foreach ($name in $names) {
        if (-not $script:gpuCounters.ContainsKey($name)) {
            $counter = [System.Diagnostics.PerformanceCounter]::new('GPU Engine', 'Utilization Percentage', $name, $true)
            [void]$counter.NextValue()
            $script:gpuCounters[$name] = $counter
            continue
        }
        try { $total += $script:gpuCounters[$name].NextValue(); $sampled = $true } catch { }
    }
    foreach ($stale in @($script:gpuCounters.Keys | Where-Object { $names -notcontains $_ })) {
        $script:gpuCounters[$stale].Dispose()
        $script:gpuCounters.Remove($stale)
    }
    if ($names.Count -gt 0 -and -not $sampled) { return $null }
    return [math]::Round($total)
}

# The game in the foreground, as {Name, Reason}, or null: a process whose own GPU use has held for
# $looks looks running, at the threshold its window's shape sets.
function Get-ForegroundGame([int]$looks = 2) {
    $processId = [int][ChimeraForeground]::ProcessId()
    $process = if ($processId -gt 0) { Get-Process -Id $processId -ErrorAction SilentlyContinue }
    $path = try { $process.Path } catch { $null }
    if (-not $process -or $ShellProcesses -contains $process.ProcessName -or $path -like '*.scr') {
        $script:gpuStreak = 0
        return $null
    }
    if ($processId -ne $script:gpuProcess) { $script:gpuStreak = 0; $script:gpuProcess = $processId }
    $gpu = Get-Gpu3DPercent $processId
    if ($null -eq $gpu) { return $null }
    $fullscreen = [ChimeraForeground]::Fullscreen()
    $threshold = if ($fullscreen) { $FullscreenGpuThreshold } else { $GpuThreshold }
    $script:gpuStreak = if ($gpu -ge $threshold) { $script:gpuStreak + 1 } else { 0 }
    if ($script:gpuStreak -ge $looks) {
        $shape = if ($fullscreen) { 'fullscreen, ' } else { '' }
        return [pscustomobject]@{ Name = $process.ProcessName; Reason = "${shape}GPU 3D $gpu%" }
    }
    return $null
}

# The wrapper, the marked daemons, and their descendants, this governor aside.
function Get-BuildProcessIds {
    $all = Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, CommandLine, CreationDate
    $byParent = @{}
    foreach ($process in $all) {
        $parent = [int]$process.ParentProcessId
        if (-not $byParent.ContainsKey($parent)) { $byParent[$parent] = [System.Collections.ArrayList]::new() }
        [void]$byParent[$parent].Add($process)
    }
    $roots = @($all | Where-Object {
        $_.ProcessId -eq $WrapperPid -or ($_.CommandLine -and $_.CommandLine.Contains("-D$Marker="))
    })
    $found = [System.Collections.Generic.HashSet[int]]::new()
    $queue = [System.Collections.Queue]::new()
    foreach ($root in $roots) { $queue.Enqueue($root) }
    while ($queue.Count -gt 0) {
        $process = $queue.Dequeue()
        if (-not $found.Add([int]$process.ProcessId)) { continue }
        foreach ($child in @($byParent[[int]$process.ProcessId])) {
            # A recycled parent id names an older process that is no child of this one.
            if ($child -and $child.CreationDate -ge $process.CreationDate) { $queue.Enqueue($child) }
        }
    }
    [void]$found.Remove($PID)
    return $found
}

function Set-BuildMode([bool]$restricted) {
    $priority = if ($restricted) { 'BelowNormal' } else { 'Normal' }
    $mask = if ($restricted) { $RestrictedMask } else { $AllMask }
    foreach ($processId in Get-BuildProcessIds) {
        try {
            $process = [System.Diagnostics.Process]::GetProcessById($processId)
            if ($process.PriorityClass -ne $priority) { $process.PriorityClass = $priority }
            if ([long]$process.ProcessorAffinity -ne $mask) { $process.ProcessorAffinity = [IntPtr]$mask }
        } catch {
            # The process ended, or is not ours to change.
        }
    }
}

function Write-GovernorLog([string]$line) {
    New-Item -ItemType Directory -Force -Path (Split-Path $LogPath) | Out-Null
    Add-Content -LiteralPath $LogPath -Value ('{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $line) -Encoding utf8
}

function Format-Mode([bool]$restricted, $game) {
    $mode = if ($restricted) { 'low priority, processors 0x{0:X}' -f $RestrictedMask } else { 'full speed' }
    $why = if ($AlwaysRestricted) { 'always restricted' }
           elseif ($game) { "game in the foreground: $($game.Name) ($($game.Reason))" }
           elseif ($restricted) { "no game for under $ReleaseSeconds s" }
           else { 'no game in the foreground' }
    return "$mode - $why"
}

# Two looks a second apart: the first starts the GPU counters, so a windowed game counts from the start.
function Get-ForegroundGameNow {
    [void](Get-ForegroundGame 1)
    Start-Sleep -Seconds 1
    return Get-ForegroundGame 1
}

if ($Probe) {
    $processId = [int][ChimeraForeground]::ProcessId()
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    $game = Get-ForegroundGameNow
    "foreground: $($process.ProcessName) (pid $processId), fullscreen=$([ChimeraForeground]::Fullscreen())," +
            " GPU 3D $(Get-Gpu3DPercent $processId)%"
    'verdict: ' + (Format-Mode ($null -ne $game) $game)
    return
}

$game = if ($AlwaysRestricted) { $null } else { Get-ForegroundGameNow }
$restricted = $AlwaysRestricted -or $null -ne $game
$lastGame = if ($game) { Get-Date } else { [datetime]::MinValue }
Set-BuildMode $restricted
Write-GovernorLog ("build $WrapperPid starts: " + (Format-Mode $restricted $game))
if ($ReadyFile) { New-Item -ItemType File -Force -Path $ReadyFile | Out-Null }
while (Get-Process -Id $WrapperPid -ErrorAction SilentlyContinue) {
    Start-Sleep -Seconds 2
    try {
        $game = if ($AlwaysRestricted) { $null } else { Get-ForegroundGame }
        if ($game) { $lastGame = Get-Date }
        $wanted = $AlwaysRestricted -or ((Get-Date) - $lastGame).TotalSeconds -lt $ReleaseSeconds
        if ($wanted -ne $restricted) { Write-GovernorLog ("build ${WrapperPid}: " + (Format-Mode $wanted $game)) }
        $restricted = $wanted
        Set-BuildMode $restricted
    } catch {
        # A look that failed (a process query, the log file) is retried at the next one.
    }
}
