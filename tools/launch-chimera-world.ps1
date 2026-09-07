[CmdletBinding()]
param(
    [string]$PrismPath = (Join-Path $env:LOCALAPPDATA "Programs\PrismLauncher\prismlauncher.exe"),
    [string]$InstanceId = "CHIMERA",
    [string]$WorldName = "Chimera Dev",
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    throw "launch-chimera-world: $Message"
}

function Test-SessionLock([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }

    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        return $false
    }
    catch [IO.IOException] {
        return $true
    }
    catch {
        return $false
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

$prismRoot = Join-Path $env:APPDATA "PrismLauncher"
$instancePath = Join-Path $prismRoot ("instances\" + $InstanceId)
$minecraftPath = Join-Path $instancePath "minecraft"
$packPath = Join-Path $instancePath "mmc-pack.json"
$worldPath = Join-Path $minecraftPath ("saves\" + $WorldName)
$lockPath = Join-Path $worldPath "session.lock"

if (-not (Test-Path -LiteralPath $PrismPath -PathType Leaf)) {
    Fail "Prism Launcher was not found at $PrismPath"
}
if (-not (Test-Path -LiteralPath $packPath -PathType Leaf)) {
    Fail "Instance metadata was not found at $packPath"
}
if (-not (Test-Path -LiteralPath $worldPath -PathType Container)) {
    Fail "World was not found at $worldPath"
}

$oldJavaIds = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)

Write-Output ("QUICKPLAY_INSTANCE=" + $InstanceId)
Write-Output ("QUICKPLAY_WORLD=" + $WorldName)
Write-Output "QUICKPLAY_MODE=prism-cli-world"

$quotedWorldName = '"' + $WorldName.Replace('"', '\"') + '"'
$launcher = Start-Process -FilePath $PrismPath -ArgumentList @("-l", $InstanceId, "-w", $quotedWorldName) -PassThru
$ready = $false
$readyPid = $null

for ($second = 0; $second -lt $ReadyTimeoutSeconds; $second++) {
    Start-Sleep -Seconds 1

    $newJava = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object {
        $oldJavaIds -notcontains $_.Id
    })

    if ($newJava.Count -gt 0 -and (Test-SessionLock $lockPath)) {
        $readyPid = $newJava[0].Id
        $ready = $true
        break
    }
}

if (-not $ready) {
    if ($launcher.HasExited) {
        Fail "Prism exited before Minecraft entered $WorldName"
    }
    Fail "Timed out waiting for Minecraft to enter $WorldName"
}

Write-Output "READY=1"
Write-Output ("MINECRAFT_PID=" + $readyPid)
Write-Output "The instance is now in the requested world. Keep this process open while capturing or hot-swapping."

while (@(Get-Process -Id $readyPid -ErrorAction SilentlyContinue).Count -gt 0) {
    Start-Sleep -Seconds 2
}

Write-Output "MINECRAFT_EXITED=1"
